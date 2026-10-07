package me.nsmr.vocabsurgery.runtime

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO, JsonFields, JsonIO}
import me.nsmr.vocabsurgery.model.{PleTokenIds, TokenizerData, TokenizerLoader}
import me.nsmr.vocabsurgery.processing.{ProcessingIO, ProcessingOptions}
import me.nsmr.vocabsurgery.tokenizer.ByteLevel

/** A runtime whose MTP drafts propose only the upstream token ids of a list, which can be replaced at serve time. */
trait DraftList:
  /** The list the runtime uses unless another is given. */
  def builtinDraftList: Path

/** A token named in a draft vocabulary profile: an upstream id, or text that is an added token's content, a vocabulary
  * key, or encodes to exactly one id.
  */
enum TokenName:
  case Id(value: Int)
  case Text(value: String)

/** The rules for rebuilding a draft list; the corpus is given separately. `base` None is the runtime's built-in list.
  * Candidates are tokens the corpus generates at least `minCount` times (or `add`); they replace base rows the corpus
  * never generates, highest id first. `protect` (and added tokens when `protectAddedTokens`) never leave.
  */
final case class DraftVocabularyProfile(base: Option[Path], minCount: Int, protectAddedTokens: Boolean, protect: Vector[TokenName],
    add: Vector[TokenName], remove: Vector[TokenName])

object DraftVocabularyProfile:
  /** Read a JSON profile with defaults: base "builtin", select.minCount 2, replace from "unused" in order "highest-id",
    * protect.addedTokens true. Unknown keys and other replace rules are rejected.
    */
  def load(path: Path): DraftVocabularyProfile = Diagnostics.guard(path, "draft vocabulary profile") {
    val root = JsonIO.read(path)
    def section(name: String, known: Set[String]): Map[String, Json] =
      val fields = root.hcursor.downField(name).focus.filterNot(_.isNull).fold(Map.empty[String, Json])(value =>
        JsonFields.obj(value, path, name).toMap)
      Diagnostics.check(fields.keySet.subsetOf(known), path, name, s"unknown keys ${(fields.keySet -- known).toVector.sorted.mkString(", ")}")
      fields
    val top = JsonFields.obj(root, path, "profile").keys.toSet
    val allowed = Set("base", "select", "replace", "protect", "force")
    Diagnostics.check(top.subsetOf(allowed), path, "profile", s"unknown keys ${(top -- allowed).toVector.sorted.mkString(", ")}")
    val select = section("select", Set("minCount"))
    val replace = section("replace", Set("from", "order"))
    Diagnostics.check(replace.get("from").forall(_ == Json.fromString("unused")) && replace.get("order").forall(_ == Json.fromString("highest-id")),
      path, "replace", "supported: from unused, order highest-id")
    val protect = section("protect", Set("addedTokens", "tokens"))
    val force = section("force", Set("add", "remove"))
    def names(fields: Map[String, Json], name: String): Vector[TokenName] =
      fields.get(name).fold(Vector.empty)(value => JsonFields.array(value, path, name).map { entry =>
        entry.asString.map(TokenName.Text(_)).getOrElse(TokenName.Id(JsonFields.int(entry, path, name)))
      })
    val base = root.hcursor.downField("base").focus.map(JsonFields.text(_, path, "base")).filterNot(_ == "builtin").map(Path.of(_))
    val minCount = select.get("minCount").fold(2)(JsonFields.int(_, path, "select.minCount"))
    Diagnostics.check(minCount >= 1, path, "select.minCount", "must be positive")
    DraftVocabularyProfile(base, minCount, protect.get("addedTokens").forall(JsonFields.bool(_, path, "protect.addedTokens")),
      names(protect, "tokens"), names(force, "add"), names(force, "remove"))
  }

/** Token ids from the reference tokenizer, without special tokens. */
trait ReferenceTokenization:
  /** How often each id occurs over the encodings of `texts`. */
  def counts(texts: Vector[String]): Map[Int, Long]
  def encode(texts: Vector[String]): Vector[Vector[Int]]

