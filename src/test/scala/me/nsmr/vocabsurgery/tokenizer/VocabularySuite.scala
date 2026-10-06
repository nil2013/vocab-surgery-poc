package me.nsmr.vocabsurgery.tokenizer

import io.circe.Json
import org.scalacheck.{Gen, Prop}
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference
import me.nsmr.vocabsurgery.model.*

class VocabularySuite extends munit.ScalaCheckSuite:
  private val japanese = JapaneseKanjiReference.fromCharacters(CodePoint.sequence("日本").toSet)
  private val policy = PruningPolicy(japanese)

  test("ByteLevel restores all 256 bytes exactly and never replaces malformed UTF-8") {
    val bytes = (0 to 255).map(_.toByte).toVector
    assertEquals(ByteLevel.decode(ByteLevel.encode(bytes)).bytes, bytes)
    assertEquals(ByteLevel.decode(ByteLevel.encode(Vector(0xff.toByte))).surface, None)
    assertEquals(ByteLevel.decode(ByteLevel.encode(TokenSurface("日本 🐈 e\u0301"))).surface.map(_.text), Some("日本 🐈 e\u0301"))
    intercept[DomainException](ByteLevel.decode(""))
    intercept[DomainException](ByteLevel.decode("日"))
    intercept[DomainException](ByteLevel.decode("\ud800"))
  }

  test("predicate removes foreign scripts and outside Han, while protecting Japanese and shared symbols") {
    val forbidden = policy.forbidden(TokenSurface("日本汉عЖ한々〇〆⼀️"))
    assertEquals(forbidden.map(_.character.text).toSet, Set("汉", "ع", "Ж", "한"))
    assert(policy.forbidden(TokenSurface("日ع")).nonEmpty)
    val explicit = policy.copy(explicitForbidden = Set(CodePoint('日'.toInt)))
    assertEquals(explicit.forbidden(TokenSurface("日")).head.reasons, Set(ForbiddenReason.Explicit))
  }

  test("base bytes, partial UTF-8, added and explicitly protected tokens survive forbidden conflicts") {
    val arabic = ByteLevel.encode(TokenSurface("ع"))
    val partial = ByteLevel.encode(Vector(0xff.toByte, 0xff.toByte))
    val extra = Vector(arabic, partial, "ab")
    val merges = Vector(BpeMerge(arabic.take(1), arabic.drop(1)), BpeMerge(partial.take(1), partial.drop(1)), BpeMerge("a", "b"))
    val tokenizer = TokenizerFixtures.create(extra, merges, ProtocolTokens.CoderNextRequired.toVector.sorted :+ "ع_tool")
    val protectedPolicy = policy.copy(protectedContents = Set(arabic))
    val analysis = VocabularyOperations.analyze(tokenizer, TokenizerFixtures.rows(tokenizer) + 3, protectedPolicy)
    assert(analysis.cleanup.directDeleted.isEmpty)
    assertEquals(analysis.unregisteredIds.size, 3)
    assert(analysis.protectedConflicts.exists(_.content == arabic))
    assert(analysis.protectedConflicts.exists(_.content == "ع_tool"))
    assert(analysis.tokens.find(_.content == partial).get.protection(ProtectionReason.PartialUtf8))
    assertEquals(analysis.tokens.count(_.protection(ProtectionReason.BaseByte)), 256)
    assert(analysis.tokens.find(_.content == "<tool_call>").get.protection(ProtectionReason.AddedToken))
    val pruned = VocabularyOperations.analyze(tokenizer, TokenizerFixtures.rows(tokenizer), policy)
    assertEquals(pruned.cleanup.directDeleted, Set(TokenizerFixtures.id(tokenizer, arabic)))
    assert(pruned.projection.compact(TokenizerFixtures.id(tokenizer, "<tool_call>")).isDefined)
    val literalSurface = VocabularyOperations.analyze(tokenizer, TokenizerFixtures.rows(tokenizer), policy.copy(protectedContents = Set("ع")))
    assert(literalSurface.projection.compact(TokenizerFixtures.id(tokenizer, arabic)).isDefined)
    assert(literalSurface.tokens.find(_.content == arabic).get.protection(ProtectionReason.Explicit))
  }

  test("analysis diagnoses missing protocol, byte alphabet, unknown protection and inconsistent token identities") {
    val tokenizer = TokenizerFixtures.create()
    val size = TokenizerFixtures.rows(tokenizer)
    intercept[DomainException](VocabularyOperations.analyze(tokenizer.copy(addedTokens = Vector.empty), size, policy))
    intercept[DomainException](VocabularyOperations.analyze(tokenizer.copy(vocabulary = tokenizer.vocabulary.tail), size, policy))
    intercept[DomainException](VocabularyOperations.analyze(tokenizer, size, policy.copy(protectedIds = Set(OriginalTokenId(size + 1)))))
    intercept[DomainException](VocabularyOperations.analyze(tokenizer, size - 1, policy))
    val conflict = tokenizer.addedTokens.head.copy(id = OriginalTokenId(0))
    intercept[DomainException](VocabularyOperations.analyze(tokenizer.copy(addedTokens = Vector(conflict)), size, policy))
    intercept[DomainException](VocabularyOperations.analyze(tokenizer.copy(addedTokens = tokenizer.addedTokens :+ tokenizer.addedTokens.head), size, policy))
  }

  test("projection is deterministic, bidirectional and includes nulls for unused rows") {
    val projection = VocabularyProjection.fromSurvivors(6, Vector(OriginalTokenId(5), OriginalTokenId(1)))
    assertEquals(projection.compactToOriginal.map(_.intValue), Vector(1, 5))
    assertEquals(projection.originalToCompactJson, Json.arr(Json.Null, Json.fromInt(0), Json.Null, Json.Null, Json.Null, Json.fromInt(1)))
    assertEquals(projection.original(CompactTokenId(1)), OriginalTokenId(5))
    intercept[DomainException](projection.compact(OriginalTokenId(6)))
    intercept[DomainException](projection.original(CompactTokenId(2)))
    intercept[DomainException](projection.requireCompact(OriginalTokenId(0)))
    intercept[DomainException](VocabularyProjection.fromSurvivors(0, Vector(OriginalTokenId(0))))
    intercept[DomainException](VocabularyProjection.fromSurvivors(2, Vector.empty))
    intercept[DomainException](VocabularyProjection.fromSurvivors(2, Vector(OriginalTokenId(0), OriginalTokenId(0))))
    intercept[DomainException](VocabularyProjection.fromSurvivors(2, Vector(OriginalTokenId(2))))
    intercept[IllegalArgumentException](CompactTokenId(-1))
  }

  property("ByteLevel is a bijection for arbitrary nonempty byte sequences") {
    Prop.forAll(Gen.nonEmptyListOf(Gen.choose(0, 255))) { values =>
      val bytes = values.map(_.toByte).toVector
      ByteLevel.decode(ByteLevel.encode(bytes)).bytes == bytes
    }
  }

  property("projection round trips each survivor and never aliases deleted IDs") {
    Prop.forAll(Gen.choose(1, 300).flatMap(size => Gen.nonEmptyListOf(Gen.choose(0, size - 1)).map(ids => (size, ids.distinct)))) { (size, indices) =>
      val ids = indices.map(OriginalTokenId.apply)
      val projection = VocabularyProjection.fromSurvivors(size, ids)
      ids.forall(id => projection.original(projection.requireCompact(id)) == id) &&
        projection.compactToOriginal.map(_.intValue) == indices.sorted.toVector &&
        (0 until size).forall(i => projection.compact(OriginalTokenId(i)).isDefined == indices.contains(i))
    }
  }
