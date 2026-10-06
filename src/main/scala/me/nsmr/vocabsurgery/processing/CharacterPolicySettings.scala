package me.nsmr.vocabsurgery.processing

import io.circe.{Json, JsonObject}
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO, JsonFields}
import me.nsmr.vocabsurgery.kanji.{JapaneseKanjiReference, KanjiData, KanjiLoader}
import me.nsmr.vocabsurgery.tokenizer.*
import me.nsmr.vocabsurgery.unicode.*

/** Settings for `CharacterPolicy`. `japaneseReference` is the kanji-data directory the Japanese kanji set is built from;
  * `deletedTokens` names an optional deletion list (see `DeletionList`).
  * `pruneSimplifiedForms` / `pruneTraditionalForms` forbid Han characters that Unihan gives a different traditional /
  * simplified variant, except the 常用 and 人名用 characters (`子の名に使える漢字`). They read `Unihan_Variants.txt` from
  * `unihan`, by default the `fetch-reference unihan` directory for ICU's Unicode version, and require that version.
  */
final case class CharacterPolicySettings(
    japaneseReference: Path = Path.of("kanji-data"),
    forbiddenScripts: Set[UnicodeScript] = Set(UnicodeScript.Arabic, UnicodeScript.Cyrillic, UnicodeScript.Hangul),
    pruneHanOutsideReference: Boolean = true,
    japaneseExtra: Set[CodePoint] = Set.empty,
    explicitForbidden: Set[CodePoint] = Set.empty,
    protectedIds: Set[OriginalTokenId] = Set.empty,
    protectedContents: Set[String] = Set.empty,
    protectMath: Boolean = true,
    deletedTokens: Option[Path] = None,
    pruneSimplifiedForms: Boolean = false,
    pruneTraditionalForms: Boolean = false,
    unihan: Option[Path] = None
) extends PolicySettings:
  def kind: PolicyKind = CharacterPolicySettings
  private def usesUnihan: Boolean = pruneSimplifiedForms || pruneTraditionalForms
  private def unihanDirectory: Path = unihan.getOrElse(ReferenceFetch.directory(Path.of("outputs"), UnihanSource, UnicodeData.unicodeVersion))
  def inputs: Vector[Path] = (japaneseReference +: deletedTokens.toVector) ++ Option.when(usesUnihan)(unihanDirectory)
  def resolved: CharacterPolicySettings = copy(japaneseReference = japaneseReference.toRealPath(), deletedTokens = deletedTokens.map(_.toRealPath()),
    unihan = if usesUnihan then Some(CharacterPolicySettings.existing(unihanDirectory).toRealPath()) else unihan)

  def domain(reference: JapaneseKanjiReference, deleted: Set[String] = Set.empty, simplified: Set[CodePoint] = Set.empty,
      traditional: Set[CodePoint] = Set.empty): CharacterPolicy =
    CharacterPolicy(reference, forbiddenScripts, pruneHanOutsideReference, explicitForbidden, protectedIds, protectedContents, protectMath,
      deleted, simplified, traditional)

  def load(): LoadedPolicy =
    val data = KanjiLoader.load(japaneseReference)
    val deleted = deletedTokens.map(DeletionList.read).getOrElse(Vector.empty)
    val variants = Option.when(usesUnihan) {
      val file = CharacterPolicySettings.existing(unihanDirectory).resolve(UnihanLoader.FileName)
      val loaded = UnihanLoader.load(file)
      Diagnostics.check(loaded.unicodeVersion == UnicodeData.unicodeVersion, file, "header",
        s"Unihan is Unicode ${loaded.unicodeVersion} but ICU is ${UnicodeData.unicodeVersion}")
      (loaded, FileIO.sha256(file))
    }
    // Daily Japanese: 常用 forms and everything in 子の名に使える漢字 (人名用 and the 常用 variants).
    val japaneseCore = data.common.records.map(_.character).toSet ++ data.names.records.map(_.character) ++ data.commonTable.records.map(_.commonForm)
    def forms(enabled: Boolean, select: UnihanVariants => Set[CodePoint]) =
      variants.filter(_ => enabled).map((v, _) => select(v) -- japaneseCore).getOrElse(Set.empty)
    CharacterPolicySettings.Loaded(domain(JapaneseKanjiReference.from(data, japaneseExtra), deleted.toSet,
      forms(pruneSimplifiedForms, _.simplifiedForms), forms(pruneTraditionalForms, _.traditionalForms)), data,
      ProcessingIO.revision(japaneseReference), deletedTokens.map(FileIO.sha256(_)), variants.map((v, hash) => v.unicodeVersion -> hash))

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
    .mapObject(fields => deletedTokens.fold(fields)(path => fields.add("deletedTokens", Json.fromString(path.toString))))
    .mapObject(_.add("pruneSimplifiedForms", Json.fromBoolean(pruneSimplifiedForms)).add("pruneTraditionalForms", Json.fromBoolean(pruneTraditionalForms)))
    .mapObject(fields => unihan.fold(fields)(path => fields.add("unihan", Json.fromString(path.toString))))

