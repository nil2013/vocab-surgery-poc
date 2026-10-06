package me.nsmr.vocabsurgery.kanji

import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*

/** Read the six agreed sources without normalizing or modifying their characters. */
object KanjiLoader:
  def load(directory: Path): KanjiData =
    val (kanken, diagnostics) = loadKanken(directory.resolve("漢検漢字辞典漢字.csv"))
    KanjiData(
      loadJis(directory.resolve("JIS漢字.csv")),
      loadCommon(directory.resolve("常用漢字.csv")),
      loadEducation(directory.resolve("教育漢字.csv")),
      loadNames(directory.resolve("子の名に使える漢字.csv")),
      loadCommonTable(directory.resolve("常用漢字表本表.json")),
      kanken,
      diagnostics
    )

  def loadJis(path: Path): KanjiSource[JisKanji] = Diagnostics.guard(path, "JIS CSV") {
    val table = CsvIO.read(path, Set("漢字", "JIS面区点番号", "Unicode符号位置", "JIS水準"))
    val positionPattern = "([12])-([0-9]{2})-([0-9]{2})".r
    val unicodePattern = "U\\+([0-9A-Fa-f]{4,6})".r
    val records = table.rows.map { row =>
      val at = s"record ${row.recordNumber}"
      val character = scalar(row.values("漢字"), path, s"$at.漢字")
      val position = row.values("JIS面区点番号") match
        case positionPattern(plane, ku, ten) => JisPosition(plane.toInt, ku.toInt, ten.toInt)
        case _ => Diagnostics.invalid(path, s"$at.JIS面区点番号", "expected plane-row-cell")
      val unicode = row.values("Unicode符号位置") match
        case unicodePattern(hex) => CodePoint(Integer.parseInt(hex, 16))
        case _ => Diagnostics.invalid(path, s"$at.Unicode符号位置", "expected U+XXXX")
      Diagnostics.check(character == unicode, path, s"$at.Unicode符号位置", "code point does not match character")
      val level = row.values("JIS水準") match
        case "1" => JisLevel.First
        case "2" => JisLevel.Second
        case "3" => JisLevel.Third
        case "4" => JisLevel.Fourth
        case other => Diagnostics.invalid(path, s"$at.JIS水準", s"unknown JIS level: $other")
      JisKanji(character, position, level)
    }
    unique(records.map(_.character), path, "漢字")
    unique(records.map(_.position), path, "JIS面区点番号")
    source(path, records)
  }

  def loadCommon(path: Path): KanjiSource[CommonKanji] = Diagnostics.guard(path, "common CSV") {
    val records = CsvIO.read(path, Set("掲載順", "漢字")).rows.map { row =>
      val at = s"record ${row.recordNumber}"
      CommonKanji(integer(row.values("掲載順"), 1, Int.MaxValue, path, s"$at.掲載順"),
        scalar(row.values("漢字"), path, s"$at.漢字"))
    }
    unique(records.map(_.order), path, "掲載順")
    unique(records.map(_.character), path, "漢字")
    source(path, records)
  }

  def loadEducation(path: Path): KanjiSource[EducationKanji] = Diagnostics.guard(path, "education CSV") {
    val records = CsvIO.read(path, Set("漢字", "学年")).rows.map { row =>
      val at = s"record ${row.recordNumber}"
      EducationKanji(scalar(row.values("漢字"), path, s"$at.漢字"),
        integer(row.values("学年"), 1, 6, path, s"$at.学年"))
    }
    unique(records.map(_.character), path, "漢字")
    source(path, records)
  }

  def loadNames(path: Path): KanjiSource[NameKanji] = Diagnostics.guard(path, "name CSV") {
    val records = CsvIO.read(path, Set("漢字", "区分")).rows.map { row =>
      val at = s"record ${row.recordNumber}"
      val category = row.values("区分") match
        case "常用漢字の通用字体" => NameKanjiCategory.CommonForm
        case "常用漢字外の字" => NameKanjiCategory.OutsideCommon
        case "常用漢字の異体字" => NameKanjiCategory.CommonVariant
        case other => Diagnostics.invalid(path, s"$at.区分", s"unknown category: $other")
      NameKanji(scalar(row.values("漢字"), path, s"$at.漢字"), category)
    }
    unique(records.map(_.character), path, "漢字")
    source(path, records)
  }

  def loadCommonTable(path: Path): KanjiSource[CommonKanjiTableEntry] = Diagnostics.guard(path, "common table JSON") {
    val records = JsonFields.array(JsonIO.read(path), path, "$").zipWithIndex.map { (record, index) =>
      val at = s"$$[$index]"
      val glyphs = JsonFields.field(record, "漢字", path, at)
      val common = scalar(JsonFields.text(JsonFields.field(glyphs, "通用字体", path, s"$at.漢字"),
        path, s"$at.漢字.通用字体"), path, s"$at.漢字.通用字体")
      val kangxi = JsonFields.obj(glyphs, path, s"$at.漢字")("康熙字典体").toVector.flatMap { value =>
        val raw = JsonFields.text(value, path, s"$at.漢字.康熙字典体")
        if raw.isEmpty then Vector.empty
        else raw.split("）（", -1).toVector.map(glyph => scalar(glyph, path, s"$at.漢字.康熙字典体"))
      }
      CommonKanjiTableEntry(common, kangxi, record)
    }
    unique(records.map(_.commonForm), path, "通用字体")
    source(path, records)
  }

  def loadKanken(path: Path): (KanjiSource[KankenKanji], Vector[GlyphDiagnostic]) =
    Diagnostics.guard(path, "kanken CSV") {
      val columns = Set("漢字テキスト", "漢字画像", "漢検漢字辞典掲載ページ", "字種ID", "字項ID", "字体", "漢検級")
      val table = CsvIO.read(path, columns)
      val diagnostics = Vector.newBuilder[GlyphDiagnostic]
      val records = table.rows.map { row =>
        val raw = row.values("漢字テキスト")
        val character = CodePoint.fromString(raw) match
          case Right(value) => Some(value)
          case Left(reason) =>
            diagnostics += GlyphDiagnostic(row.recordNumber, raw, if raw.isEmpty then "missing character text" else reason)
            None
        KankenKanji(character, row.values("漢検漢字辞典掲載ページ"), row.values("字種ID"), row.values("字項ID"),
          row.values("字体"), row.values("漢検級"), Option(row.values("漢字画像")).filter(_.nonEmpty), row.values)
      }
      (source(path, records), diagnostics.result())
    }

  private def source[A](path: Path, records: Vector[A]): KanjiSource[A] =
    KanjiSource(path, FileIO.sha256(path), records)

  private def scalar(text: String, path: Path, location: String): CodePoint =
    CodePoint.fromString(text).fold(reason => Diagnostics.invalid(path, location, reason), identity)

  private def integer(text: String, min: Int, max: Int, path: Path, location: String): Int =
    val value = text.toIntOption.getOrElse(Diagnostics.invalid(path, location, "expected integer"))
    Diagnostics.check(value >= min && value <= max, path, location, s"expected integer in $min..$max")
    value

  private def unique[A](values: Vector[A], path: Path, location: String): Unit =
    Diagnostics.check(values.distinct.size == values.size, path, location, "duplicate records")
