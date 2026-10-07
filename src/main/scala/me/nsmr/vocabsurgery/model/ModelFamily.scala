package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.tokenizer.{ByteLevelBpe, TokenizerScheme, VocabularyProjection}

/** Object keys leading to a value inside a JSON document; empty for the document itself. */
final case class JsonPath(keys: Vector[String]):
  def /(key: String): JsonPath = JsonPath(keys :+ key)
  def text: String = if keys.isEmpty then "$" else keys.mkString(".")
  def get(json: Json): Option[Json] = keys.foldLeft(Option(json))((node, key) => node.flatMap(_.asObject).flatMap(_(key)))

  /** Transform an existing value in place, keeping every sibling field and its order. */
  def modify(json: Json)(f: Json => Json): Json =
    def descend(node: Json, rest: List[String]): Json = rest match
      case Nil => f(node)
      case key :: tail =>
        val fields = DomainJson.fields(node, text)
        val child = fields(key).getOrElse(DomainChecks.fail("missing-json-field", text, "path is absent"))
        Json.fromJsonObject(fields.add(key, descend(child, tail)))
    descend(json, keys.toList)

object JsonPath:
  val root: JsonPath = JsonPath(Vector.empty)
  def apply(first: String, rest: String*): JsonPath = JsonPath((first +: rest).toVector)

/** A file the surgery writes from planned content instead of copying it from the source. */
enum OutputFile:
  case JsonFile(name: String, value: Json)
  case TextFile(name: String, text: String)
  def name: String
  /** Exact file content; JSON uses the sorted-key encoding shared by every generated artifact. */
  def content: String = this match
    case JsonFile(_, value) => OutputFile.printer.print(value) + "\n"
    case TextFile(_, text) => text

object OutputFile:
  private val printer = io.circe.Printer.noSpaces.copy(sortKeys = true)

/** What differs between model families' checkpoints. Tokenizer cleanup, row projection, binary transfer and
  * verification are shared; supporting a new family means describing these points and registering it in `all`.
  */
trait ModelFamily:
  def name: String
  /** Whether a checkpoint with this `config.json` belongs to the family. */
  def accepts(config: Json): Boolean
  /** Every place `config.json` records the vocabulary row count. All must agree; all are rewritten. */
  def vocabSize: Vector[JsonPath]
  def hiddenSize: JsonPath
  /** Objects inside `config.json` whose fields may hold token IDs. Elsewhere such fields are rejected. */
  def tokenIdScopes: Vector[JsonPath]
  /** Single-ID fields beyond the common Hugging Face ones; a pruned ID here stops the plan. */
  def tokenIdFields: Set[String] = Set.empty
  /** Tensor-name prefixes of the modules whose axis 0 is the vocabulary. Unsupported layouts fail here. */
  def vocabularyModules(config: Json): Vector[String]
  def tokenizer: TokenizerScheme = ByteLevelBpe
  /** Tokens the family's protocol needs as single IDs, besides those its tokenizer metadata and template name. */
  def requiredTokens: Set[String]
  /** JSON files the family writes into the pruned model. A source copy of one is read for `familyFiles`, not copied. */
  def familyFileNames: Set[String] = Set.empty
  /** Contents of the family's files for the pruned model, given the source `config.json`, the source copies and the rows kept. */
  def familyFiles(config: Json, sourceFiles: Map[String, Json], projection: VocabularyProjection): Vector[OutputFile] = Vector.empty

object ModelFamily:
  val all: Vector[ModelFamily] = Vector(CoderNext, FlashNext)

  def of(config: Json): ModelFamily =
    all.filter(_.accepts(config)) match
      case Vector(family) => family
      case Vector() => DomainChecks.fail("unsupported-model", "config",
        s"no model family accepts model_type ${config.hcursor.get[String]("model_type").getOrElse("<absent>")}; supported: ${all.map(_.name).mkString(", ")}")
      case several => DomainChecks.fail("ambiguous-model", "config", several.map(_.name).mkString(", "))

  private[model] def modelType(config: Json): Option[String] = config.hcursor.get[String]("model_type").toOption

  /** Separate input-embedding and output-head rows; a shared matrix would need a different module list. */
  private[model] def requireUntied(config: Json, paths: JsonPath*): Unit =
    paths.foreach(path => DomainChecks.check(path.get(config).forall(_ == Json.False), "unsupported-model", path.text,
      "expected separate embedding and LM head"))
