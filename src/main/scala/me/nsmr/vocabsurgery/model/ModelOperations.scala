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

/** Pure domain result, with neither payload bytes nor output paths / file handles.
  * `outputFiles` are written from planned content; every other source file is copied, and changed shards are rebuilt.
  */
final case class ModelUpdatePlan(family: ModelFamily, policy: PruningPolicy, analysis: VocabularyAnalysis,
    modules: Vector[VocabularyModule], shards: Vector[ShardProjection], outputFiles: Vector[OutputFile],
    notices: Vector[MetadataNotice], parameterCountPolicy: ParameterCountPolicy):
  def projection: VocabularyProjection = analysis.projection
  def removedRows: Int = projection.originalSize - projection.compactSize
  def originalPayloadBytes: ByteCount = ByteCount(shards.foldLeft(0L)((sum, shard) =>
    Math.addExact(sum, shard.source.fileSize.longValue - shard.source.payloadStart.longValue)))
  def payloadBytes: ByteCount = ByteCount(shards.foldLeft(0L)((sum, shard) => Math.addExact(sum, shard.payloadBytes.longValue)))
  def savedPayloadBytes: ByteCount = ByteCount(originalPayloadBytes.longValue - payloadBytes.longValue)
  def file(name: String): OutputFile = outputFiles.find(_.name == name).getOrElse(DomainChecks.fail("missing-output-file", name, "not planned"))
  def json(name: String): Json = file(name) match
    case OutputFile.JsonFile(_, value) => value
    case _ => DomainChecks.fail("not-json-output", name, "planned file is text")

