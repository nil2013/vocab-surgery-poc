package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.model.{ModelSnapshot, SnapshotLoader}
import me.nsmr.vocabsurgery.tokenizer.*
import me.nsmr.vocabsurgery.unicode.UnicodeData

object PlanReports:
  def inspection(source: Path): Json =
    val model = SnapshotLoader.load(source.toRealPath())
    inspection(model)

  def inspection(model: ModelSnapshot): Json =
    Json.obj("source" -> Json.fromString(model.directory.toString), "family" -> Json.fromString(model.config.family.name),
      "rows" -> Json.fromInt(model.config.vocabSize), "hiddenSize" -> Json.fromInt(model.config.hiddenSize),
      "lexicalTokens" -> Json.fromInt(model.tokenizer.vocabulary.size), "addedTokens" -> Json.fromInt(model.tokenizer.addedTokens.size),
      "merges" -> Json.fromInt(model.tokenizer.merges.size), "shards" -> Json.fromInt(model.shards.size),
      "tensors" -> Json.fromInt(model.shards.map(_.tensors.size).sum),
      "inputs" -> Json.fromValues(InputInventory.capture(model).map(_.json)))

  /** Emit metadata and diagnostics with streaming JSONL. No weights are created. */
  def write(plan: PreparedPlan, directory: Path): Unit =
    val domain = plan.domain
    ProcessingIO.write(directory.resolve("environment.json"), Json.obj("toolRevision" -> ProcessingIO.revision(Path.of(".")).fold(Json.Null)(Json.fromString),
      "javaVersion" -> Json.fromString(sys.props("java.version")), "osName" -> Json.fromString(sys.props("os.name")),
      "osVersion" -> Json.fromString(sys.props("os.version")), "osArch" -> Json.fromString(sys.props("os.arch")),
      "maxJvmHeapBytes" -> Json.fromLong(Runtime.getRuntime.maxMemory()), "recordedAt" -> Json.fromString(java.time.Instant.now().toString)))
    if !java.nio.file.Files.exists(directory.resolve("inspection.json")) then
      ProcessingIO.write(directory.resolve("inspection.json"), inspection(plan.source))
    ProcessingIO.write(directory.resolve("plan.json"), plan.json)
    ProcessingIO.write(directory.resolve("policy.effective.json"), Json.obj("policy" -> plan.policy.json, "inputs" -> plan.loadedPolicy.fingerprint))
    ProcessingIO.write(directory.resolve("original-to-compact.json"), domain.projection.originalToCompactJson)
    ProcessingIO.write(directory.resolve("compact-to-original.json"), domain.projection.compactToOriginalJson)
    ProcessingIO.lines(directory.resolve("tokens.jsonl"), domain.analysis.tokens.iterator.map { token =>
      Json.obj("originalId" -> Json.fromInt(token.id.intValue), "compactId" -> domain.projection.compact(token.id).fold(Json.Null)(id => Json.fromInt(id.intValue)),
        "content" -> Json.fromString(token.content), "surface" -> token.surface.fold(Json.Null)(s => Json.fromString(s.text)),
        "bytesHex" -> token.decoded.fold(Json.Null)(d => Json.fromString(d.bytes.map(b => f"${b & 255}%02x").mkString)),
        "protection" -> Json.fromValues(token.protection.toVector.map(_.code).sorted.map(Json.fromString)),
        "directDeleted" -> Json.fromBoolean(domain.analysis.cleanup.directDeleted(token.id)), "indirectDeleted" -> Json.fromBoolean(domain.analysis.cleanup.indirectDeleted(token.id)),
        "deletion" -> Json.fromValues(token.deletion.map(reason => Json.obj("code" -> Json.fromString(reason.code),
          "codePoint" -> reason.character.fold(Json.Null)(cp => Json.fromInt(cp.intValue))))))
    })
    ProcessingIO.write(directory.resolve("merge-cleanup.json"), Json.obj("directDeleted" -> Json.fromInt(domain.analysis.cleanup.directDeleted.size),
      "indirectDeleted" -> Json.fromInt(domain.analysis.cleanup.indirectDeleted.size), "removedRanks" -> Json.fromValues(domain.analysis.cleanup.removedRanks.map(Json.fromInt)),
      "unreachableProtected" -> Json.fromValues(domain.analysis.cleanup.unreachableProtected.toVector.sortBy(_.intValue).map(id => Json.fromInt(id.intValue)))))
    plan.loadedPolicy.writeReports(domain.analysis, directory)
    ProcessingIO.write(directory.resolve("summary.json"), Json.obj("rows" -> Json.fromInt(domain.projection.compactSize), "removedRows" -> Json.fromInt(domain.removedRows),
      "directDeleted" -> Json.fromInt(domain.analysis.cleanup.directDeleted.size), "indirectDeleted" -> Json.fromInt(domain.analysis.cleanup.indirectDeleted.size),
      "unregisteredRows" -> Json.fromInt(domain.analysis.unregisteredIds.size), "protectedConflicts" -> Json.fromInt(domain.analysis.protectedConflicts.size),
      "policy" -> plan.loadedPolicy.summary(domain.analysis),
      "savedPayloadBytes" -> Json.fromLong(domain.savedPayloadBytes.longValue)))
