package me.nsmr.vocabsurgery.processing

import io.circe.{Json, JsonObject}
import java.nio.file.Path
import me.nsmr.vocabsurgery.io.{Diagnostics, JsonFields, JsonIO}
import me.nsmr.vocabsurgery.model.ParameterCountPolicy
import me.nsmr.vocabsurgery.tokenizer.{PruningPolicy, VocabularyAnalysis}

/** A policy loaded with its reference inputs. */
trait LoadedPolicy:
  def policy: PruningPolicy
  /** Identity of everything the policy read besides its settings; a saved plan stays valid only while this is equal. */
  def fingerprint: Json
  /** Policy-specific analysis files in the run directory. */
  def writeReports(analysis: VocabularyAnalysis, directory: Path): Unit = ()
  /** Policy-specific figures added to `summary.json`. */
  def summary(analysis: VocabularyAnalysis): Json = Json.obj()

/** One policy kind's settings as saved in a policy file and a plan. */
trait PolicySettings:
  def kind: PolicyKind
  /** Kind-specific fields, without `kind` and `parameterCount`. */
  def json: Json
  /** Files and directories the policy reads. They are fingerprinted, and outputs may not overlap them. */
  def inputs: Vector[Path]
  /** The same settings with reference paths made real, so a saved plan names the inputs it was computed from. */
  def resolved: PolicySettings
  def load(): LoadedPolicy

/** Reads one kind's fields; unknown fields are rejected. */
trait PolicyKind:
  def name: String
  def parse(fields: JsonObject, path: Path): PolicySettings

/** A policy JSON file: `kind` selects the strategy, `parameterCount` how index parameter counts are updated. */
final case class PolicyFile(settings: PolicySettings, parameterCount: ParameterCountPolicy = ParameterCountPolicy.OmitUnverified):
  def json: Json = settings.json.mapObject(_.add("kind", Json.fromString(settings.kind.name))
    .add("parameterCount", Json.fromString(PolicyFile.parameterCountName(parameterCount))))
  def resolved: PolicyFile = copy(settings = settings.resolved)

object PolicyFile:
  val kinds: Vector[PolicyKind] = Vector(CharacterPolicySettings)
  def default: PolicyFile = PolicyFile(CharacterPolicySettings())

  def load(path: Path): PolicyFile = parse(JsonIO.read(path), path)

  def parse(json: Json, path: Path): PolicyFile = Diagnostics.guard(path, "policy") {
    val fields = JsonFields.obj(json, path, "policy")
    val name = JsonFields.text(JsonFields.field(json, "kind", path, "policy"), path, "kind")
    val kind = kinds.find(_.name == name).getOrElse(Diagnostics.invalid(path, "kind", s"unknown policy kind $name; known: ${kinds.map(_.name).mkString(", ")}"))
    val parameters = fields("parameterCount").map(JsonFields.text(_, path, "parameterCount")).getOrElse("omit-unverified") match
      case "omit-unverified" => ParameterCountPolicy.OmitUnverified
      case "verified-logical-weights" => ParameterCountPolicy.VerifiedLogicalWeights
      case _ => Diagnostics.invalid(path, "parameterCount", "unsupported parameter-count policy")
    PolicyFile(kind.parse(fields.remove("kind").remove("parameterCount"), path), parameters)
  }

  private def parameterCountName(policy: ParameterCountPolicy): String = policy match
    case ParameterCountPolicy.OmitUnverified => "omit-unverified"
    case ParameterCountPolicy.VerifiedLogicalWeights => "verified-logical-weights"
