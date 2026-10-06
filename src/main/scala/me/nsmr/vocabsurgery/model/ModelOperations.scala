package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.tokenizer.*

enum ParameterCountPolicy:
  /** No evidence for the source counter's semantics: remove it and record a notice. */
  case OmitUnverified
  /** Caller has verified that total_parameters counts logical dense weight elements. */
  case VerifiedLogicalWeights

final case class MetadataNotice(location: String, reason: String, before: Json, after: Option[Json])

/** Pure domain result, with neither payload bytes nor output paths / file handles. */
final case class ModelUpdatePlan(policy: PruningPolicy, analysis: VocabularyAnalysis, modules: Vector[QuantizedVocabularyModule],
    shards: Vector[ShardProjection], config: Json, generation: Json, tokenizer: Json, tokenizerConfig: Json,
    index: Json, notices: Vector[MetadataNotice], parameterCountPolicy: ParameterCountPolicy):
  def projection: VocabularyProjection = analysis.projection
  def removedRows: Int = projection.originalSize - projection.compactSize
  def originalPayloadBytes: ByteCount = ByteCount(shards.foldLeft(0L)((sum, shard) =>
    Math.addExact(sum, shard.source.fileSize.longValue - shard.source.payloadStart.longValue)))
  def payloadBytes: ByteCount = ByteCount(shards.foldLeft(0L)((sum, shard) => Math.addExact(sum, shard.payloadBytes.longValue)))
  def savedPayloadBytes: ByteCount = ByteCount(originalPayloadBytes.longValue - payloadBytes.longValue)

object ModelOperations:
  /** Describe a Qwen3-Next vocabulary update without touching the input or writing a checkpoint. */
  def plan(source: ModelSnapshot, policy: PruningPolicy,
      parameterCountPolicy: ParameterCountPolicy = ParameterCountPolicy.OmitUnverified): ModelUpdatePlan =
    val config = source.config
    DomainChecks.check(config.modelType == "qwen3_next" && !config.tieWordEmbeddings,
      "unsupported-model", "config", "expected Qwen3-Next with separate embedding and LM head")
    val configFields = DomainJson.fields(config.original, "config")
    DomainChecks.check(configFields("vocab_size").contains(Json.fromInt(config.vocabSize)) &&
      configFields("hidden_size").contains(Json.fromInt(config.hiddenSize)) &&
      configFields("model_type").contains(Json.fromString(config.modelType)) &&
      configFields("tie_word_embeddings").forall(_ == Json.fromBoolean(config.tieWordEmbeddings)),
      "inconsistent-config", "config", "typed architecture and original JSON disagree")
    TokenizerConstraints.validate(source.tokenizer)
    val requiredIds = TokenReferences.requiredRootIds(config.original) ++ TokenReferences.requiredRootIds(source.generation.original) ++
      TokenReferences.requiredTokenizerIds(source.tokenizer.original, source.tokenizerConfig)
    val model = DomainJson.fields(DomainJson.required(source.tokenizer.original, "model", "tokenizer"), "model")
    val unknownToken = model("unk_token").filterNot(_.isNull).map(DomainJson.text(_, "model.unk_token")).toSet
    val templates = source.chatTemplate.text +: DomainJson.fields(source.tokenizerConfig, "tokenizer_config")("chat_template").toVector.flatMap(templateTexts)
    val templateProtocol = templates.flatMap(text => "<\\|[^<>\\s]+\\|>".r.findAllIn(text)).toSet
    val contents = ProtocolTokens.CoderNextRequired ++ TokenReferences.configuredContents(source.tokenizerConfig) ++ unknownToken ++ templateProtocol
    val analysis = VocabularyOperations.analyze(source.tokenizer, config.vocabSize, policy, requiredIds, contents)
    val allTensors = source.shards.flatMap(_.tensors)
    DomainChecks.check(allTensors.map(_.name).distinct.size == allTensors.size, "duplicate-tensor", "snapshot", "tensor exists in multiple shards")
    val actualMap = source.shards.flatMap(s => s.tensors.map(t => t.name -> s.path.getFileName.toString)).toMap
    DomainChecks.check(actualMap == source.index.weightMap, "inconsistent-index", "weight_map", "index and shards disagree")
    val modules = Vector("model.embed_tokens", "lm_head").map(QuantizedVocabularyModule.validate(_, config, allTensors.map(t => t.name -> t).toMap))
    val targets = modules.flatMap(_.tensors.map(_.name)).toSet
    DomainChecks.check(allTensors.filter(t => t.name.startsWith("model.embed_tokens.") || t.name.startsWith("lm_head.")).map(_.name).toSet == targets,
      "unsupported-vocabulary-tensor", "snapshot", "unexpected embedding / LM-head companion")
    val shards = source.shards.sortBy(_.path.getFileName.toString).map(ShardProjection.plan(_, targets, analysis.projection))
    val oldBytes = shards.foldLeft(0L)((sum, shard) => Math.addExact(sum, shard.source.fileSize.longValue - shard.source.payloadStart.longValue))
    val newBytes = shards.foldLeft(0L)((sum, shard) => Math.addExact(sum, shard.payloadBytes.longValue))
    DomainChecks.check(source.index.totalSize.forall(_.longValue == oldBytes), "inconsistent-index", "metadata.total_size", "payload size disagrees")
    val rewrittenConfig = Json.fromJsonObject(DomainJson.fields(TokenReferences.rewriteRoot(config.original, analysis.projection), "config")
      .add("vocab_size", Json.fromInt(analysis.projection.compactSize)))
    val generation = TokenReferences.rewriteRoot(source.generation.original, analysis.projection)
    val (tokenizer, tokenizerConfig) = TokenReferences.rewriteTokenizerReferences(TokenizerRewrite(source.tokenizer, analysis), source.tokenizerConfig, analysis.projection)
    val rootIndex = DomainJson.fields(source.index.original, "index")
    DomainChecks.check(DomainJson.fields(DomainJson.required(source.index.original, "weight_map", "index"), "weight_map").toVector
      .map((name, filename) => name -> DomainJson.text(filename, name)).toMap == actualMap, "inconsistent-index", "weight_map", "original JSON and typed index disagree")
    var metadata = rootIndex("metadata").map(DomainJson.fields(_, "index.metadata")).getOrElse(io.circe.JsonObject.empty)
    val notices = Vector.newBuilder[MetadataNotice]
    metadata("total_parameters").foreach { before =>
      parameterCountPolicy match
        case ParameterCountPolicy.OmitUnverified =>
          metadata = metadata.remove("total_parameters")
          notices += MetadataNotice("index.metadata.total_parameters", "source counting semantics unverified", before, None)
        case ParameterCountPolicy.VerifiedLogicalWeights =>
          val old = before.asNumber.flatMap(_.toLong).getOrElse(DomainChecks.fail("invalid-parameter-count", "total_parameters", "expected 64-bit integer"))
          DomainChecks.check(before.asNumber.get.toString.matches("0|[1-9][0-9]*"), "invalid-parameter-count", "total_parameters", "expected nonnegative integer literal")
          val removed = Math.multiplyExact(Math.multiplyExact(modules.size.toLong, config.hiddenSize.toLong), (config.vocabSize - analysis.projection.compactSize).toLong)
          DomainChecks.check(old >= removed, "invalid-parameter-count", "total_parameters", "removed logical elements exceed total")
          val after = Json.fromLong(old - removed)
          metadata = metadata.add("total_parameters", after)
          notices += MetadataNotice("index.metadata.total_parameters", "verified logical weight element count", before, Some(after))
    }
    metadata = metadata.add("total_size", Json.fromLong(newBytes))
    Vector("suppress_tokens", "begin_suppress_tokens", "bad_words_ids").foreach { field =>
      DomainJson.fields(source.generation.original, "generation")(field).foreach { before =>
        val after = DomainJson.fields(generation, "generation")(field)
        if !after.contains(before) then notices += MetadataNotice(s"generation.$field", "projected IDs and removed deleted-token references", before, after)
      }
      configFields(field).foreach { before =>
        val after = DomainJson.fields(rewrittenConfig, "config")(field)
        if !after.contains(before) then notices += MetadataNotice(s"config.$field", "projected IDs and removed deleted-token references", before, after)
      }
    }
    val index = Json.fromJsonObject(rootIndex.add("metadata", Json.fromJsonObject(metadata)))
    ModelUpdatePlan(policy, analysis, modules, shards, rewrittenConfig, generation, tokenizer, tokenizerConfig, index, notices.result(), parameterCountPolicy)

  private def templateTexts(value: Json): Vector[String] = value.asString match
    case Some(text) => Vector(text)
    case None =>
      DomainJson.array(value, "chat_template").map(item => DomainJson.text(DomainJson.required(item, "template", "chat_template"), "template"))

