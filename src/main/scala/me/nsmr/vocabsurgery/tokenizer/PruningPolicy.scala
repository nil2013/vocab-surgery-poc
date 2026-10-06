package me.nsmr.vocabsurgery.tokenizer

import me.nsmr.vocabsurgery.core.*

/** What a policy sees of one registered token. `surface` is None for partial UTF-8 lexical keys. */
final case class TokenCandidate(id: OriginalTokenId, content: String, surface: Option[TokenSurface], decoded: Option[DecodedToken], added: Boolean)

/** One reason a policy gives for a token, reported per token. `character` names the scalar involved, if any. */
final case class PolicyReason(code: String, character: Option[CodePoint] = None)

/** A policy's verdict on one token. Keeping wins over deleting, and the conflict is reported. */
final case class PolicyDecision(delete: Vector[PolicyReason] = Vector.empty, keep: Vector[PolicyReason] = Vector.empty)

/** A pruning strategy over the whole registered vocabulary.
  * Structural protection (base tokens, partial UTF-8, added tokens, references from metadata and protocol) always wins
  * and is applied by the analysis, not by policies. Merge cleanup afterwards removes tokens the remaining merges cannot build.
  */
trait PruningPolicy:
  def name: String
  /** Verdicts for the tokens the policy has an opinion on; absent tokens are kept without reasons.
    * Implementations reject settings that name tokens absent from `tokens`.
    */
  def decide(tokens: Vector[TokenCandidate]): Map[OriginalTokenId, PolicyDecision]
  /** The policy's per-scalar verdict, for generated-text contamination metrics; None when it does not decide by characters. */
  def characterRule: Option[CharacterRule] = None
  /** Scalars whose NFC forms the decisions rely on; the reference check compares the tokenizer's NFC with ICU on them. */
  def normalizationSamples: Vector[CodePoint] = Vector.empty

/** A verdict on single Unicode scalars. */
trait CharacterRule:
  def forbidden(cp: CodePoint): Option[ForbiddenCharacter]
  def forbidden(surface: TokenSurface): Vector[ForbiddenCharacter] = surface.codePoints.distinct.flatMap(forbidden)
