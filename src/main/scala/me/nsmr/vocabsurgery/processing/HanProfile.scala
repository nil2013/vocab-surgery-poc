package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.{Files, Path}
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO}
import me.nsmr.vocabsurgery.kanji.*
import me.nsmr.vocabsurgery.lexicon.JMdictLoader
import me.nsmr.vocabsurgery.model.SnapshotLoader
import me.nsmr.vocabsurgery.unicode.{UnicodeData, UnihanLoader}

/** Where the references come from. Unihan defaults to ICU's Unicode version and JMdict to the newest fetched build. */
final case class HanReferencePaths(kanji: Path = Path.of("kanji-data"), unihan: Option[Path] = None, jmdict: Option[Path] = None):
  def resolve(outputRoot: Path): (Path, Path, Path) =
    def fetched(path: Option[Path], fallback: => Option[Path], name: String): Path =
      val directory = path.orElse(fallback).getOrElse(Diagnostics.invalid(outputRoot, name, s"not fetched; run scripts/vocab-surgery fetch-reference $name"))
      Diagnostics.check(Files.isDirectory(directory), directory, name, s"missing; run scripts/vocab-surgery fetch-reference $name")
      directory.toRealPath()
    (kanji.toRealPath(),
      fetched(unihan, Some(ReferenceFetch.directory(outputRoot, UnihanSource, UnicodeData.unicodeVersion)), "unihan"),
      fetched(jmdict, ReferenceFetch.latest(outputRoot, JMdictSource), "jmdict"))

/** Write a static per-character profile of the Han characters in a tokenizer vocabulary or a text. */
object HanProfile:
  def load(paths: HanReferencePaths, outputRoot: Path): (HanReferences, Json) =
    val (kanjiPath, unihanPath, jmdictPath) = paths.resolve(outputRoot)
    val kanji = KanjiLoader.load(kanjiPath)
    val unihanFile = unihanPath.resolve(UnihanLoader.FileName)
    val unihan = UnihanLoader.load(unihanFile)
    Diagnostics.check(unihan.unicodeVersion == UnicodeData.unicodeVersion, unihanFile, "header",
      s"Unihan is Unicode ${unihan.unicodeVersion} but ICU is ${UnicodeData.unicodeVersion}")
    val jmdictFile = jmdictPath.resolve(JMdictLoader.FileName)
    val lexicon = JMdictLoader.load(jmdictFile)
    val inputs = Json.obj(
      "kanji" -> Json.obj("path" -> Json.fromString(kanjiPath.toString), "revision" -> ProcessingIO.revision(kanjiPath).fold(Json.Null)(Json.fromString)),
      "unihan" -> Json.obj("path" -> Json.fromString(unihanFile.toString), "unicodeVersion" -> Json.fromString(unihan.unicodeVersion),
        "sha256" -> Json.fromString(FileIO.sha256(unihanFile))),
      "jmdict" -> Json.obj("path" -> Json.fromString(jmdictFile.toString), "created" -> Json.fromString(lexicon.created),
        "sha256" -> Json.fromString(FileIO.sha256(jmdictFile)), "headwords" -> Json.fromInt(lexicon.headwords.size)),
      "icu" -> Json.obj("version" -> Json.fromString(UnicodeData.provenance.providerVersion), "unicodeVersion" -> Json.fromString(UnicodeData.unicodeVersion)))
    (HanReferences(kanji, JapaneseKanjiReference.from(kanji), unihan, lexicon), inputs)

  /** Han characters with the tokens containing them, in token-count then code-point order. */
  def fromSnapshot(source: Path): Vector[(CodePoint, Vector[String])] =
    val model = SnapshotLoader.load(source.toRealPath())
    val scheme = model.config.family.tokenizer
    val surfaces = model.tokenizer.vocabulary.sortBy(_.id.intValue).flatMap(token => scheme.decode(token.encoded).surface.map(_.text)) ++
      model.tokenizer.addedTokens.map(_.content)
    surfaces.flatMap(surface => CodePoint.sequence(surface).distinct.filter(UnicodeData.isHanKanji).map(_ -> surface))
      .groupMap(_._1)(_._2).toVector.sortBy((cp, tokens) => (-tokens.size, cp.intValue))

  def fromText(text: String): Vector[(CodePoint, Vector[String])] =
    CodePoint.sequence(text).distinct.filter(UnicodeData.isHanKanji).map(_ -> Vector.empty)

  def json(judgment: HanJudgment, tokens: Option[Vector[String]]): Json =
    def glyphs(values: Set[CodePoint]) = Json.fromValues(values.toVector.sortBy(_.intValue).map(cp => Json.fromString(cp.text)))
    val usage = judgment.lexicon
    Json.obj("codePoint" -> Json.fromString(judgment.character.notation), "glyph" -> Json.fromString(judgment.character.text),
      "japaneseReference" -> Json.fromValues(judgment.referenceSources.toVector.sorted.map(Json.fromString)),
      "joyo" -> Json.fromBoolean(judgment.joyo),
      "nameKanji" -> judgment.nameKanji.fold(Json.Null)(category => Json.fromString(category.toString)),
      "simplifiedForm" -> Json.fromBoolean(judgment.simplifiedForm), "traditionalVariants" -> glyphs(judgment.traditionalVariants),
      "traditionalForm" -> Json.fromBoolean(judgment.traditionalForm), "simplifiedVariants" -> glyphs(judgment.simplifiedVariants),
      "jmdict" -> Json.obj("headwords" -> Json.fromInt(usage.headwords), "standard" -> Json.fromInt(usage.standard),
        "priority" -> Json.fromInt(usage.priority), "examples" -> Json.fromValues(usage.examples.map(Json.fromString))))
      .mapObject(fields => tokens.fold(fields)(found => fields.add("tokens", Json.obj("count" -> Json.fromInt(found.size),
        "examples" -> Json.fromValues(found.take(8).map(Json.fromString))))))

  /** Write `han-profile.jsonl` and `han-profile.json` into a new run directory and return the summary. */
  def write(characters: Vector[(CodePoint, Vector[String])], withTokens: Boolean, references: HanReferences, inputs: Json,
      runDirectory: Path, protectedRoots: Vector[Path], options: ProcessingOptions): Json =
    val directory = ProcessingIO.createDirectory(options.outputRoot, runDirectory, protectedRoots)
    val judged = characters.map((cp, tokens) => (references.judge(cp), tokens))
    ProcessingIO.lines(directory.resolve("han-profile.jsonl"), judged.iterator.map((j, tokens) => json(j, Option.when(withTokens)(tokens))))
    def count(predicate: HanJudgment => Boolean) = Json.fromInt(judged.count((j, _) => predicate(j)))
    val summary = Json.obj("runDirectory" -> Json.fromString(directory.toString), "characters" -> Json.fromInt(judged.size),
      "inJapaneseReference" -> count(_.inJapaneseReference), "dailyJapanese" -> count(_.dailyJapanese),
      "simplifiedForms" -> count(_.simplifiedForm), "traditionalForms" -> count(_.traditionalForm),
      "inJMdict" -> count(_.lexicon.headwords > 0), "inJMdictStandard" -> count(_.lexicon.standard > 0),
      "inJMdictPriority" -> count(_.lexicon.priority > 0), "inputs" -> inputs)
    ProcessingIO.write(directory.resolve("han-profile.json"), summary)
    summary
