package me.nsmr.vocabsurgery.tokenizer

import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.model.TokenizerData

enum ProtectionReason:
  case BaseToken, PartialUtf8, AddedToken, RequiredReference
  case Policy(reason: PolicyReason)
  def code: String = this match
    case Policy(reason) => s"policy_${reason.code}"
    case other => other.toString

final case class TokenAnalysis(
    id: OriginalTokenId,
    content: String,
    decoded: Option[DecodedToken],
    surface: Option[TokenSurface],
    protection: Set[ProtectionReason],
    deletion: Vector[PolicyReason]
):
  def protectedToken: Boolean = protection.nonEmpty
  def directDelete: Boolean = !protectedToken && deletion.nonEmpty

final case class VocabularyAnalysis(
    tokens: Vector[TokenAnalysis],
    cleanup: MergeCleanup,
    projection: VocabularyProjection,
    unregisteredIds: Set[OriginalTokenId]
):
  def survivingTokens: Vector[TokenAnalysis] = tokens.filter(token => projection.compact(token.id).isDefined)
  def protectedConflicts: Vector[TokenAnalysis] = tokens.filter(token => token.protectedToken && token.deletion.nonEmpty)

/** Classify first, then clean the DAG and construct a deterministic projection. No file access. */
object VocabularyOperations:
  def analyze(
      tokenizer: TokenizerData,
      scheme: TokenizerScheme,
      originalRows: Int,
      policy: PruningPolicy,
      requiredIds: Set[OriginalTokenId],
      requiredContents: Set[String]
  ): VocabularyAnalysis =
    DomainChecks.check(originalRows > 0, "invalid-vocabulary-size", "analysis", "original row count must be positive")
    val graph = BpeGraph.from(tokenizer, scheme)
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
    val decodedLexical = tokenizer.vocabulary.map(token => token.id -> scheme.decode(token.encoded)).toMap
    val contentIds = requiredContents.map(content => byContent.getOrElse(content,
      DomainChecks.fail("missing-protocol-token", "analysis", content)))
    val required = requiredIds ++ contentIds
    DomainChecks.check(required.subsetOf(ids), "unknown-protected-id", "analysis", "required ID is unregistered")
    val candidates = ids.toVector.sortBy(_.intValue).map { id =>
      val lex = lexical.get(id)
      val decoded = lex.map(token => decodedLexical(token.id))
      val surface = added.get(id).map(token => TokenSurface(token.content)).orElse(decoded.flatMap(_.surface))
      TokenCandidate(id, lex.map(_.encoded).getOrElse(added(id).content), surface, decoded, added.contains(id))
    }
    val decisions = policy.decide(candidates)
    DomainChecks.check(decisions.keySet.subsetOf(ids), "unknown-policy-token", policy.name, "policy decided on an unregistered ID")
    val tokens = candidates.map { candidate =>
      val id = candidate.id
      val decision = decisions.getOrElse(id, PolicyDecision())
      val protectedBy = Set.newBuilder[ProtectionReason]
      if graph.roots(id) then protectedBy += ProtectionReason.BaseToken
      if candidate.decoded.exists(_.surface.isEmpty) then protectedBy += ProtectionReason.PartialUtf8
      if candidate.added then protectedBy += ProtectionReason.AddedToken
      if required(id) then protectedBy += ProtectionReason.RequiredReference
      decision.keep.foreach(reason => protectedBy += ProtectionReason.Policy(reason))
      TokenAnalysis(id, candidate.content, candidate.decoded, candidate.surface, protectedBy.result(), decision.delete)
    }
    val direct = tokens.filter(_.directDelete).map(_.id).toSet
    val protectedLexical = tokens.filter(_.protectedToken).map(_.id).toSet.intersect(graph.ids)
    val cleanup = graph.cleanup(direct, protectedLexical)
    val surviving = cleanup.survivingIds ++ added.keySet
    val projection = VocabularyProjection.fromSurvivors(originalRows, surviving)
    val unregistered = (0 until originalRows).iterator.map(OriginalTokenId.apply).filterNot(ids).toSet
    VocabularyAnalysis(tokens, cleanup, projection, unregistered)
