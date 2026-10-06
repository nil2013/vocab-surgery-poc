package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.tokenizer.*

object TokenizerRewrite:
  def apply(source: TokenizerData, analysis: VocabularyAnalysis): Json =
    val projection = analysis.projection
    val keptVocabulary = source.vocabulary.filter(token => projection.compact(token.id).isDefined)
    DomainChecks.check(keptVocabulary.map(_.id).toSet == analysis.cleanup.survivingIds,
      "inconsistent-cleanup", "tokenizer rewrite", "projection and lexical cleanup disagree")
    source.addedTokens.foreach(token => projection.requireCompact(token.id))
    val registered = (source.vocabulary.map(_.id) ++ source.addedTokens.map(_.id)).toSet
    DomainChecks.check(projection.compactToOriginal.forall(registered), "unregistered-survivor", "tokenizer rewrite", "projection retains unregistered rows")
    val root = DomainJson.fields(source.original, "tokenizer")
    val model = DomainJson.fields(DomainJson.required(source.original, "model", "tokenizer"), "model")
    val originalVocab = DomainJson.fields(model("vocab").getOrElse(DomainChecks.fail("missing-json-field", "vocab", "missing vocabulary")), "vocab")
    DomainChecks.check(originalVocab.toVector.map((key, value) => key -> OriginalTokenId(DomainJson.integer(value, s"vocab.$key"))).toMap ==
      source.vocabulary.map(token => token.encoded -> token.id).toMap, "inconsistent-tokenizer", "vocab", "typed vocabulary and JSON disagree")
    val originalAdded = DomainJson.array(DomainJson.required(source.original, "added_tokens", "tokenizer"), "added_tokens")
    DomainChecks.check(originalAdded == source.addedTokens.map(_.original), "inconsistent-tokenizer", "added_tokens", "typed records and JSON disagree")
    source.addedTokens.foreach { token =>
      DomainChecks.check(token.original.hcursor.get[Int]("id").contains(token.id.intValue) &&
        token.original.hcursor.get[String]("content").contains(token.content), "inconsistent-tokenizer", "added token", "identity and original JSON disagree")
    }
    val originalMerges = DomainJson.array(model("merges").getOrElse(DomainChecks.fail("missing-json-field", "merges", "missing merges")), "merges")
    DomainChecks.check(originalMerges.size == source.merges.size, "inconsistent-tokenizer", "merges", "typed merges and JSON disagree")
    originalMerges.zip(source.merges).foreach { (json, merge) =>
      val pair = json.asArray.map(_.map(DomainJson.text(_, "merge")))
        .getOrElse(DomainJson.text(json, "merge").split(" ", -1).toVector)
      DomainChecks.check(pair == Vector(merge.left, merge.right), "inconsistent-tokenizer", "merges", "typed merge and original JSON disagree")
    }
    val merges = analysis.cleanup.survivingEdges.map { edge =>
      DomainChecks.check(edge.rank >= 0 && edge.rank < source.merges.size, "invalid-merge-rank", "rewrite", edge.rank.toString)
      originalMerges(edge.rank)
    }
    val vocabJson = Json.obj(keptVocabulary.sortBy(_.id.intValue).map(token =>
      token.encoded -> Json.fromInt(projection.requireCompact(token.id).intValue))* )
    val addedJson = Json.fromValues(source.addedTokens.map(token =>
      Json.fromJsonObject(DomainJson.fields(token.original, "added token").add("id", Json.fromInt(projection.requireCompact(token.id).intValue)))))
    Json.fromJsonObject(root.add("model", Json.fromJsonObject(model.add("vocab", vocabJson).add("merges", Json.fromValues(merges))))
      .add("added_tokens", addedJson))
