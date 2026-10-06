package me.nsmr.vocabsurgery.tokenizer

import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference
import me.nsmr.vocabsurgery.model.TokenizerData
import me.nsmr.vocabsurgery.unicode.*

enum ForbiddenReason:
  case Script(script: UnicodeScript)
  case HanOutsideJapaneseReference, Explicit
  def code: String = this match
    case Script(script) => s"script_${script.name.toLowerCase(java.util.Locale.ROOT)}"
    case HanOutsideJapaneseReference => "han_outside_japanese_reference"
    case Explicit => "explicit"

final case class ForbiddenCharacter(character: CodePoint, reasons: Set[ForbiddenReason])

final case class PruningPolicy(
    japanese: JapaneseKanjiReference,
    forbiddenScripts: Set[UnicodeScript] = Set(UnicodeScript.Arabic, UnicodeScript.Cyrillic, UnicodeScript.Hangul),
    pruneHanOutsideReference: Boolean = true,
    explicitForbidden: Set[CodePoint] = Set.empty,
    protectedIds: Set[OriginalTokenId] = Set.empty,
    protectedContents: Set[String] = Set.empty
):
  def forbidden(surface: TokenSurface): Vector[ForbiddenCharacter] =
    surface.codePoints.distinct.flatMap(forbidden)

  /** Reuse the exact same predicate for generated-text metrics and tokenizer classification. */
  def forbidden(cp: CodePoint): Option[ForbiddenCharacter] =
      val script = UnicodeData.script(cp)
      val reasons = Set.newBuilder[ForbiddenReason]
      if forbiddenScripts(script) then reasons += ForbiddenReason.Script(script)
      if pruneHanOutsideReference && UnicodeData.isHanKanji(cp) && !japanese.contains(cp) then reasons += ForbiddenReason.HanOutsideJapaneseReference
      if explicitForbidden(cp) then reasons += ForbiddenReason.Explicit
      val result = reasons.result()
      if result.isEmpty then None else Some(ForbiddenCharacter(cp, result))

enum ProtectionReason:
  case BaseByte, PartialUtf8, AddedToken, RequiredReference, Explicit

final case class TokenAnalysis(
    id: OriginalTokenId,
    content: String,
    decoded: Option[DecodedToken],
    surface: Option[TokenSurface],
    protection: Set[ProtectionReason],
    forbidden: Vector[ForbiddenCharacter]
):
  def protectedToken: Boolean = protection.nonEmpty
  def directDelete: Boolean = !protectedToken && forbidden.nonEmpty

object ProtocolTokens:
  val CoderNextRequired: Set[String] = Set("<|endoftext|>", "<|im_start|>", "<|im_end|>",
    "<|fim_prefix|>", "<|fim_middle|>", "<|fim_suffix|>", "<|fim_pad|>", "<|repo_name|>", "<|file_sep|>",
    "<tool_call>", "</tool_call>", "<tool_response>", "</tool_response>", "<think>", "</think>")

final case class VocabularyAnalysis(
    tokens: Vector[TokenAnalysis],
    cleanup: MergeCleanup,
    projection: VocabularyProjection,
    unregisteredIds: Set[OriginalTokenId]
):
  def survivingTokens: Vector[TokenAnalysis] = tokens.filter(token => projection.compact(token.id).isDefined)
  def protectedConflicts: Vector[TokenAnalysis] = tokens.filter(token => token.protectedToken && token.forbidden.nonEmpty)

/** Classify first, then clean the DAG and construct a deterministic projection. No file access. */
object VocabularyOperations:
  def analyze(
      tokenizer: TokenizerData,
      originalRows: Int,
      policy: PruningPolicy,
      requiredIds: Set[OriginalTokenId] = Set.empty,
      requiredContents: Set[String] = ProtocolTokens.CoderNextRequired
  ): VocabularyAnalysis =
    DomainChecks.check(originalRows > 0, "invalid-vocabulary-size", "analysis", "original row count must be positive")
    val graph = BpeGraph.from(tokenizer)
    val lexical = tokenizer.vocabulary.map(token => token.id -> token).toMap
    val added = tokenizer.addedTokens.map(token => token.id -> token).toMap
    DomainChecks.check(added.size == tokenizer.addedTokens.size && tokenizer.addedTokens.map(_.content).distinct.size == added.size,
      "duplicate-added-token", "analysis", "duplicate added token IDs or contents")
    added.foreach { (id, token) =>
      DomainChecks.check(lexical.get(id).forall(_.encoded == token.content) &&
        graph.vocabulary.get(token.content).forall(_ == id), "token-identity-conflict", "analysis", token.content)
    }
    val ids = graph.ids ++ added.keySet
    DomainChecks.check(ids.forall(_.intValue < originalRows), "id-out-of-range", "analysis", "registered ID exceeds model rows")
    val byContent = graph.vocabulary ++ added.values.map(token => token.content -> token.id)
    val decodedLexical = tokenizer.vocabulary.map(token => token.id -> ByteLevel.decode(token.encoded)).toMap
    val bySurface = decodedLexical.toVector.flatMap((id, decoded) => decoded.surface.map(surface => surface.text -> id)).toMap
    val explicitIds = policy.protectedContents.flatMap { content =>
      val matched = byContent.get(content).toSet ++ bySurface.get(content).toSet
      DomainChecks.check(matched.nonEmpty, "missing-protected-token", "analysis", content)
      matched
    }
    val contentIds = requiredContents.map(content => byContent.getOrElse(content,
      DomainChecks.fail("missing-protocol-token", "analysis", content)))
    val required = requiredIds ++ contentIds
    DomainChecks.check((required ++ policy.protectedIds).subsetOf(ids), "unknown-protected-id", "analysis", "protected ID is unregistered")
    val tokens = ids.toVector.sortBy(_.intValue).map { id =>
      val lex = lexical.get(id)
      val decoded = lex.map(token => decodedLexical(token.id))
      val surface = added.get(id).map(token => TokenSurface(token.content)).orElse(decoded.flatMap(_.surface))
      val content = lex.map(_.encoded).getOrElse(added(id).content)
      val protectedBy = Set.newBuilder[ProtectionReason]
      if decoded.exists(_.bytes.size == 1) then protectedBy += ProtectionReason.BaseByte
      if decoded.exists(_.surface.isEmpty) then protectedBy += ProtectionReason.PartialUtf8
      if added.contains(id) then protectedBy += ProtectionReason.AddedToken
      if required(id) then protectedBy += ProtectionReason.RequiredReference
      if policy.protectedIds(id) || explicitIds(id) then protectedBy += ProtectionReason.Explicit
      TokenAnalysis(id, content, decoded, surface, protectedBy.result(), surface.map(policy.forbidden).getOrElse(Vector.empty))
    }
    val direct = tokens.filter(_.directDelete).map(_.id).toSet
    val protectedLexical = tokens.filter(_.protectedToken).map(_.id).toSet.intersect(graph.ids)
    val cleanup = graph.cleanup(direct, protectedLexical)
    val surviving = cleanup.survivingIds ++ added.keySet
    val projection = VocabularyProjection.fromSurvivors(originalRows, surviving)
    val unregistered = (0 until originalRows).iterator.map(OriginalTokenId.apply).filterNot(ids).toSet
    VocabularyAnalysis(tokens, cleanup, projection, unregistered)
