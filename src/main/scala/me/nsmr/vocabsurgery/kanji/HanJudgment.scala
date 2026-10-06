package me.nsmr.vocabsurgery.kanji

import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.lexicon.{CharacterUsage, JapaneseLexicon}
import me.nsmr.vocabsurgery.unicode.{UnicodeData, UnihanVariants}

/** Static facts about one character from the Japanese kanji lists, Unihan and a Japanese lexicon. It takes no decision;
  * policies and reviewers decide from these facts.
  */
final case class HanJudgment(
    character: CodePoint,
    hanKanji: Boolean,
    referenceSources: Set[String],
    joyo: Boolean,
    nameKanji: Option[NameKanjiCategory],
    simplifiedVariants: Set[CodePoint],
    traditionalVariants: Set[CodePoint],
    lexicon: CharacterUsage
):
  /** Unihan gives it a traditional variant other than itself. */
  def simplifiedForm: Boolean = traditionalVariants.nonEmpty
  /** Unihan gives it a simplified variant other than itself. */
  def traditionalForm: Boolean = simplifiedVariants.nonEmpty
  def inJapaneseReference: Boolean = referenceSources.nonEmpty
  /** 常用漢字 or 子の名に使える漢字 (人名用 and the 常用 variants). */
  def dailyJapanese: Boolean = joyo || nameKanji.isDefined

/** The references a judgment draws on, each identified by its own version. */
final case class HanReferences(kanji: KanjiData, reference: JapaneseKanjiReference, unihan: UnihanVariants, lexicon: JapaneseLexicon):
  private lazy val joyo = kanji.common.records.map(_.character).toSet ++ kanji.commonTable.records.map(_.commonForm)
  private lazy val names = kanji.names.records.map(record => record.character -> record.category).toMap

  def judge(cp: CodePoint): HanJudgment =
    HanJudgment(cp, UnicodeData.isHanKanji(cp), reference.origins.getOrElse(cp, Set.empty).map(_.source),
      joyo(cp), names.get(cp), unihan.simplified.getOrElse(cp, Set.empty) - cp, unihan.traditional.getOrElse(cp, Set.empty) - cp,
      lexicon.usage(cp))
