package me.nsmr.vocabsurgery.tokenizer

import scala.collection.mutable
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.model.TokenizerData

final case class MergeEdge(rank: Int, left: OriginalTokenId, right: OriginalTokenId, result: OriginalTokenId)
final case class MergeCleanup(
    survivingIds: Set[OriginalTokenId],
    survivingEdges: Vector[MergeEdge],
    directDeleted: Set[OriginalTokenId],
    indirectDeleted: Set[OriginalTokenId],
    reachableIds: Set[OriginalTokenId],
    unreachableProtected: Set[OriginalTokenId],
    removedRanks: Vector[Int]
)

/** Dependency DAG for byte-concatenating merges; alternate derivations are retained. */
final case class BpeGraph private (
    vocabulary: Map[String, OriginalTokenId],
    edges: Vector[MergeEdge],
    roots: Set[OriginalTokenId]
):
  val ids: Set[OriginalTokenId] = vocabulary.values.toSet

  def cleanup(directDeleted: Set[OriginalTokenId], protectedIds: Set[OriginalTokenId]): MergeCleanup =
    DomainChecks.check((directDeleted ++ protectedIds).subsetOf(ids), "unknown-token", "BPE cleanup", "ID not in lexical vocabulary")
    DomainChecks.check(directDeleted.intersect(protectedIds ++ roots).isEmpty,
      "protected-token-deletion", "BPE cleanup", "protected / base-byte token selected for deletion")
    val initial = ids -- directDeleted
    val valid = edges.filter(e => Set(e.left, e.right, e.result).subsetOf(initial))
    val reachable = reachability(valid)
    val indirect = initial -- reachable -- protectedIds -- roots
    val surviving = initial -- indirect
    val keptEdges = valid.filter(e => Set(e.left, e.right, e.result).subsetOf(surviving))
    val keptRanks = keptEdges.iterator.map(_.rank).toSet
    MergeCleanup(surviving, keptEdges, directDeleted, indirect, reachable,
      protectedIds.intersect(surviving) -- reachable, edges.filterNot(e => keptRanks(e.rank)).map(_.rank))

  private def reachability(active: Vector[MergeEdge]): Set[OriginalTokenId] =
    val dependencies = mutable.Map.empty[OriginalTokenId, mutable.ArrayBuffer[Int]]
    val pending = Array.ofDim[Int](active.size)
    active.zipWithIndex.foreach { (edge, index) =>
      val inputs = Set(edge.left, edge.right)
      pending(index) = inputs.size
      inputs.foreach(id => dependencies.getOrElseUpdate(id, mutable.ArrayBuffer.empty) += index)
    }
    val reached = mutable.Set.from(roots)
    val queue = mutable.Queue.from(roots.toVector.sortBy(_.intValue))
    while queue.nonEmpty do
      val id = queue.dequeue()
      dependencies.get(id).foreach(_.foreach { index =>
        pending(index) -= 1
        if pending(index) == 0 && reached.add(active(index).result) then queue.enqueue(active(index).result)
      })
    reached.toSet

object BpeGraph:
  def from(tokenizer: TokenizerData, scheme: TokenizerScheme): BpeGraph =
    val tokens = tokenizer.vocabulary
    DomainChecks.check(tokens.map(_.encoded).distinct.size == tokens.size && tokens.map(_.id).distinct.size == tokens.size,
      "duplicate-vocabulary", "BPE graph", "duplicate token keys or IDs")
    tokens.foreach(token => scheme.decode(token.encoded))
    val vocabulary = tokens.map(token => token.encoded -> token.id).toMap
    val roots = scheme.baseKeys.map { key =>
      vocabulary.getOrElse(key, DomainChecks.fail("missing-base-token", "BPE graph", key))
    }.toSet
    DomainChecks.check(tokenizer.merges.distinct.size == tokenizer.merges.size, "duplicate-merge", "BPE graph", "duplicate merge rules")
    val edges = tokenizer.merges.zipWithIndex.map { (merge, rank) =>
      DomainChecks.check(merge.left.nonEmpty && merge.right.nonEmpty, "empty-merge-input", s"merge[$rank]", "empty component")
      def id(key: String): OriginalTokenId = vocabulary.getOrElse(key,
        DomainChecks.fail("missing-merge-token", s"merge[$rank]", key))
      MergeEdge(rank, id(merge.left), id(merge.right), id(merge.left + merge.right))
    }
    BpeGraph(vocabulary, edges, roots)
