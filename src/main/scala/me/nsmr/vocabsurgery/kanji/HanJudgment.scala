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

enum HanForm(val name: String):
  case Simplified extends HanForm("simplified")
  case Traditional extends HanForm("traditional")

/** How much JMdict use keeps a character: any headword, a standard (not irregular / outdated / rare / search-only) one,
  * a common (priority) one, or none at all.
  */
enum JMdictProtection(val name: String):
  case AnyHeadword extends JMdictProtection("any")
  case StandardHeadword extends JMdictProtection("standard")
  case PriorityHeadword extends JMdictProtection("priority")
  case NoProtection extends JMdictProtection("none")
  def keeps(usage: me.nsmr.vocabsurgery.lexicon.CharacterUsage): Boolean = this match
    case AnyHeadword => usage.headwords > 0
    case StandardHeadword => usage.standard > 0
    case PriorityHeadword => usage.priority > 0
    case NoProtection => false

enum HanFormDecision(val name: String):
  case Pruned extends HanFormDecision("pruned")
  case KeptDaily extends HanFormDecision("kept-daily-japanese")
  case KeptByJMdict extends HanFormDecision("kept-jmdict")
  case KeptManually extends HanFormDecision("kept-manually")

/** Select simplified / traditional Han forms to forbid from their judgments. 常用 and 子の名に使える漢字 are always kept;
  * then `keep` (a manual list) and JMdict use. Characters of neither selected form get no decision.
  */
final case class HanFormRule(prune: Set[HanForm], keepInJMdict: JMdictProtection = JMdictProtection.AnyHeadword, keep: Set[CodePoint] = Set.empty):
  def forms(judgment: HanJudgment): Set[HanForm] =
    Set(HanForm.Simplified).filter(_ => judgment.simplifiedForm) ++ Set(HanForm.Traditional).filter(_ => judgment.traditionalForm)

  def decide(judgment: HanJudgment): Option[HanFormDecision] =
    Option.when(judgment.hanKanji && forms(judgment).exists(prune)) {
      if judgment.dailyJapanese then HanFormDecision.KeptDaily
      else if keep(judgment.character) then HanFormDecision.KeptManually
      else if keepInJMdict.keeps(judgment.lexicon) then HanFormDecision.KeptByJMdict
      else HanFormDecision.Pruned
    }
