package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference
import me.nsmr.vocabsurgery.tokenizer.*

class MetadataOperationsSuite extends munit.FunSuite:
  private val projection = VocabularyProjection.fromSurvivors(5, Vector(OriginalTokenId(0), OriginalTokenId(2), OriginalTokenId(4)))
  private def id(n: Int) = Json.fromInt(n)

  test("known required fields preserve EOS lists and forced decoder positions; other numbers stay unchanged") {
    val json = Json.obj("bos_token_id" -> id(4), "eos_token_id" -> Json.arr(id(2), id(4)),
      "pad_token_id" -> Json.Null, "forced_decoder_ids" -> Json.arr(Json.arr(id(4), id(2)), Json.arr(id(3), Json.Null)),
      "force_words_ids" -> Json.arr(Json.arr(id(0), id(4)), Json.arr(Json.arr(id(2)), Json.arr(id(4)))),
      "temperature" -> Json.fromDoubleOrNull(0.75), "num_attention_heads" -> id(4),
      "unknown" -> Json.obj("id" -> id(4)), "chat_template" -> Json.fromString("{{ 4 }}"))
    assertEquals(TokenReferences.requiredIds(json), Set(OriginalTokenId(0), OriginalTokenId(2), OriginalTokenId(4)))
    val out = TokenReferences.rewrite(json, projection)
    assertEquals(out.hcursor.downField("eos_token_id").focus, Some(Json.arr(id(1), id(2))))
    assertEquals(out.hcursor.downField("forced_decoder_ids").focus, Some(Json.arr(Json.arr(id(4), id(1)), Json.arr(id(3), Json.Null))))
    Vector("temperature", "num_attention_heads", "unknown", "chat_template", "pad_token_id").foreach { field =>
      assertEquals(out.hcursor.downField(field).focus, json.hcursor.downField(field).focus)
    }
  }

  test("suppression drops deleted IDs and bad words as whole sequences, without making them protected") {
    val json = Json.obj("suppress_tokens" -> Json.arr(id(0), id(1), id(4)),
      "begin_suppress_tokens" -> Json.arr(id(3)), "bad_words_ids" -> Json.arr(Json.arr(id(2), id(4)), Json.arr(id(0), id(1))))
    assertEquals(TokenReferences.requiredIds(json), Set.empty[OriginalTokenId])
    val rewritten = TokenReferences.rewrite(json, projection)
    assertEquals(rewritten.hcursor.downField("suppress_tokens").focus, Some(Json.arr(id(0), id(2))))
    assertEquals(rewritten.hcursor.downField("begin_suppress_tokens").focus, Some(Json.arr()))
    assertEquals(rewritten.hcursor.downField("bad_words_ids").focus, Some(Json.arr(Json.arr(id(1), id(2)))))
  }

  test("deleted / invalid required IDs, malformed schemas and unrecognized ID fields fail explicitly") {
    Vector(Json.obj("bos_token_id" -> id(1)), Json.obj("bos_token_id" -> id(5)),
      Json.obj("eos_token_id" -> Json.arr()), Json.obj("eos_token_id" -> id(-1)),
      Json.obj("bos_token_id" -> Json.fromDoubleOrNull(2.0)),
      Json.obj("eos_token_id" -> Json.arr(Json.arr(id(2)))),
      Json.obj("force_words_ids" -> id(2)),
      Json.obj("force_words_ids" -> Json.arr(Json.arr(Json.arr(Json.arr(id(2)))))),
      Json.obj("future_token_ids" -> Json.arr(id(2))), Json.obj("sequence_bias" -> Json.obj()),
      Json.obj("forced_decoder_ids" -> Json.arr(Json.arr(id(0)))),
      Json.obj("forced_decoder_ids" -> Json.arr(Json.arr(id(-1), id(2)))),
      Json.obj("bad_words_ids" -> Json.arr(Json.arr())),
      Json.obj("suppress_tokens" -> Json.arr(id(5))))
      .foreach(json => intercept[DomainException](TokenReferences.rewrite(json, projection)))
    assertEquals(TokenReferences.rewrite(Json.obj("future_token_id" -> Json.Null), projection), Json.obj("future_token_id" -> Json.Null))
  }

  test("scoped references reach nested objects, take family fields, and refuse token IDs anywhere else") {
    val json = Json.obj("image_token_id" -> id(4), "text_config" -> Json.obj("eos_token_id" -> id(2), "vocab_size" -> id(5)),
      "vision_config" -> Json.obj("depth" -> id(4)), "list" -> Json.arr(Json.obj("x" -> id(1))))
    val scopes = Vector(JsonPath.root, JsonPath("text_config"), JsonPath("absent"))
    assertEquals(TokenReferences.required(json, scopes, Set("image_token_id")).toSet,
      Set("$.image_token_id" -> OriginalTokenId(4), "text_config.eos_token_id" -> OriginalTokenId(2)))
    val out = TokenReferences.rewrite(json, projection, scopes, Set("image_token_id"))
    assertEquals(out.hcursor.get[Int]("image_token_id"), Right(2))
    assertEquals(out.hcursor.downField("text_config").get[Int]("eos_token_id"), Right(1))
    assertEquals(out.hcursor.downField("text_config").get[Int]("vocab_size"), Right(5))
    intercept[DomainException](TokenReferences.required(json, scopes))
    intercept[DomainException](TokenReferences.required(json, Vector(JsonPath.root), Set("image_token_id")))
    val nested = Json.obj("list" -> Json.arr(Json.obj("pad_token_id" -> id(0))))
    intercept[DomainException](TokenReferences.required(nested))
    assertEquals(TokenReferences.configuredContents(Json.obj("extra_special_tokens" -> Json.obj("image_token" -> Json.fromString("<i>")),
      "additional_special_tokens" -> Json.arr(Json.obj("content" -> Json.fromString("<a>"))))), Set("<i>", "<a>"))
  }

  test("padding, nested post-processors and added-token decoder keys all share the projection") {
    val special = Json.obj("id" -> Json.fromString("EOS"), "ids" -> Json.arr(id(4)), "tokens" -> Json.arr(Json.fromString("EOS")), "custom" -> id(4))
    val template = Json.obj("type" -> Json.fromString("TemplateProcessing"), "special_tokens" -> Json.obj("EOS" -> special), "single" -> Json.arr())
    val bert = Json.obj("type" -> Json.fromString("BertProcessing"), "cls" -> Json.arr(Json.fromString("CLS"), id(2)), "sep" -> Json.arr(Json.fromString("SEP"), id(4)))
    val tokenizer = Json.obj("padding" -> Json.obj("pad_id" -> id(4), "pad_token" -> Json.fromString("PAD"), "length" -> id(4)),
      "post_processor" -> Json.obj("type" -> Json.fromString("Sequence"), "processors" -> Json.arr(template, bert)))
    val entry = Json.obj("id" -> id(4), "content" -> Json.fromString("EOS"), "special" -> Json.False)
    val config = Json.obj("added_tokens_decoder" -> Json.obj("4" -> entry), "eos_token_id" -> id(4))
    assertEquals(TokenReferences.requiredTokenizerIds(tokenizer, config), Set(OriginalTokenId(2), OriginalTokenId(4)))
    val (out, cfg) = TokenReferences.rewriteTokenizerReferences(tokenizer, config, projection)
    assertEquals(out.hcursor.downField("padding").get[Int]("pad_id"), Right(2))
    assertEquals(out.hcursor.downField("padding").get[Int]("length"), Right(4))
    assertEquals(out.hcursor.downField("post_processor").downField("processors").downN(0).downField("special_tokens").downField("EOS").get[Vector[Int]]("ids"), Right(Vector(2)))
    assertEquals(cfg.hcursor.downField("added_tokens_decoder").downField("2").get[Int]("id"), Right(2))
    assertEquals(cfg.hcursor.get[Int]("eos_token_id"), Right(2))
    assertEquals(cfg.hcursor.downField("added_tokens_decoder").downField("2").get[Boolean]("special"), Right(false))
  }

  test("invalid decoder keys, key / ID conflicts and unsupported post-processors cannot leak stale IDs") {
    Vector("-1", "01", "2147483648").foreach { key =>
      intercept[DomainException](TokenReferences.requiredTokenizerIds(Json.obj(), Json.obj("added_tokens_decoder" -> Json.obj(key -> Json.obj()))))
    }
    intercept[DomainException](TokenReferences.requiredTokenizerIds(Json.obj(), Json.obj("added_tokens_decoder" -> Json.obj("4" -> Json.obj("id" -> id(2))))))
    intercept[DomainException](TokenReferences.requiredTokenizerIds(Json.obj("post_processor" -> Json.obj("type" -> Json.fromString("Unknown"))), Json.obj()))
    intercept[DomainException](TokenReferences.rewriteTokenizerReferences(Json.obj("padding" -> Json.obj("pad_id" -> id(1))), Json.obj(), projection))
  }

  test("rewrite retains original merge encoding and all unknown tokenizer / added-token attributes") {
    val source = TokenizerFixtures.create(Vector("ab", "abc", "bc"), Vector(BpeMerge("a", "b"), BpeMerge("ab", "c"), BpeMerge("b", "c"), BpeMerge("a", "bc")))
    val strings = Json.fromValues(source.merges.map(merge => Json.fromString(s"${merge.left} ${merge.right}")))
    val original = source.original.mapObject(_.add("future", Json.obj("large" -> Json.fromLong(9007199254740993L))))
      .mapObject(fields => fields.add("model", fields("model").get.mapObject(_.add("merges", strings))))
    val tokenizer = source.copy(original = original)
    val policy = PruningPolicy(JapaneseKanjiReference.fromCharacters(Set(CodePoint('日'.toInt))), explicitForbidden = Set(CodePoint('b'.toInt)), protectedContents = Set("ab"))
    val analysis = VocabularyOperations.analyze(tokenizer, ByteLevelBpe, TokenizerFixtures.rows(tokenizer) + 2, policy, Set.empty, CoderNext.requiredTokens)
    val result = TokenizerRewrite(tokenizer, analysis)
    assertEquals(result.hcursor.downField("future").focus, original.hcursor.downField("future").focus)
    Vector("normalizer", "pre_tokenizer").foreach(field => assertEquals(result.hcursor.downField(field).focus, original.hcursor.downField(field).focus))
    assertEquals(result.hcursor.downField("model").get[Vector[String]]("merges"), Right(analysis.cleanup.survivingEdges.map(e => s"${source.merges(e.rank).left} ${source.merges(e.rank).right}")))
    val resultAdded = result.hcursor.get[Vector[Json]]("added_tokens").toOption.get
    resultAdded.zip(source.addedTokens).foreach { (json, token) =>
      assertEquals(json.mapObject(_.remove("id")), token.original.mapObject(_.remove("id")))
      assertEquals(json.hcursor.get[Int]("id"), Right(analysis.projection.requireCompact(token.id).intValue))
    }
    intercept[DomainException](TokenizerRewrite(tokenizer.copy(merges = Vector.empty), analysis))
    val corruptVocab = original.mapObject(fields => fields.add("model", fields("model").get.mapObject(_.add("vocab", Json.obj()))))
    intercept[DomainException](TokenizerRewrite(tokenizer.copy(original = corruptVocab), analysis))
    val corruptMerge = original.mapObject(fields => fields.add("model", fields("model").get.mapObject(_.add("merges", Json.fromValues(source.merges.map(_ => Json.fromString("b a")))))))
    intercept[DomainException](TokenizerRewrite(tokenizer.copy(original = corruptMerge), analysis))
  }