/** The graph algorithm assumes plain concatenating ByteLevel BPE with an NFC normalizer. */
object TokenizerConstraints:
  def validate(tokenizer: TokenizerData): Unit =
    val root = DomainJson.fields(tokenizer.original, "tokenizer")
    val model = DomainJson.fields(DomainJson.required(tokenizer.original, "model", "tokenizer"), "model")
    DomainChecks.check(model("type").contains(Json.fromString("BPE")), "unsupported-tokenizer", "model.type", "expected BPE")
    Vector("continuing_subword_prefix", "end_of_word_suffix").foreach { field =>
      DomainChecks.check(model(field).forall(v => v.isNull || v == Json.fromString("")), "unsupported-tokenizer", field, "affixed merges are unsupported")
    }
    Vector("byte_fallback", "ignore_merges").foreach { field =>
      DomainChecks.check(model(field).forall(_ == Json.False), "unsupported-tokenizer", field, "must be false")
    }
    val normalizer = root("normalizer").getOrElse(Json.Null)
    DomainChecks.check(normalizer.hcursor.get[String]("type").contains("NFC"), "unsupported-normalizer", "normalizer", "expected NFC")
    def containsByteLevel(value: Json): Boolean =
      val fields = DomainJson.fields(value, "pre_tokenizer")
      fields("type").flatMap(_.asString) match
        case Some("ByteLevel") => true
        case Some("Sequence") => DomainJson.array(DomainJson.required(value, "pretokenizers", "pre_tokenizer"), "pretokenizers").exists(containsByteLevel)
        case _ => false
    DomainChecks.check(root("pre_tokenizer").exists(containsByteLevel), "unsupported-tokenizer", "pre_tokenizer", "expected ByteLevel")
    root("decoder").foreach(value => DomainChecks.check(value.hcursor.get[String]("type").contains("ByteLevel"),
      "unsupported-tokenizer", "decoder", "expected ByteLevel"))
