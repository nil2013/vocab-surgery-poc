package me.nsmr.vocabsurgery.tokenizer

import org.scalacheck.{Gen, Prop}
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.model.*

class BpeGraphSuite extends munit.ScalaCheckSuite:
  test("alternate derivations survive pruning and original ranks remain ordered") {
    val tokenizer = TokenizerFixtures.create(Vector("ab", "bc", "abc"),
      Vector(BpeMerge("a", "b"), BpeMerge("b", "c"), BpeMerge("ab", "c"), BpeMerge("a", "bc")))
    val graph = BpeGraph.from(tokenizer, ByteLevelBpe)
    val ab = TokenizerFixtures.id(tokenizer, "ab")
    val abc = TokenizerFixtures.id(tokenizer, "abc")
    val result = graph.cleanup(Set(ab), Set.empty)
    assert(result.survivingIds(abc))
    assertEquals(result.indirectDeleted, Set.empty[OriginalTokenId])
    assertEquals(result.survivingEdges.map(_.rank), Vector(1, 3))
    assertEquals(result.removedRanks, Vector(0, 2))
  }

  test("transitive orphan cleanup and unreachable protected tokens are separate outcomes") {
    val tokenizer = TokenizerFixtures.create(Vector("ab", "abc", "abcd", "orphan"),
      Vector(BpeMerge("a", "b"), BpeMerge("ab", "c"), BpeMerge("abc", "d")))
    val graph = BpeGraph.from(tokenizer, ByteLevelBpe)
    val ab = TokenizerFixtures.id(tokenizer, "ab")
    val abc = TokenizerFixtures.id(tokenizer, "abc")
    val abcd = TokenizerFixtures.id(tokenizer, "abcd")
    val orphan = TokenizerFixtures.id(tokenizer, "orphan")
    val pruned = graph.cleanup(Set(ab), Set.empty)
    assertEquals(pruned.indirectDeleted, Set(abc, abcd, orphan))
    val protectedResult = graph.cleanup(Set(ab), Set(abc))
    assertEquals(protectedResult.unreachableProtected, Set(abc))
    assert(protectedResult.survivingIds(abc))
    assert(!protectedResult.survivingIds(abcd))
  }

  test("repeated merge inputs use a single dependency, and missing graph tokens fail") {
    val tokenizer = TokenizerFixtures.create(Vector("aa"), Vector(BpeMerge("a", "a")))
    val graph = BpeGraph.from(tokenizer, ByteLevelBpe)
    assert(graph.cleanup(Set.empty, Set.empty).reachableIds(TokenizerFixtures.id(tokenizer, "aa")))
    intercept[DomainException](graph.cleanup(Set(TokenizerFixtures.id(tokenizer, "a")), Set.empty))
    intercept[DomainException](graph.cleanup(Set(OriginalTokenId(9999)), Set.empty))
    intercept[DomainException](graph.cleanup(Set(TokenizerFixtures.id(tokenizer, "aa")), Set(TokenizerFixtures.id(tokenizer, "aa"))))
    intercept[DomainException](BpeGraph.from(tokenizer.copy(merges = Vector(BpeMerge("a", "missing"))), ByteLevelBpe))
    intercept[DomainException](BpeGraph.from(tokenizer.copy(merges = tokenizer.merges ++ tokenizer.merges), ByteLevelBpe))
    intercept[DomainException](BpeGraph.from(tokenizer.copy(vocabulary = tokenizer.vocabulary ++ tokenizer.vocabulary), ByteLevelBpe))
  }

  property("cleanup is a fixed point and every retained edge references retained tokens") {
    val words = Vector("ab", "abc", "abcd", "abcde", "abcdef")
    val merges = Vector(BpeMerge("a", "b")) ++ words.sliding(2).map(pair => BpeMerge(pair(0), pair(1).takeRight(1))).toVector
    val tokenizer = TokenizerFixtures.create(words, merges)
    val graph = BpeGraph.from(tokenizer, ByteLevelBpe)
    Prop.forAll(Gen.someOf(words)) { removed =>
      val result = graph.cleanup(removed.map(TokenizerFixtures.id(tokenizer, _)).toSet, Set.empty)
      val rewritten = tokenizer.copy(vocabulary = tokenizer.vocabulary.filter(t => result.survivingIds(t.id)),
        merges = result.survivingEdges.map(e => tokenizer.merges(e.rank)))
      val again = BpeGraph.from(rewritten, ByteLevelBpe).cleanup(Set.empty, Set.empty)
      again.indirectDeleted.isEmpty && again.survivingIds == result.survivingIds &&
        result.survivingEdges.forall(e => Set(e.left, e.right, e.result).subsetOf(result.survivingIds))
    }
  }
