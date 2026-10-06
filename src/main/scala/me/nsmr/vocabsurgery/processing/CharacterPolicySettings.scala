package me.nsmr.vocabsurgery.processing

import io.circe.{Json, JsonObject}
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.{Diagnostics, JsonFields}
import me.nsmr.vocabsurgery.kanji.{JapaneseKanjiReference, KanjiData, KanjiLoader}
import me.nsmr.vocabsurgery.tokenizer.*
import me.nsmr.vocabsurgery.unicode.*

/** Settings for `CharacterPolicy`. `japaneseReference` is the kanji-data directory the Japanese kanji set is built from. */
final case class CharacterPolicySettings(
    japaneseReference: Path = Path.of("kanji-data"),
    forbiddenScripts: Set[UnicodeScript] = Set(UnicodeScript.Arabic, UnicodeScript.Cyrillic, UnicodeScript.Hangul),
    pruneHanOutsideReference: Boolean = true,
    japaneseExtra: Set[CodePoint] = Set.empty,
    explicitForbidden: Set[CodePoint] = Set.empty,
    protectedIds: Set[OriginalTokenId] = Set.empty,
    protectedContents: Set[String] = Set.empty,
    protectMath: Boolean = true
) extends PolicySettings:
  def kind: PolicyKind = CharacterPolicySettings
  def inputs: Vector[Path] = Vector(japaneseReference)
  def resolved: CharacterPolicySettings = copy(japaneseReference = japaneseReference.toRealPath())

  def domain(reference: JapaneseKanjiReference): CharacterPolicy =
    CharacterPolicy(reference, forbiddenScripts, pruneHanOutsideReference, explicitForbidden, protectedIds, protectedContents, protectMath)

  def load(): LoadedPolicy =
    val data = KanjiLoader.load(japaneseReference)
    CharacterPolicySettings.Loaded(domain(JapaneseKanjiReference.from(data, japaneseExtra)), data, ProcessingIO.revision(japaneseReference))

  def json: Json = Json.obj(
    "japaneseReference" -> Json.fromString(japaneseReference.toString),
    "unicodeVersion" -> Json.fromString(UnicodeData.provenance.unicodeVersion),
    "forbiddenScripts" -> Json.fromValues(forbiddenScripts.toVector.map(_.name).sorted.map(Json.fromString)),
    "pruneHanOutsideReference" -> Json.fromBoolean(pruneHanOutsideReference),
    "japaneseExtra" -> Json.fromValues(japaneseExtra.toVector.sortBy(_.intValue).map(cp => Json.fromInt(cp.intValue))),
    "explicitForbidden" -> Json.fromValues(explicitForbidden.toVector.sortBy(_.intValue).map(cp => Json.fromInt(cp.intValue))),
    "protectedIds" -> Json.fromValues(protectedIds.toVector.sortBy(_.intValue).map(id => Json.fromInt(id.intValue))),
    "protectedContents" -> Json.fromValues(protectedContents.toVector.sorted.map(Json.fromString)),
    "unregisteredRows" -> Json.fromString("drop"),
    "protectMath" -> Json.fromBoolean(protectMath))