object CharacterPolicySettings extends PolicyKind:
  val name = "characters"

  def parse(fields: JsonObject, path: Path): CharacterPolicySettings =
    val names = Set("japaneseReference", "unicodeVersion", "forbiddenScripts", "pruneHanOutsideReference", "japaneseExtra",
      "explicitForbidden", "protectedIds", "protectedContents", "unregisteredRows", "protectMath", "deletedTokens",
      "pruneSimplifiedForms", "pruneTraditionalForms", "unihan")
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
      flag("protectMath"), fields("deletedTokens").map(v => Path.of(JsonFields.text(v, path, "deletedTokens"))),
      fields("pruneSimplifiedForms").exists(JsonFields.bool(_, path, "pruneSimplifiedForms")),
      fields("pruneTraditionalForms").exists(JsonFields.bool(_, path, "pruneTraditionalForms")),
      fields("unihan").map(v => Path.of(JsonFields.text(v, path, "unihan"))))

  /** A missing reference directory names the command that creates it. */
  private def existing(directory: Path): Path =
    Diagnostics.check(java.nio.file.Files.isDirectory(directory), directory, "unihan", "missing; run scripts/vocab-surgery fetch-reference unihan")
    directory

  private final case class Loaded(policy: CharacterPolicy, data: KanjiData, revision: Option[String], deletedTokensSha256: Option[String],
      unihan: Option[(String, String)]) extends LoadedPolicy:
    private val reference = policy.japanese
    private def hashes: Json = Json.obj(reference.sourceHashes.toVector.sortBy(_._1).map((name, hash) => name -> Json.fromString(hash))*)
    private def outsideReference(token: TokenAnalysis): Vector[PolicyReason] = token.deletion.filter(_.code == ForbiddenReason.HanOutsideJapaneseReference.code)

    def fingerprint: Json = Json.obj("kanjiRevision" -> revision.fold(Json.Null)(Json.fromString), "kanjiSourceHashes" -> hashes,
      "unicode" -> Json.obj("provider" -> Json.fromString(reference.unicode.provider), "providerVersion" -> Json.fromString(reference.unicode.providerVersion),
        "unicodeVersion" -> Json.fromString(reference.unicode.unicodeVersion)),
      "hanDefinition" -> Json.fromString("Script=Han && General_Category=Lo"))
      .mapObject(fields => deletedTokensSha256.fold(fields)(hash => fields.add("deletedTokensSha256", Json.fromString(hash))))
      .mapObject(fields => unihan.fold(fields)((version, hash) => fields.add("unihan", Json.obj("unicodeVersion" -> Json.fromString(version),
        "variantsSha256" -> Json.fromString(hash)))))

    private def variantTokens(analysis: VocabularyAnalysis, reason: ForbiddenReason): Int =
      analysis.tokens.count(_.deletion.exists(_.code == reason.code))

    private def unmatched(analysis: VocabularyAnalysis): Vector[String] =
      policy.unmatchedDeletions(analysis.tokens.map(t => TokenCandidate(t.id, t.content, t.surface, t.decoded, t.protection(ProtectionReason.AddedToken))))
    private def listed(analysis: VocabularyAnalysis): Vector[TokenAnalysis] = analysis.tokens.filter(_.deletion.exists(_.code == "listed"))

    override def summary(analysis: VocabularyAnalysis): Json =
      val candidates = analysis.tokens.filter(outsideReference(_).nonEmpty)
      val missing = unmatched(analysis)
      Json.obj("hanCandidates" -> Json.fromInt(candidates.size), "hanExamples" -> Json.fromValues(candidates.take(10).flatMap(_.surface).map(s => Json.fromString(s.text))),
        "simplifiedForms" -> Json.obj("characters" -> Json.fromInt(policy.simplifiedForms.size),
          "tokens" -> Json.fromInt(variantTokens(analysis, ForbiddenReason.SimplifiedForm))),
        "traditionalForms" -> Json.obj("characters" -> Json.fromInt(policy.traditionalForms.size),
          "tokens" -> Json.fromInt(variantTokens(analysis, ForbiddenReason.TraditionalForm))),
        "deletionList" -> Json.obj("entries" -> Json.fromInt(policy.deletedContents.size), "matchedTokens" -> Json.fromInt(listed(analysis).size),
          "keptByProtection" -> Json.fromInt(listed(analysis).count(_.protectedToken)), "unmatchedEntries" -> Json.fromInt(missing.size),
          "unmatchedExamples" -> Json.fromValues(missing.take(10).map(Json.fromString))))

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
      val forms = policy.simplifiedForms.toVector.map(_ -> "simplified") ++ policy.traditionalForms.toVector.map(_ -> "traditional")
      ProcessingIO.lines(directory.resolve("han-variant-forms.jsonl"), forms.sortBy(_._1.intValue).iterator.map { (cp, kind) =>
        Json.obj("codePoint" -> Json.fromInt(cp.intValue), "glyph" -> Json.fromString(cp.text), "form" -> Json.fromString(kind),
          "inJapaneseReference" -> Json.fromBoolean(reference.contains(cp)))
      })
      ProcessingIO.lines(directory.resolve("deletion-list-unmatched.jsonl"), unmatched(analysis).iterator.map(Json.fromString))
      ProcessingIO.lines(directory.resolve("kanji-missing-texts.jsonl"), data.glyphDiagnostics.iterator.map(d => Json.obj("record" -> Json.fromLong(d.recordNumber), "rawText" -> Json.fromString(d.rawText), "reason" -> Json.fromString(d.reason))))
