package me.nsmr.vocabsurgery.kanji

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.CodePoint

enum JisLevel(val number: Int):
  case First extends JisLevel(1)
  case Second extends JisLevel(2)
  case Third extends JisLevel(3)
  case Fourth extends JisLevel(4)

final case class JisPosition(plane: Int, row: Int, cell: Int):
  require(plane == 1 || plane == 2, "JIS plane must be 1 or 2")
  require(row >= 1 && row <= 94 && cell >= 1 && cell <= 94, "invalid JIS row / cell")

final case class JisKanji(character: CodePoint, position: JisPosition, level: JisLevel)
final case class CommonKanji(order: Int, character: CodePoint)
final case class EducationKanji(character: CodePoint, grade: Int)

enum NameKanjiCategory:
  case CommonForm, OutsideCommon, CommonVariant

final case class NameKanji(character: CodePoint, category: NameKanjiCategory)

/** Keep the full table record, including readings and notes, for later domain operations. */
final case class CommonKanjiTableEntry(commonForm: CodePoint, kangxiForms: Vector[CodePoint], original: Json)

/** Image-only / missing-text records remain records; they are not invented code points. */
final case class KankenKanji(
    character: Option[CodePoint],
    page: String,
    characterTypeId: String,
    entryId: String,
    glyphKind: String,
    grade: String,
    image: Option[String],
    original: Map[String, String]
)

final case class GlyphDiagnostic(recordNumber: Long, rawText: String, reason: String)

final case class KanjiSource[A](path: Path, sha256: String, records: Vector[A])

/** Loaded source records only. Set operations and normalization belong to stage two. */
final case class KanjiData(
    jis: KanjiSource[JisKanji],
    common: KanjiSource[CommonKanji],
    education: KanjiSource[EducationKanji],
    names: KanjiSource[NameKanji],
    commonTable: KanjiSource[CommonKanjiTableEntry],
    kanken: KanjiSource[KankenKanji],
    glyphDiagnostics: Vector[GlyphDiagnostic]
)
