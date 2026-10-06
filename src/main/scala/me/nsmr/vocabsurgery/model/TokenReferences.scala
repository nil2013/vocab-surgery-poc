package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.tokenizer.VocabularyProjection

private[vocabsurgery] object DomainJson:
  def fields(value: Json, at: String) = value.asObject.getOrElse(DomainChecks.fail("invalid-json-object", at, "expected object"))
  def array(value: Json, at: String): Vector[Json] = value.asArray.getOrElse(DomainChecks.fail("invalid-json-array", at, "expected array"))
  def integer(value: Json, at: String): Int =
    val number = value.asNumber.getOrElse(DomainChecks.fail("invalid-token-id", at, "expected integer"))
    DomainChecks.check(number.toString.matches("(0|[1-9][0-9]*)"), "invalid-token-id", at, "expected nonnegative integer literal")
    number.toInt.getOrElse(DomainChecks.fail("invalid-token-id", at, "integer exceeds ID range"))
  def required(value: Json, name: String, at: String): Json = fields(value, at)(name)
    .getOrElse(DomainChecks.fail("missing-json-field", s"$at.$name", "required field is absent"))
  def text(value: Json, at: String): String = value.asString.getOrElse(DomainChecks.fail("invalid-json-string", at, "expected string"))

/** Schema-specific token references; ordinary hyperparameters and template strings are untouched. */
object TokenReferences:
  private val scalarOrList = Set("bos_token_id", "eos_token_id", "pad_token_id", "decoder_start_token_id",
    "forced_bos_token_id", "forced_eos_token_id", "unk_token_id", "sep_token_id", "cls_token_id", "mask_token_id")
  private val optionalLists = Set("suppress_tokens", "begin_suppress_tokens")

  def requiredRootIds(json: Json): Set[OriginalTokenId] =
    val ids = Set.newBuilder[OriginalTokenId]
    transformRoot(json, (id, _) => { ids += id; Json.fromInt(id.intValue) },
      id => Some(Json.fromInt(id.intValue)))
    ids.result()

  def rewriteRoot(json: Json, projection: VocabularyProjection): Json =
    transformRoot(json, (id, _) => Json.fromInt(projection.requireCompact(id).intValue),
      id => projection.compact(id).map(compact => Json.fromInt(compact.intValue)))

  private def transformRoot(json: Json, required: (OriginalTokenId, String) => Json,
      optional: OriginalTokenId => Option[Json]): Json =
    val source = DomainJson.fields(json, "config")
    var output = source
    source.toVector.foreach { (name, value) =>
      val at = s"config.$name"
      def id(value: Json, where: String): OriginalTokenId = OriginalTokenId(DomainJson.integer(value, where))
      def requiredTree(value: Json, where: String): Json = value.asArray match
        case Some(values) =>
          DomainChecks.check(values.nonEmpty, "empty-required-ids", where, "required ID list cannot be empty")
          Json.fromValues(values.zipWithIndex.map((item, i) => requiredTree(item, s"$where[$i]")))
        case None => required(id(value, where), where)
      if !value.isNull then
        if scalarOrList(name) then
          val mapped = value.asArray match
            case Some(values) =>
              DomainChecks.check(values.nonEmpty, "empty-required-ids", at, "required ID list cannot be empty")
              Json.fromValues(values.map(item => required(id(item, at), at)))
            case None => required(id(value, at), at)
          output = output.add(name, mapped)
        else if name == "force_words_ids" then
          val groups = DomainJson.array(value, at)
          DomainChecks.check(groups.nonEmpty, "empty-required-ids", at, "forced word groups cannot be empty")
          groups.foreach { group =>
            val words = DomainJson.array(group, at)
            DomainChecks.check(words.nonEmpty, "empty-required-ids", at, "forced word cannot be empty")
            if words.head.isArray then words.foreach { word =>
              val phrase = DomainJson.array(word, at)
              DomainChecks.check(phrase.nonEmpty, "empty-required-ids", at, "alternative word cannot be empty")
              phrase.foreach(item => id(item, at))
            }
            else words.foreach(item => id(item, at))
          }
          output = output.add(name, requiredTree(value, at))
        else if optionalLists(name) then
          output = output.add(name, Json.fromValues(DomainJson.array(value, at).flatMap(item => optional(id(item, at)))))
        else if name == "bad_words_ids" then
          val words = DomainJson.array(value, at).flatMap { word =>
            val ids = DomainJson.array(word, at)
            DomainChecks.check(ids.nonEmpty, "empty-bad-word", at, "word must contain token IDs")
            val mapped = ids.map(item => optional(id(item, at)))
            if mapped.forall(_.isDefined) then Some(Json.fromValues(mapped.flatten)) else None
          }
          output = output.add(name, Json.fromValues(words))
        else if name == "forced_decoder_ids" then
          val pairs = DomainJson.array(value, at).map { pair =>
            val items = DomainJson.array(pair, at)
            DomainChecks.check(items.size == 2, "invalid-forced-decoder", at, "expected position / ID pair")
            DomainJson.integer(items(0), s"$at.position")
            Json.arr(items(0), if items(1).isNull then Json.Null else required(id(items(1), at), at))
          }
          output = output.add(name, Json.fromValues(pairs))
        else if name.endsWith("_token_id") || name.endsWith("_token_ids") || name == "sequence_bias" then
          DomainChecks.fail("unsupported-token-reference", at, "unrecognized token-reference schema")
    }
    Json.fromJsonObject(output)

  /** Collect padding / post-processor / decoder-key references before pruning. */
  def requiredTokenizerIds(tokenizer: Json, tokenizerConfig: Json): Set[OriginalTokenId] =
    val ids = Set.newBuilder[OriginalTokenId]
    transformTokenizer(tokenizer, tokenizerConfig, id => { ids += id; Json.fromInt(id.intValue) })
    ids.result() ++ requiredRootIds(tokenizerConfig)

  def rewriteTokenizerReferences(tokenizer: Json, config: Json, projection: VocabularyProjection): (Json, Json) =
    val (rewritten, decoderConfig) = transformTokenizer(tokenizer, config,
      id => Json.fromInt(projection.requireCompact(id).intValue))
    (rewritten, rewriteRoot(decoderConfig, projection))

  private def transformTokenizer(tokenizer: Json, config: Json, map: OriginalTokenId => Json): (Json, Json) =
    def mapId(value: Json, at: String): Json = map(OriginalTokenId(DomainJson.integer(value, at)))
    def processor(json: Json): Json =
      val fields = DomainJson.fields(json, "post_processor")
      DomainJson.text(DomainJson.required(json, "type", "post_processor"), "post_processor.type") match
        case "ByteLevel" => json
        case "Sequence" => Json.fromJsonObject(fields.add("processors",
          Json.fromValues(DomainJson.array(DomainJson.required(json, "processors", "post_processor"), "processors").map(processor))))
        case "TemplateProcessing" =>
          val tokens = DomainJson.fields(DomainJson.required(json, "special_tokens", "post_processor"), "special_tokens")
          val rewritten = tokens.toVector.map { (key, record) =>
            val fields = DomainJson.fields(record, s"special_tokens.$key")
            val ids = DomainJson.array(DomainJson.required(record, "ids", key), s"$key.ids")
            DomainChecks.check(ids.nonEmpty, "empty-required-ids", key, "post-processor special token IDs cannot be empty")
            key -> Json.fromJsonObject(fields.add("ids", Json.fromValues(ids.map(mapId(_, s"$key.ids")))))
          }
          Json.fromJsonObject(fields.add("special_tokens", Json.obj(rewritten*)))
        case "BertProcessing" | "RobertaProcessing" =>
          val rewritten = Vector("cls", "sep").foldLeft(fields) { (fields, key) =>
            val pair = DomainJson.array(DomainJson.required(json, key, "post_processor"), key)
            DomainChecks.check(pair.size == 2, "invalid-post-processor", key, "expected token string / ID pair")
            DomainJson.text(pair(0), key)
            fields.add(key, Json.arr(pair(0), mapId(pair(1), key)))
          }
          Json.fromJsonObject(rewritten)
        case other => DomainChecks.fail("unsupported-post-processor", "post_processor.type", other)
    var tokenFields = DomainJson.fields(tokenizer, "tokenizer")
    tokenFields("padding").filterNot(_.isNull).foreach { padding =>
      val fields = DomainJson.fields(padding, "padding")
      tokenFields = tokenFields.add("padding", Json.fromJsonObject(fields.add("pad_id",
        mapId(DomainJson.required(padding, "pad_id", "padding"), "padding.pad_id"))))
    }
    tokenFields("post_processor").filterNot(_.isNull).foreach(post => tokenFields = tokenFields.add("post_processor", processor(post)))
    var configFields = DomainJson.fields(config, "tokenizer_config")
    configFields("added_tokens_decoder").filterNot(_.isNull).foreach { decoder =>
      val rewritten = DomainJson.fields(decoder, "added_tokens_decoder").toVector.map { (key, value) =>
        DomainChecks.check(key.matches("0|[1-9][0-9]*"), "invalid-decoder-key", "added_tokens_decoder", key)
        val original = key.toIntOption.getOrElse(DomainChecks.fail("invalid-decoder-key", "added_tokens_decoder", key))
        val newKey = map(OriginalTokenId(original)).asNumber.get.toInt.get.toString
        val fields = DomainJson.fields(value, s"added_tokens_decoder.$key")
        val rewrittenValue = fields("id").map { id =>
          DomainChecks.check(DomainJson.integer(id, key) == original, "decoder-id-conflict", key, "key and id disagree")
          Json.fromJsonObject(fields.add("id", mapId(id, key)))
        }.getOrElse(value)
        newKey -> rewrittenValue
      }
      configFields = configFields.add("added_tokens_decoder", Json.obj(rewritten*))
    }
    (Json.fromJsonObject(tokenFields), Json.fromJsonObject(configFields))

  def configuredContents(config: Json): Set[String] =
    val fields = DomainJson.fields(config, "tokenizer_config")
    def content(value: Json, at: String): String = value.asString.getOrElse {
      DomainJson.text(DomainJson.required(value, "content", at), at)
    }
    val single = Vector("bos_token", "eos_token", "pad_token", "unk_token", "sep_token", "cls_token", "mask_token")
      .flatMap(name => fields(name).filterNot(_.isNull).map(content(_, name)))
    val lists = Vector("additional_special_tokens", "extra_special_tokens").flatMap { name =>
      fields(name).filterNot(_.isNull).toVector.flatMap(value => DomainJson.array(value, name).map(content(_, name)))
    }
    (single ++ lists).toSet
