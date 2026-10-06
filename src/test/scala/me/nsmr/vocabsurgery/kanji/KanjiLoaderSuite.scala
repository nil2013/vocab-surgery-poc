package me.nsmr.vocabsurgery.kanji

import java.nio.file.{Files, Path}
import scala.util.Using
import me.nsmr.vocabsurgery.io.*

class KanjiLoaderSuite extends munit.FunSuite:
  private def fixture[A](name: String, text: String)(f: Path => A): A =
    val dir = Files.createTempDirectory("vocab-kanji-test-")
    val path = dir.resolve(name)
    try
      FileIO.writeNewUtf8(path, text)
      f(path)
    finally
      Files.deleteIfExists(path)
      Files.delete(dir)

  test("JIS characters are Unicode scalars and retain compatibility forms") {
    fixture("jis.csv", "漢字,JIS面区点番号,Unicode符号位置,JIS水準\n𠀋,1-14-02,U+2000B,3\n欄,1-89-10,U+F91D,3\n") { path =>
      val data = KanjiLoader.loadJis(path)
      assertEquals(data.records.map(_.character.intValue), Vector(0x2000b, 0xf91d))
      assertEquals(data.records.head.level, JisLevel.Third)
      assertEquals(data.sha256, FileIO.sha256(path))
    }
  }

  test("invalid JIS columns, Unicode mismatch and duplicate entries have path context") {
    fixture("bad.csv", "漢字,JIS面区点番号,Unicode符号位置,JIS水準\n字,1-16-01,U+4E00,1\n") { path =>
      val error = intercept[DataException](KanjiLoader.loadJis(path))
      assertEquals(error.source, path)
      assert(error.location.contains("Unicode符号位置"))
    }
    fixture("duplicate.csv", "掲載順,漢字\n1,字\n2,字\n") { path =>
      intercept[DataException](KanjiLoader.loadCommon(path))
    }
    fixture("grade.csv", "漢字,学年\n字,7\n") { path =>
      intercept[DataException](KanjiLoader.loadEducation(path))
    }
  }

  test("common table keeps optional variants and unknown JSON fields") {
    fixture("table.json", """[{"漢字":{"通用字体":"亜","康熙字典体":"亞"},"extra":[1]},{"漢字":{"通用字体":"字","康熙字典体":""}},{"漢字":{"通用字体":"弁","康熙字典体":"辨）（瓣）（辯"}}]""") { path =>
      val records = KanjiLoader.loadCommonTable(path).records
      assertEquals(records.head.kangxiForms.map(_.text), Vector("亞"))
      assertEquals(records(1).kangxiForms, Vector.empty)
      assertEquals(records(2).kangxiForms.map(_.text), Vector("辨", "瓣", "辯"))
      assert(records.head.original.hcursor.downField("extra").succeeded)
    }
  }

  test("kanken empty and multi-scalar texts are retained with explicit diagnostics") {
    val text = "漢字テキスト,漢字画像,漢検漢字辞典掲載ページ,字種ID,字項ID,字体,漢検級\n字,,1,T1,E1,親字,1級\n,image.png,2,T2,E2,旧字,配当外\n漢字,,3,T3,E3,親字,1級\n"
    fixture("kanken.csv", text) { path =>
      val (source, diagnostics) = KanjiLoader.loadKanken(path)
      assertEquals(source.records.size, 3)
      assertEquals(source.records.map(_.character.map(_.text)), Vector(Some("字"), None, None))
      assertEquals(source.records(1).image, Some("image.png"))
      assertEquals(diagnostics.map(_.recordNumber), Vector(2L, 3L))
    }
  }

  test("pinned kanji submodule loads all six sources and known record counts") {
    val data = KanjiLoader.load(Path.of("kanji-data"))
    assertEquals(data.jis.records.size, 10050)
    assertEquals(data.common.records.size, 2136)
    assertEquals(data.education.records.size, 1026)
    assertEquals(data.names.records.size, 3000)
    assertEquals(data.commonTable.records.size, 2136)
    assertEquals(data.commonTable.records.find(_.commonForm.text == "弁").get.kangxiForms.map(_.text),
      Vector("辨", "瓣", "辯"))
    assertEquals(data.kanken.records.size, 6787)
    assertEquals(data.kanken.records.count(_.character.isDefined), 6250)
    assertEquals(data.glyphDiagnostics.size, 537)
    assertEquals(data.jis.records.count(_.character.intValue > 0xffff), 303)
  }
