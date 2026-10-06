package me.nsmr.vocabsurgery.tokenizer

import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference
import me.nsmr.vocabsurgery.unicode.*

enum ForbiddenReason:
  case Script(script: UnicodeScript)
  case HanOutsideJapaneseReference, Explicit
  def code: String = this match
    case Script(script) => s"script_${script.name.toLowerCase(java.util.Locale.ROOT)}"
    case HanOutsideJapaneseReference => "han_outside_japanese_reference"
    case Explicit => "explicit"

final case class ForbiddenCharacter(character: CodePoint, reasons: Set[ForbiddenReason])

/** Delete every token whose surface contains a forbidden scalar: one of `forbiddenScripts`, Han kanji outside the
  * Japanese reference, or an explicit code point. Math scalars are never forbidden, and tokens made only of them are kept.
  * `protectedIds` and `protectedContents` (encoded key, added-token text or decoded surface) are kept as well.
  */
final case class CharacterPolicy(
    japanese: JapaneseKanjiReference,
    forbiddenScripts: Set[UnicodeScript] = Set(UnicodeScript.Arabic, UnicodeScript.Cyrillic, UnicodeScript.Hangul),
    pruneHanOutsideReference: Boolean = true,
    explicitForbidden: Set[CodePoint] = Set.empty,
    protectedIds: Set[OriginalTokenId] = Set.empty,
    protectedContents: Set[String] = Set.empty,
    protectMath: Boolean = true
) extends PruningPolicy with CharacterRule:
  val name = "characters"
  override def characterRule: Option[CharacterRule] = Some(this)
  override def normalizationSamples: Vector[CodePoint] = japanese.sortedCharacters

  def forbidden(cp: CodePoint): Option[ForbiddenCharacter] =
    if protectMath && UnicodeData.isMath(cp) then return None
    val script = UnicodeData.script(cp)
    val reasons = Set.newBuilder[ForbiddenReason]
    if forbiddenScripts(script) then reasons += ForbiddenReason.Script(script)
    if pruneHanOutsideReference && UnicodeData.isHanKanji(cp) && !japanese.contains(cp) then reasons += ForbiddenReason.HanOutsideJapaneseReference
    if explicitForbidden(cp) then reasons += ForbiddenReason.Explicit
    val result = reasons.result()
    if result.isEmpty then None else Some(ForbiddenCharacter(cp, result))

  def decide(tokens: Vector[TokenCandidate]): Map[OriginalTokenId, PolicyDecision] =
    val ids = tokens.map(_.id).toSet
    DomainChecks.check(protectedIds.subsetOf(ids), "unknown-protected-id", "policy", "protected ID is unregistered")
    val byContent = tokens.groupMap(_.content)(_.id)
    val bySurface = tokens.filterNot(_.added).flatMap(t => t.surface.map(_.text -> t.id)).groupMap(_._1)(_._2)
    val explicit = protectedContents.flatMap { content =>
      val matched = byContent.getOrElse(content, Vector.empty) ++ bySurface.getOrElse(content, Vector.empty)
      DomainChecks.check(matched.nonEmpty, "missing-protected-token", "policy", content)
      matched
    } ++ protectedIds
    tokens.iterator.flatMap { token =>
      val delete = token.surface.toVector.flatMap(forbidden).flatMap(f => f.reasons.toVector.map(_.code).sorted.map(PolicyReason(_, Some(f.character))))
      val keep = Vector(Option.when(explicit(token.id))(PolicyReason("explicit")),
        Option.when(protectMath && token.surface.exists(_.codePoints.forall(UnicodeData.isMath)))(PolicyReason("math"))).flatten
      Option.when(delete.nonEmpty || keep.nonEmpty)(token.id -> PolicyDecision(delete, keep))
    }.toMap