object CharacterPolicySettings extends PolicyKind:
  val name = "characters"

  def parse(fields: JsonObject, path: Path): CharacterPolicySettings =
    val names = Set("japaneseReference", "unicodeVersion", "forbiddenScripts", "pruneHanOutsideReference", "japaneseExtra",
      "explicitForbidden", "protectedIds", "protectedContents", "unregisteredRows", "protectMath")
    fields.keys.find(!names(_)).foreach(name => Diagnostics.invalid(path, name, "unknown policy field"))
    fields("unicodeVersion").foreach(v => Diagnostics.check(JsonFields.text(v, path, "unicodeVersion") == UnicodeData.provenance.unicodeVersion, path, "unicodeVersion", "Unicode version does not match pinned provider"))
    fields("unregisteredRows").foreach(v => Diagnostics.check(JsonFields.text(v, path, "unregisteredRows") == "drop", path, "unregisteredRows", "only drop is supported"))
    def array(name: String): Option[Vector[Json]] = fields(name).map(JsonFields.array(_, path, name))
    def points(name: String): Set[CodePoint] = array(name).getOrElse(Vector.empty).map(value => CodePoint(JsonFields.int(value, path, name))).toSet
    def flag(name: String): Boolean = fields(name).map(JsonFields.bool(_, path, name)).getOrElse(true)
    val defaults = CharacterPolicySettings()
    CharacterPolicySettings(
      fields("japaneseReference").map(v => Path.of(JsonFields.text(v, path, "japaneseReference"))).getOrElse(defaults.japaneseReference),
      array("forbiddenScripts").map(_.map(v => UnicodeScript.fromName(JsonFields.text(v, path, "forbiddenScripts"))).toSet).getOrElse(defaults.forbiddenScripts),
      flag("pruneHanOutsideReference"), points("japaneseExtra"), points("explicitForbidden"),
      array("protectedIds").getOrElse(Vector.empty).map(v => OriginalTokenId(JsonFields.int(v, path, "protectedIds"))).toSet,
      array("protectedContents").getOrElse(Vector.empty).map(v => JsonFields.text(v, path, "protectedContents")).toSet,
      flag("protectMath"))

  private final case class Loaded(policy: CharacterPolicy, data: KanjiData, revision: Option[String]) extends LoadedPolicy:
    private val reference = policy.japanese
    private def hashes: Json = Json.obj(reference.sourceHashes.toVector.sortBy(_._1).map((name, hash) => name -> Json.fromString(hash))*)
    private def outsideReference(token: TokenAnalysis): Vector[PolicyReason] = token.deletion.filter(_.code == ForbiddenReason.HanOutsideJapaneseReference.code)

    def fingerprint: Json = Json.obj("kanjiRevision" -> revision.fold(Json.Null)(Json.fromString), "kanjiSourceHashes" -> hashes,
      "unicode" -> Json.obj("provider" -> Json.fromString(reference.unicode.provider), "providerVersion" -> Json.fromString(reference.unicode.providerVersion),
        "unicodeVersion" -> Json.fromString(reference.unicode.unicodeVersion)),
      "hanDefinition" -> Json.fromString("Script=Han && General_Category=Lo"))

    override def summary(analysis: VocabularyAnalysis): Json =
      val candidates = analysis.tokens.filter(outsideReference(_).nonEmpty)
      Json.obj("hanCandidates" -> Json.fromInt(candidates.size), "hanExamples" -> Json.fromValues(candidates.take(10).flatMap(_.surface).map(s => Json.fromString(s.text))))

    override def writeReports(analysis: VocabularyAnalysis, directory: Path): Unit =
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
        "sourceHashes" -> hashes))
      ProcessingIO.lines(directory.resolve("kanji-coverage.jsonl"), reference.sortedCharacters.iterator.map { cp =>
        Json.obj("codePoint" -> Json.fromInt(cp.intValue), "glyph" -> Json.fromString(cp.text), "raw" -> Json.fromBoolean(reference.raw(cp)),
          "nfc" -> Json.fromValues(UnicodeData.normalizedCodePoints(cp).toVector.sortBy(_.intValue).map(n => Json.fromInt(n.intValue))),
          "origins" -> Json.fromValues(reference.origins.getOrElse(cp, Set.empty).toVector.sortBy(o => (o.source, o.record, o.field)).map(o => Json.obj("source" -> Json.fromString(o.source), "record" -> Json.fromInt(o.record), "field" -> Json.fromString(o.field)))))
      })
      ProcessingIO.lines(directory.resolve("han-pruning-candidates.jsonl"), analysis.tokens.iterator.filter(outsideReference(_).nonEmpty).map(t => Json.obj(
        "originalId" -> Json.fromInt(t.id.intValue), "surface" -> t.surface.fold(Json.Null)(s => Json.fromString(s.text)), "protected" -> Json.fromBoolean(t.protectedToken),
        "codePoints" -> Json.fromValues(outsideReference(t).flatMap(_.character).map(cp => Json.fromInt(cp.intValue))))))
      ProcessingIO.lines(directory.resolve("kanji-missing-texts.jsonl"), data.glyphDiagnostics.iterator.map(d => Json.obj("record" -> Json.fromLong(d.recordNumber), "rawText" -> Json.fromString(d.rawText), "reason" -> Json.fromString(d.reason))))
