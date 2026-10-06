package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.kanji.KanjiLoader
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
    val reference = domain.policy.japanese
    val data = KanjiLoader.load(plan.kanjiDirectory)
    ProcessingIO.write(directory.resolve("environment.json"), Json.obj("toolRevision" -> ProcessingIO.revision(Path.of(".")).fold(Json.Null)(Json.fromString),
      "javaVersion" -> Json.fromString(sys.props("java.version")), "osName" -> Json.fromString(sys.props("os.name")),
      "osVersion" -> Json.fromString(sys.props("os.version")), "osArch" -> Json.fromString(sys.props("os.arch")),
      "maxJvmHeapBytes" -> Json.fromLong(Runtime.getRuntime.maxMemory()), "recordedAt" -> Json.fromString(java.time.Instant.now().toString)))
    if !java.nio.file.Files.exists(directory.resolve("inspection.json")) then
      ProcessingIO.write(directory.resolve("inspection.json"), inspection(plan.source))
    ProcessingIO.write(directory.resolve("plan.json"), plan.json)
    ProcessingIO.write(directory.resolve("policy.effective.json"), Json.obj("settings" -> plan.settings.json,
      "unicode" -> Json.obj("provider" -> Json.fromString(reference.unicode.provider), "providerVersion" -> Json.fromString(reference.unicode.providerVersion), "unicodeVersion" -> Json.fromString(reference.unicode.unicodeVersion)),
      "hanDefinition" -> Json.fromString("Script=Han && General_Category=Lo"), "kanjiRevision" -> plan.kanjiRevision.fold(Json.Null)(Json.fromString),
      "sourceHashes" -> Json.obj(reference.sourceHashes.toVector.sortBy(_._1).map((name, hash) => name -> Json.fromString(hash))*)))
    ProcessingIO.write(directory.resolve("original-to-compact.json"), domain.projection.originalToCompactJson)
    ProcessingIO.write(directory.resolve("compact-to-original.json"), domain.projection.compactToOriginalJson)
    ProcessingIO.lines(directory.resolve("tokens.jsonl"), domain.analysis.tokens.iterator.map { token =>
      Json.obj("originalId" -> Json.fromInt(token.id.intValue), "compactId" -> domain.projection.compact(token.id).fold(Json.Null)(id => Json.fromInt(id.intValue)),
        "content" -> Json.fromString(token.content), "surface" -> token.surface.fold(Json.Null)(s => Json.fromString(s.text)),
        "bytesHex" -> token.decoded.fold(Json.Null)(d => Json.fromString(d.bytes.map(b => f"${b & 255}%02x").mkString)),
        "protection" -> Json.fromValues(token.protection.toVector.map(_.toString).sorted.map(Json.fromString)),
        "directDeleted" -> Json.fromBoolean(domain.analysis.cleanup.directDeleted(token.id)), "indirectDeleted" -> Json.fromBoolean(domain.analysis.cleanup.indirectDeleted(token.id)),
        "forbidden" -> Json.fromValues(token.forbidden.map(f => Json.obj("codePoint" -> Json.fromInt(f.character.intValue), "reasons" -> Json.fromValues(f.reasons.toVector.map(_.code).sorted.map(Json.fromString))))))
    })
    ProcessingIO.write(directory.resolve("merge-cleanup.json"), Json.obj("directDeleted" -> Json.fromInt(domain.analysis.cleanup.directDeleted.size),
      "indirectDeleted" -> Json.fromInt(domain.analysis.cleanup.indirectDeleted.size), "removedRanks" -> Json.fromValues(domain.analysis.cleanup.removedRanks.map(Json.fromInt)),
      "unreachableProtected" -> Json.fromValues(domain.analysis.cleanup.unreachableProtected.toVector.sortBy(_.intValue).map(id => Json.fromInt(id.intValue)))))
    ProcessingIO.write(directory.resolve("japanese-kanji-reference.json"), Json.fromValues(reference.sortedCharacters.map(cp => Json.fromInt(cp.intValue))))
    ProcessingIO.write(directory.resolve("kanji-reference.json"), Json.obj("rawCount" -> Json.fromInt(reference.raw.size), "normalizedCount" -> Json.fromInt(reference.normalized.size),
      "referenceCount" -> Json.fromInt(reference.characters.size), "missingTexts" -> Json.fromInt(reference.missingTexts.size),
      "adoptedFields" -> Json.obj("JIS漢字.csv" -> Json.fromString("漢字"), "常用漢字.csv" -> Json.fromString("漢字"),
        "教育漢字.csv" -> Json.fromString("漢字"), "子の名に使える漢字.csv" -> Json.fromString("漢字"),
        "常用漢字表本表.json" -> Json.arr(Json.fromString("通用字体"), Json.fromString("康熙字典体")), "漢検漢字辞典漢字.csv" -> Json.fromString("漢字テキスト")),
      "sourceRecordCounts" -> Json.obj("jis" -> Json.fromInt(data.jis.records.size), "common" -> Json.fromInt(data.common.records.size),
        "education" -> Json.fromInt(data.education.records.size), "names" -> Json.fromInt(data.names.records.size),
        "commonTable" -> Json.fromInt(data.commonTable.records.size), "kanken" -> Json.fromInt(data.kanken.records.size)),
      "duplicateGlyphOccurrences" -> Json.fromInt(data.jis.records.size + data.common.records.size + data.education.records.size + data.names.records.size +
        data.commonTable.records.size + data.commonTable.records.map(_.kangxiForms.size).sum + data.kanken.records.count(_.character.isDefined) - reference.raw.size),
      "sourceHashes" -> Json.obj(reference.sourceHashes.toVector.sortBy(_._1).map((name, hash) => name -> Json.fromString(hash))*)))
    ProcessingIO.lines(directory.resolve("kanji-coverage.jsonl"), reference.sortedCharacters.iterator.map { cp =>
      Json.obj("codePoint" -> Json.fromInt(cp.intValue), "glyph" -> Json.fromString(cp.text), "raw" -> Json.fromBoolean(reference.raw(cp)),
        "nfc" -> Json.fromValues(UnicodeData.normalizedCodePoints(cp).toVector.sortBy(_.intValue).map(n => Json.fromInt(n.intValue))),
        "origins" -> Json.fromValues(reference.origins.getOrElse(cp, Set.empty).toVector.sortBy(o => (o.source, o.record, o.field)).map(o => Json.obj("source" -> Json.fromString(o.source), "record" -> Json.fromInt(o.record), "field" -> Json.fromString(o.field)))))
    })
    val candidates = domain.analysis.tokens.iterator.filter(_.forbidden.exists(_.reasons(ForbiddenReason.HanOutsideJapaneseReference)))
    ProcessingIO.lines(directory.resolve("han-pruning-candidates.jsonl"), candidates.map(t => Json.obj("originalId" -> Json.fromInt(t.id.intValue),
      "surface" -> t.surface.fold(Json.Null)(s => Json.fromString(s.text)), "protected" -> Json.fromBoolean(t.protectedToken),
      "codePoints" -> Json.fromValues(t.forbidden.filter(_.reasons(ForbiddenReason.HanOutsideJapaneseReference)).map(f => Json.fromInt(f.character.intValue))))))
    ProcessingIO.lines(directory.resolve("kanji-missing-texts.jsonl"), data.glyphDiagnostics.iterator.map(d => Json.obj("record" -> Json.fromLong(d.recordNumber), "rawText" -> Json.fromString(d.rawText), "reason" -> Json.fromString(d.reason))))
    ProcessingIO.write(directory.resolve("summary.json"), Json.obj("rows" -> Json.fromInt(domain.projection.compactSize), "removedRows" -> Json.fromInt(domain.removedRows),
      "directDeleted" -> Json.fromInt(domain.analysis.cleanup.directDeleted.size), "indirectDeleted" -> Json.fromInt(domain.analysis.cleanup.indirectDeleted.size),
      "unregisteredRows" -> Json.fromInt(domain.analysis.unregisteredIds.size), "protectedConflicts" -> Json.fromInt(domain.analysis.protectedConflicts.size),
      "hanCandidates" -> Json.fromInt(domain.analysis.tokens.count(_.forbidden.exists(_.reasons(ForbiddenReason.HanOutsideJapaneseReference)))),
      "hanExamples" -> Json.fromValues(domain.analysis.tokens.filter(_.forbidden.exists(_.reasons(ForbiddenReason.HanOutsideJapaneseReference))).take(10).flatMap(_.surface).map(s => Json.fromString(s.text))),
      "savedPayloadBytes" -> Json.fromLong(domain.savedPayloadBytes.longValue)))