/** The rebuilt list (sorted upstream ids) and the ids swapped in and out. */
final case class DraftSwap(ids: Vector[Int], added: Vector[Int], dropped: Vector[Int])

/** Rebuilding an MTP draft list from a profile and a neutral corpus while keeping its number of rows. */
object DraftVocabulary:
  private val MaxCorpusBytes = 1L << 30

  /** Distinct non-blank texts of neutral corpus files (one {"text": ...} per line), in file order. */
  def corpus(paths: Vector[Path]): Vector[String] =
    val parser = io.circe.jawn.JawnParser(allowDuplicateKeys = false)
    val texts = paths.iterator.flatMap { path =>
      FileIO.readUtf8(path, MaxCorpusBytes).split("\r\n|\n|\r", -1).iterator.zipWithIndex.filter(_._1.trim.nonEmpty).map { (line, index) =>
        val at = s"line ${index + 1}"
        val row = parser.parse(line).fold(e => Diagnostics.invalid(path, at, e.message), identity)
        row.hcursor.get[String]("text").toOption.getOrElse(Diagnostics.invalid(path, at, """expected {"text": string}"""))
      }
    }.filter(_.trim.nonEmpty).toVector
    texts.distinct

  /** The swapped list over the `kept` ids; its row count stays that of the kept base rows.
    *
    * Candidates enter forced additions first, then most frequent first, then by id; they take the rows of forced
    * removals, then base rows the corpus never generates, highest id first. Protected rows never leave.
    */
  def swap(size: Int, counts: Map[Int, Long], base: Vector[Int], kept: Int => Boolean, protect: Set[Int], minCount: Int,
      add: Set[Int] = Set.empty, remove: Set[Int] = Set.empty): DraftSwap =
    require((base ++ add ++ remove ++ protect).forall(id => id >= 0 && id < size), "token id outside the vocabulary")
    require(!add.exists(remove) && !remove.exists(protect) && add.forall(kept),
      "force.add and force.remove overlap, remove a protected token, or add a token the model drops")
    val count = (id: Int) => counts.getOrElse(id, 0L)
    val baseKept = base.distinct.filter(kept).toSet
    val listed = baseKept -- remove
    val wanted = (counts.keySet.filter(count(_) >= minCount) ++ add).filter(id => id < size && !listed(id) && kept(id) && !remove(id))
      .toVector.sortBy(id => (!add(id), -count(id), id))
    val droppable = listed.filter(id => count(id) == 0 && !protect(id) && !add(id)).toVector.sorted.reverse
    val room = baseKept.size - listed.size
    val n = math.min(wanted.size, droppable.size + room)
    require(!wanted.drop(n).exists(add), "not enough unused rows to make room for force.add")
    val added = wanted.take(n).sorted
    val dropped = droppable.take(math.max(0, n - room)).sorted
    DraftSwap(((listed ++ added) -- dropped).toVector.sorted, added, dropped)

  /** The share of the counted tokens that `ids` cover. */
  def coverage(counts: Map[Int, Long], ids: Iterable[Int]): Double =
    val total = counts.values.sum
    ids.iterator.map(counts.getOrElse(_, 0L)).sum.toDouble / math.max(total, 1L).toDouble

  /** Upstream ids of profile names: added-token content, then vocabulary keys, then texts encoding to one id. */
  def resolve(names: Vector[TokenName], tokenizer: TokenizerData, reference: ReferenceTokenization, what: String): Vector[Int] =
    val known = tokenizer.addedTokens.map(t => t.content -> t.id.intValue).toMap ++
      tokenizer.vocabulary.map(t => t.encoded -> t.id.intValue).filterNot((key, _) => tokenizer.addedTokens.exists(_.content == key))
    val texts = names.collect { case TokenName.Text(text) if !known.contains(text) => text }.distinct
    val encoded = if texts.isEmpty then Map.empty[String, Vector[Int]] else texts.zip(reference.encode(texts)).toMap
    names.map {
      case TokenName.Id(value) => value
      case TokenName.Text(text) => known.getOrElse(text, encoded(text) match
        case Vector(one) => one
        case _ => throw IllegalArgumentException(s"$what: ${Json.fromString(text).noSpaces} is not one token"))
    }

  /** The text of one token id: an added token's content, otherwise its ByteLevel bytes with invalid UTF-8 replaced. */
  def surface(tokenizer: TokenizerData, id: Int): String =
    tokenizer.addedTokens.find(_.id.intValue == id).map(_.content).getOrElse {
      val key = tokenizer.vocabulary.find(_.id.intValue == id).map(_.encoded).getOrElse(throw IllegalArgumentException(s"no token $id"))
      String(ByteLevel.decode(key).bytes.toArray, StandardCharsets.UTF_8)
    }

  /** Rebuild `runtime`'s draft list (or the profile's base) for `source`'s vocabulary, bounded by the tokens `model`
    * keeps when given (its PLE table), and write it to `output` as sorted upstream ids, one per line.
    */
  def rebuild(profile: DraftVocabularyProfile, profilePath: Path, corpusPaths: Vector[Path], source: Path, runtime: DraftList,
      model: Option[Path], reference: ReferenceTokenization, output: Path, options: ProcessingOptions = ProcessingOptions()): Json =
    val tokenizer = TokenizerLoader.load(source.resolve("tokenizer.json"))
    val size = (tokenizer.vocabulary.map(_.id.intValue) ++ tokenizer.addedTokens.map(_.id.intValue)).max + 1
    val texts = corpus(corpusPaths)
    Diagnostics.check(texts.nonEmpty, corpusPaths.headOption.getOrElse(source), "corpus", "no texts")
    val counts = reference.counts(texts)
    val basePath = profile.base.getOrElse(runtime.builtinDraftList)
    val base = FileIO.readUtf8(basePath).split("\\s+").iterator.filter(_.nonEmpty).map(text =>
      text.toIntOption.filter(id => id >= 0 && id < size).getOrElse(Diagnostics.invalid(basePath, "draft list", s"invalid token id $text"))).toVector.distinct.sorted
    val kept: Int => Boolean = model.fold((_: Int) => true) { directory =>
      val table = PleTokenIds.parse(JsonIO.read(directory.resolve(PleTokenIds.FileName)))
      table.originalTokenIds.toSet
    }
    val protect = (if profile.protectAddedTokens then tokenizer.addedTokens.map(_.id.intValue) else Vector.empty) ++
      resolve(profile.protect, tokenizer, reference, "protect.tokens")
    val result = swap(size, counts, base, kept, protect.toSet, profile.minCount,
      resolve(profile.add, tokenizer, reference, "force.add").toSet, resolve(profile.remove, tokenizer, reference, "force.remove").toSet)
    val target = ProcessingIO.outputPath(options.outputRoot, output, model.toVector :+ source)
    Files.createDirectories(target.getParent)
    FileIO.writeNewUtf8(target, result.ids.mkString("", "\n", "\n"))
    val examples = result.added.sortBy(id => -counts.getOrElse(id, 0L)).take(40).map(id => Json.fromString(surface(tokenizer, id)))
    Json.obj("output" -> Json.fromString(target.toString), "profile" -> Json.fromString(profilePath.toString),
      "corpus" -> Json.fromValues(corpusPaths.map(p => Json.fromString(p.toString))), "texts" -> Json.fromInt(texts.size),
      "tokens" -> Json.fromLong(counts.values.sum), "base" -> Json.fromInt(base.size), "baseKept" -> Json.fromInt(base.count(kept)),
      "rows" -> Json.fromInt(result.ids.size), "swapped" -> Json.fromInt(result.added.size),
      "protectedInBase" -> Json.fromInt(base.count(id => kept(id) && protect.contains(id))), "added" -> Json.fromValues(examples),
      "droppedHighestIds" -> Json.fromValues(result.dropped.takeRight(10).map(Json.fromInt)),
      "coverage" -> Json.obj("base" -> Json.fromDoubleOrNull(coverage(counts, base.filter(kept))),
        "swapped" -> Json.fromDoubleOrNull(coverage(counts, result.ids))))
