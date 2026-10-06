package me.nsmr.vocabsurgery.kanji

import java.nio.file.Path
import org.scalacheck.{Gen, Prop}
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.unicode.*

class KanjiOperationsSuite extends munit.ScalaCheckSuite:
  test("reference combines all six sources, variants, NFC and provenance without changing source data") {
    val data = KanjiLoader.load(Path.of("kanji-data"))
    val reference = JapaneseKanjiReference.from(data)
    assertEquals(reference.raw.size, 10086)
    assertEquals(reference.characters.size, 10086)
    assert(reference.contains(CodePoint(0x2000b)))
    assert(reference.contains(CodePoint(0xf91d)))
    assert(reference.contains(CodePoint('欄'.toInt)))
    assertEquals(reference.missingTexts.size, 537)
    assertEquals(reference.sourceHashes.size, 6)
    assert(reference.origins(CodePoint('弁'.toInt)).exists(_.field == "通用字体"))
    assert(reference.origins(CodePoint('辨'.toInt)).exists(_.field == "康熙字典体"))
    assertEquals(data.jis.records.find(_.character.intValue == 0xf91d).get.character.text, "欄")
    assertEquals(reference.unicode.unicodeVersion, "17.0.0")
  }

  test("set operations and explicit additions do not expand into token-level protection") {
    val reference = JapaneseKanjiReference.fromCharacters(Set(CodePoint('日'.toInt)), Set(CodePoint('本'.toInt)))
    assertEquals(reference.overlap(CodePoint.sequence("日本汉")), Set(CodePoint('日'.toInt), CodePoint('本'.toInt)))
    assertEquals(reference.outside(CodePoint.sequence("日本汉")), Set(CodePoint('汉'.toInt)))
    assertEquals(reference.sortedCharacters.map(_.intValue), reference.characters.toVector.map(_.intValue).sorted)
  }

  test("Unicode Script and category distinguish Han letters from Japanese symbols") {
    assertEquals(UnicodeData.script(CodePoint('ع'.toInt)), UnicodeScript.Arabic)
    assertEquals(UnicodeData.script(CodePoint('Ж'.toInt)), UnicodeScript.Cyrillic)
    assertEquals(UnicodeData.script(CodePoint('한'.toInt)), UnicodeScript.Hangul)
    assert(UnicodeData.isHanKanji(CodePoint(0x2000b)))
    CodePoint.sequence("々〇〆⼀️").foreach(cp => assert(!UnicodeData.isHanKanji(cp)))
    assertEquals(UnicodeData.normalize(TokenSurface("e\u0301")).text, "é")
    intercept[DomainException](UnicodeScript.fromName("not-a-script"))
    intercept[DomainException](TokenSurface("\ud800"))
    intercept[DomainException](JapaneseKanjiReference.fromCharacters(Set.empty))
    val data = KanjiLoader.load(Path.of("kanji-data"))
    intercept[DomainException](JapaneseKanjiReference.from(data.copy(jis = data.jis.copy(records = Vector.empty))))
  }

  property("NFC reference closure retains originals and every normalized scalar") {
    val scalars = Gen.choose(0, 0x10ffff).suchThat(cp => cp < 0xd800 || cp > 0xdfff)
    Prop.forAll(Gen.nonEmptyListOf(scalars)) { values =>
      val raw = values.map(CodePoint.apply).toSet
      val reference = JapaneseKanjiReference.fromCharacters(raw)
      raw.subsetOf(reference.characters) && raw.flatMap(UnicodeData.normalizedCodePoints).subsetOf(reference.characters)
    }
  }
