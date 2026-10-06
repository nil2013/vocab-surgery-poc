package me.nsmr.vocabsurgery.tokenizer

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.model.*

object TokenizerFixtures:
  def create(extra: Vector[String] = Vector.empty, merges: Vector[BpeMerge] = Vector.empty,
      addedContents: Vector[String] = ProtocolTokens.CoderNextRequired.toVector.sorted): TokenizerData =
    val keys = (ByteLevel.alphabet ++ extra).distinct
    val vocabulary = keys.zipWithIndex.map((key, index) => LexicalToken(key, OriginalTokenId(index)))
    val added = addedContents.zipWithIndex.map { (content, index) =>
      val id = vocabulary.find(_.encoded == content).map(_.id).getOrElse(OriginalTokenId(vocabulary.size + index))
      val json = Json.obj("id" -> Json.fromInt(id.intValue), "content" -> Json.fromString(content),
        "special" -> Json.False, "single_word" -> Json.False, "lstrip" -> Json.False,
        "rstrip" -> Json.False, "normalized" -> Json.False)
      AddedToken(id, content, false, false, false, false, false, json)
    }
    val model = Json.obj("type" -> Json.fromString("BPE"),
      "vocab" -> Json.obj(vocabulary.map(t => t.encoded -> Json.fromInt(t.id.intValue))*),
      "merges" -> Json.fromValues(merges.map(m => Json.arr(Json.fromString(m.left), Json.fromString(m.right)))),
      "unk_token" -> Json.Null, "continuing_subword_prefix" -> Json.fromString(""), "end_of_word_suffix" -> Json.fromString(""),
      "byte_fallback" -> Json.False, "ignore_merges" -> Json.False)
    val original = Json.obj("model" -> model, "added_tokens" -> Json.fromValues(added.map(_.original)),
      "normalizer" -> Json.obj("type" -> Json.fromString("NFC")),
      "pre_tokenizer" -> Json.obj("type" -> Json.fromString("ByteLevel")))
    TokenizerData(vocabulary, merges, added, original)

  def id(tokenizer: TokenizerData, key: String): OriginalTokenId =
    tokenizer.vocabulary.find(_.encoded == key).map(_.id)
      .orElse(tokenizer.addedTokens.find(_.content == key).map(_.id)).get

  def rows(tokenizer: TokenizerData): Int =
    (tokenizer.vocabulary.map(_.id.intValue) ++ tokenizer.addedTokens.map(_.id.intValue)).max + 1
