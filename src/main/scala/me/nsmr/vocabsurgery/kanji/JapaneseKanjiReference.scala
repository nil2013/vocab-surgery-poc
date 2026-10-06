package me.nsmr.vocabsurgery.kanji

import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.unicode.{UnicodeData, UnicodeProvenance}

final case class KanjiOrigin(source: String, record: Int, field: String)

/** Original and NFC forms with per-character provenance; operations do not perform I/O. */
final case class JapaneseKanjiReference private (
    raw: Set[CodePoint],
    normalized: Set[CodePoint],
    additional: Set[CodePoint],
    origins: Map[CodePoint, Set[KanjiOrigin]],
    sourceHashes: Map[String, String],
    unicode: UnicodeProvenance,
    missingTexts: Vector[GlyphDiagnostic]
):
  val characters: Set[CodePoint] = raw ++ normalized ++ additional
  def contains(character: CodePoint): Boolean = characters.contains(character)
  def outside(characters: Iterable[CodePoint]): Set[CodePoint] = characters.toSet -- this.characters
  def overlap(characters: Iterable[CodePoint]): Set[CodePoint] = characters.toSet.intersect(this.characters)
  def sortedCharacters: Vector[CodePoint] = characters.toVector.sortBy(_.intValue)

object JapaneseKanjiReference:
  def from(data: KanjiData, additional: Set[CodePoint] = Set.empty): JapaneseKanjiReference =
    DomainChecks.check(data.jis.records.nonEmpty, "empty-kanji-reference", "JIS", "Japanese reference cannot omit all JIS records")
    val entries = Vector.newBuilder[(CodePoint, KanjiOrigin)]
    def add[A](source: KanjiSource[A], field: String)(extract: A => Vector[CodePoint]): Unit =
      source.records.zipWithIndex.foreach { (record, index) =>
        extract(record).foreach(cp => entries += cp -> KanjiOrigin(source.path.getFileName.toString, index + 1, field))
      }
    add(data.jis, "漢字")(record => Vector(record.character))
    add(data.common, "漢字")(record => Vector(record.character))
    add(data.education, "漢字")(record => Vector(record.character))
    add(data.names, "漢字")(record => Vector(record.character))
    add(data.commonTable, "通用字体")(record => Vector(record.commonForm))
    add(data.commonTable, "康熙字典体")(_.kangxiForms)
    add(data.kanken, "漢字テキスト")(_.character.toVector)
    val values = entries.result()
    val raw = values.map(_._1).toSet
    val normalized = raw.flatMap(UnicodeData.normalizedCodePoints)
    val origins = values.flatMap { (cp, origin) =>
      (UnicodeData.normalizedCodePoints(cp) + cp).toVector.map(_ -> origin)
    }.groupMap(_._1)(_._2).view.mapValues(_.toSet).toMap
    val hashes = Vector(data.jis.path -> data.jis.sha256, data.common.path -> data.common.sha256,
      data.education.path -> data.education.sha256, data.names.path -> data.names.sha256,
      data.commonTable.path -> data.commonTable.sha256, data.kanken.path -> data.kanken.sha256)
      .map((path, hash) => path.toString -> hash).toMap
    JapaneseKanjiReference(raw, normalized, additional, origins, hashes, UnicodeData.provenance, data.glyphDiagnostics)

  /** Explicit small references are useful for controlled experiments and synthetic tests. */
  def fromCharacters(characters: Set[CodePoint], additional: Set[CodePoint] = Set.empty): JapaneseKanjiReference =
    DomainChecks.check(characters.nonEmpty, "empty-kanji-reference", "characters", "Japanese reference must not be empty")
    JapaneseKanjiReference(characters, characters.flatMap(UnicodeData.normalizedCodePoints), additional,
      Map.empty, Map.empty, UnicodeData.provenance, Vector.empty)
