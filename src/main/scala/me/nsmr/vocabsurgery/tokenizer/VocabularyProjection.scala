package me.nsmr.vocabsurgery.tokenizer

import io.circe.Json
import me.nsmr.vocabsurgery.core.*

/** Source IDs of a list split into the ones kept, as compact IDs in ascending order, and the ones pruned. */
final case class IdTranslation(kept: Vector[CompactTokenId], dropped: Vector[OriginalTokenId])

/** A checked bijection on surviving rows. Unregistered and deleted rows map to None. */
final case class VocabularyProjection private (
    originalToCompact: Vector[Option[CompactTokenId]],
    compactToOriginal: Vector[OriginalTokenId]
):
  private val originalIndices = compactToOriginal.map(_.intValue)
  DomainChecks.check(originalIndices == originalIndices.sorted.distinct, "invalid-projection", "compactToOriginal",
    "source rows must be strictly increasing")
  DomainChecks.check(originalIndices.forall(i => i < originalToCompact.size), "invalid-projection", "compactToOriginal", "source row out of bounds")
  private val expected = Array.fill[Option[CompactTokenId]](originalToCompact.size)(None)
  compactToOriginal.zipWithIndex.foreach((id, i) => expected(id.intValue) = Some(CompactTokenId(i)))
  DomainChecks.check(originalToCompact == expected.toVector, "invalid-projection", "originalToCompact", "inverse mapping is inconsistent")

  def originalSize: Int = originalToCompact.size
  def compactSize: Int = compactToOriginal.size
  def compact(id: OriginalTokenId): Option[CompactTokenId] =
    DomainChecks.check(id.intValue < originalSize, "id-out-of-range", "original ID", id.intValue.toString)
    originalToCompact(id.intValue)
  def requireCompact(id: OriginalTokenId): CompactTokenId =
    compact(id).getOrElse(DomainChecks.fail("deleted-token-reference", "original ID", id.intValue.toString))
  def original(id: CompactTokenId): OriginalTokenId =
    DomainChecks.check(id.intValue < compactSize, "id-out-of-range", "compact ID", id.intValue.toString)
    compactToOriginal(id.intValue)
  /** Carry a list of source IDs, such as a draft vocabulary, into the compact ID space. Duplicates collapse. */
  def translate(ids: Iterable[OriginalTokenId]): IdTranslation =
    val distinct = ids.toVector.distinct.sortBy(_.intValue)
    val (kept, dropped) = distinct.partition(compact(_).isDefined)
    IdTranslation(kept.map(requireCompact), dropped)
  def originalToCompactJson: Json = Json.fromValues(originalToCompact.map(_.fold(Json.Null)(id => Json.fromInt(id.intValue))))
  def compactToOriginalJson: Json = Json.fromValues(compactToOriginal.map(id => Json.fromInt(id.intValue)))

object VocabularyProjection:
  def fromSurvivors(originalSize: Int, survivors: Iterable[OriginalTokenId]): VocabularyProjection =
    DomainChecks.check(originalSize > 0, "invalid-vocabulary-size", "projection", "original size must be positive")
    val input = survivors.toVector
    DomainChecks.check(input.nonEmpty, "empty-projection", "survivors", "at least one row must survive")
    DomainChecks.check(input.distinct.size == input.size, "duplicate-survivor", "survivors", "duplicate source IDs")
    DomainChecks.check(input.forall(_.intValue < originalSize), "id-out-of-range", "survivors", "source row exceeds vocabulary size")
    val ordered = input.sortBy(_.intValue)
    val inverse = Array.fill[Option[CompactTokenId]](originalSize)(None)
    ordered.zipWithIndex.foreach((id, index) => inverse(id.intValue) = Some(CompactTokenId(index)))
    VocabularyProjection(inverse.toVector, ordered)