object ModelOperations:
  /** Describe a vocabulary update without touching the input or writing a checkpoint. */
  def plan(source: ModelSnapshot, policy: PruningPolicy,
      parameterCountPolicy: ParameterCountPolicy = ParameterCountPolicy.OmitUnverified): ModelUpdatePlan =
    val config = source.config
    val family = config.family
    DomainChecks.check(ModelFamily.of(config.original) == family &&
      family.vocabSize.forall(_.get(config.original).contains(Json.fromInt(config.vocabSize))) &&
      family.hiddenSize.get(config.original).contains(Json.fromInt(config.hiddenSize)),
      "inconsistent-config", "config", "typed architecture and original JSON disagree")
    val scheme = family.tokenizer
    scheme.validate(source.tokenizer)
    val requiredIds = TokenReferences.requiredIds(config.original, family.tokenIdScopes, family.tokenIdFields) ++
      TokenReferences.requiredIds(source.generation) ++ TokenReferences.requiredTokenizerIds(source.tokenizer.original, source.tokenizerConfig)
    val model = DomainJson.fields(DomainJson.required(source.tokenizer.original, "model", "tokenizer"), "model")
    val unknownToken = model("unk_token").filterNot(_.isNull).map(DomainJson.text(_, "model.unk_token")).toSet
    val templates = source.chatTemplate.map(_.text).toVector ++
      DomainJson.fields(source.tokenizerConfig, "tokenizer_config")("chat_template").toVector.flatMap(templateTexts)
    val templateProtocol = templates.flatMap(text => "<\\|[^<>\\s]+\\|>".r.findAllIn(text)).toSet
    val contents = family.requiredTokens ++ TokenReferences.configuredContents(source.tokenizerConfig) ++ unknownToken ++ templateProtocol
    val analysis = VocabularyOperations.analyze(source.tokenizer, scheme, config.vocabSize, policy, requiredIds, contents)
    val projection = analysis.projection
    val allTensors = source.shards.flatMap(_.tensors)
    DomainChecks.check(allTensors.map(_.name).distinct.size == allTensors.size, "duplicate-tensor", "snapshot", "tensor exists in multiple shards")
    val actualMap = source.shards.flatMap(s => s.tensors.map(t => t.name -> s.path.getFileName.toString)).toMap
    DomainChecks.check(actualMap == source.index.weightMap, "inconsistent-index", "weight_map", "index and shards disagree")
    val tensorsByName = allTensors.map(t => t.name -> t).toMap
    val modules = family.vocabularyModules(config.original).map(VocabularyModule.validate(_, config, tensorsByName))
    val targets = modules.flatMap(_.tensors.map(_.name)).toSet
    val shards = source.shards.sortBy(_.path.getFileName.toString).map(ShardProjection.plan(_, targets, projection))
    val oldBytes = shards.foldLeft(0L)((sum, shard) => Math.addExact(sum, shard.source.fileSize.longValue - shard.source.payloadStart.longValue))
    val newBytes = shards.foldLeft(0L)((sum, shard) => Math.addExact(sum, shard.payloadBytes.longValue))
    DomainChecks.check(source.index.totalSize.forall(_.longValue == oldBytes), "inconsistent-index", "metadata.total_size", "payload size disagrees")
    val rewrittenConfig = family.vocabSize.foldLeft(TokenReferences.rewrite(config.original, projection, family.tokenIdScopes, family.tokenIdFields)) {
      (json, path) => path.modify(json)(_ => Json.fromInt(projection.compactSize))
    }
    val generation = TokenReferences.rewrite(source.generation, projection)
    val (tokenizer, tokenizerConfig) = TokenReferences.rewriteTokenizerReferences(TokenizerRewrite(source.tokenizer, analysis), source.tokenizerConfig, projection)
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
          val removed = Math.multiplyExact(Math.multiplyExact(modules.size.toLong, config.hiddenSize.toLong), (config.vocabSize - projection.compactSize).toLong)
          DomainChecks.check(old >= removed, "invalid-parameter-count", "total_parameters", "removed logical elements exceed total")
          val after = Json.fromLong(old - removed)
          metadata = metadata.add("total_parameters", after)
          notices += MetadataNotice("index.metadata.total_parameters", "verified logical weight element count", before, Some(after))
    }
    metadata = metadata.add("total_size", Json.fromLong(newBytes))
    val suppressions = Vector("suppress_tokens", "begin_suppress_tokens", "bad_words_ids")
    def notice(label: String, before: Option[Json], after: Option[Json]): Unit = before.foreach { value =>
      if !after.contains(value) then notices += MetadataNotice(label, "projected IDs and removed deleted-token references", value, after)
    }
    suppressions.foreach(field => notice(s"generation.$field", JsonPath(field).get(source.generation), JsonPath(field).get(generation)))
    for scope <- family.tokenIdScopes; field <- suppressions do
      notice(s"config.${(scope / field).text}", (scope / field).get(config.original), (scope / field).get(rewrittenConfig))
    val index = Json.fromJsonObject(rootIndex.add("metadata", Json.fromJsonObject(metadata)))
    val outputFiles = Vector(OutputFile.JsonFile("config.json", rewrittenConfig), OutputFile.JsonFile("generation_config.json", generation),
      OutputFile.JsonFile("tokenizer.json", tokenizer), OutputFile.JsonFile("tokenizer_config.json", tokenizerConfig),
      OutputFile.JsonFile("model.safetensors.index.json", index)) ++
      scheme.companions(source.tokenizer, source.tokenizerCompanions, tokenizer) ++
      family.familyFiles(config.original, source.familyFiles, projection)
    DomainChecks.check(outputFiles.map(_.name).distinct.size == outputFiles.size, "duplicate-output-file", "plan", outputFiles.map(_.name).mkString(", "))
    ModelUpdatePlan(family, policy, analysis, modules, shards, outputFiles, notices.result(), parameterCountPolicy)

  private def templateTexts(value: Json): Vector[String] = value.asString match
    case Some(text) => Vector(text)
    case None =>
      DomainJson.array(value, "chat_template").map(item => DomainJson.text(DomainJson.required(item, "template", "chat_template"), "template"))
