package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.model.*

/** `policy` has real reference paths; `loadedPolicy` is what they produced. */
final case class PreparedPlan(source: ModelSnapshot, domain: ModelUpdatePlan, policy: PolicyFile, loadedPolicy: LoadedPolicy, inputs: Vector[InputFile]):
  def description: Json = PlanStore.describe(domain)
  /** Directories outputs must not overlap: the checkpoint and every policy input. */
  def protectedRoots: Vector[Path] = source.directory +: policy.settings.inputs
  def json: Json = Json.obj("source" -> Json.fromString(source.directory.toString), "policy" -> policy.json,
    "policyInputs" -> loadedPolicy.fingerprint, "inputs" -> Json.fromValues(inputs.map(_.json)), "domain" -> description,
    "domainSha256" -> Json.fromString(ProcessingIO.signature(description)))

/** Saved plans are validated by recomputation, never executed as arbitrary serialized byte ranges. */
object PlanStore:
  /** Load metadata, fingerprint input structure and compute domain operations without reading tensor payloads. */
  def prepare(source: Path, policy: PolicyFile): PreparedPlan =
    val model = SnapshotLoader.load(source.toRealPath())
    val resolved = policy.resolved
    val loaded = resolved.settings.load()
    val domain = ModelOperations.plan(model, loaded.policy, resolved.parameterCount)
    PreparedPlan(model, domain, resolved, loaded, InputInventory.capture(model))

  /** Recompute operations from current inputs and reject any inventory, reference or saved-plan mismatch. */
  def loadVerified(path: Path): PreparedPlan =
    val json = JsonIO.read(path)
    def field(name: String) = JsonFields.field(json, name, path, "plan")
    val expectedInputs = InputInventory.parse(field("inputs"), path)
    val source = Path.of(JsonFields.text(field("source"), path, "source"))
    val current = prepare(source, PolicyFile.parse(field("policy"), path))
    Diagnostics.check(current.inputs == expectedInputs, path, "inputs", "source files changed since planning")
    Diagnostics.check(current.loadedPolicy.fingerprint == field("policyInputs"), path, "policyInputs", "policy inputs changed since planning")
    val expected = field("domain")
    Diagnostics.check(ProcessingIO.signature(expected) == JsonFields.text(field("domainSha256"), path, "domainSha256"), path, "domainSha256", "saved plan digest mismatch")
    Diagnostics.check(current.description == expected, path, "domain", "saved plan differs from recomputed operations")
    current

  def describe(plan: ModelUpdatePlan): Json = Json.obj("family" -> Json.fromString(plan.family.name),
    "originalRows" -> Json.fromInt(plan.projection.originalSize), "compactRows" -> Json.fromInt(plan.projection.compactSize),
    "originalToCompact" -> plan.projection.originalToCompactJson, "compactToOriginal" -> plan.projection.compactToOriginalJson,
    "directDeleted" -> ids(plan.analysis.cleanup.directDeleted), "indirectDeleted" -> ids(plan.analysis.cleanup.indirectDeleted),
    "unregisteredRows" -> ids(plan.analysis.unregisteredIds), "removedMergeRanks" -> Json.fromValues(plan.analysis.cleanup.removedRanks.map(Json.fromInt)),
    "unreachableProtected" -> ids(plan.analysis.cleanup.unreachableProtected),
    "savedPayloadBytes" -> Json.fromLong(plan.savedPayloadBytes.longValue),
    "outputFiles" -> Json.obj(plan.outputFiles.map(file => file.name -> Json.fromString(ProcessingIO.sha256(file.content)))*),
    "shards" -> Json.fromValues(plan.shards.map { shard => Json.obj("name" -> Json.fromString(shard.source.path.getFileName.toString),
      "changed" -> Json.fromBoolean(shard.changed), "header" -> shard.header, "payloadBytes" -> Json.fromLong(shard.payloadBytes.longValue)) }),
    "notices" -> Json.fromValues(plan.notices.map(notice => Json.obj("location" -> Json.fromString(notice.location), "reason" -> Json.fromString(notice.reason), "before" -> notice.before, "after" -> notice.after.getOrElse(Json.Null)))))

  private def ids(values: Set[OriginalTokenId]): Json = Json.fromValues(values.toVector.sortBy(_.intValue).map(id => Json.fromInt(id.intValue)))
