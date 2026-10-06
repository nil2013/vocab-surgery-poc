package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.{Files, LinkOption, Path}
import java.util.UUID
import scala.util.control.NonFatal
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.model.*
import me.nsmr.vocabsurgery.tokenizer.*

/** Injectable platform operations allow deterministic capacity / failure tests. */
final case class ProcessingOptions(outputRoot: Path = Path.of("outputs"), transfer: FileTransfer = FileTransfer.platform,
    bufferBytes: Int = BinaryTransfer.DefaultBufferBytes, reserveBytes: Long = 64L * 1024 * 1024,
    availableBytes: Path => Long = path => Files.getFileStore(path).getUsableSpace,
    progress: String => Unit = _ => ()):
  require(bufferBytes > 0 && bufferBytes <= FileIO.MaxTransferBuffer, "invalid buffer size")
  require(reserveBytes >= 0, "negative capacity reserve")

final case class VerificationResult(tensors: Vector[TensorVerification], reference: Json):
  def json: Json = Json.obj("passed" -> Json.True, "comparedTensors" -> Json.fromInt(tensors.size),
    "comparedPayloadBytes" -> Json.fromLong(tensors.foldLeft(0L)((sum, tensor) => Math.addExact(sum, tensor.comparedBytes))),
    "tensors" -> Json.fromValues(tensors.map(_.json)), "referenceTokenizer" -> reference)

/** Execute a recomputed plan in staging, verify every output tensor, then publish without replacement. */
object CheckpointProcessor:
  /** External tokenizer validation must return a JSON object with `passed=true` or throw.
    * Production callers use the offline Hugging Face helper; synthetic tests supply an explicit contract.
    */
  type ReferenceValidator = (PreparedPlan, Path) => Json

  /** Save inspection metadata to a new run directory without reading weight payloads. */
  def inspect(source: Path, runDirectory: Path, options: ProcessingOptions = ProcessingOptions()): Path =
    val json = PlanReports.inspection(source)
    val directory = ProcessingIO.createDirectory(options.outputRoot, runDirectory, Vector(source))
    ProcessingIO.write(directory.resolve("inspection.json"), json)
    directory

  /** Save a recomputable plan and analysis, returning its path.
    * Accept a new run or an inspection-only run for the same source; never replace an existing plan.
    */
  def plan(source: Path, kanji: Path, settings: PolicySettings, runDirectory: Path,
      options: ProcessingOptions = ProcessingOptions()): Path =
    val prepared = PlanStore.prepare(source, kanji, settings)
    val protectedRoots = Vector(source, kanji)
    val target = ProcessingIO.outputPath(options.outputRoot, runDirectory, protectedRoots)
    val directory = if Files.exists(target) then
      val existing = InputInventory.files(target)
      Diagnostics.check(existing.map(_.getFileName.toString) == Vector("inspection.json"), target, "plan", "only an inspection-only run can be extended")
      val inspection = JsonIO.read(target.resolve("inspection.json"))
      Diagnostics.check(inspection == PlanReports.inspection(prepared.source), target, "inspection", "inspection input changed or differs from plan source")
      target
    else ProcessingIO.createDirectory(options.outputRoot, runDirectory, protectedRoots)
    PlanReports.write(prepared, directory)
    directory.resolve("plan.json")

  /** Worst-case output bytes plus reserve, without assuming any copy-on-write clone succeeds. */
  def requiredBytes(plan: PreparedPlan, reserve: Long): Long =
    val generated = outputs(plan.domain)
    val shardNames = plan.domain.shards.map(s => s.source.path.getFileName.toString).toSet
    val assets = plan.inputs.filterNot(file => shardNames(file.name) || generated.contains(file.name)).foldLeft(0L)((sum, file) => Math.addExact(sum, file.bytes))
    val headers = plan.domain.shards.foldLeft(0L)((sum, shard) => Math.addExact(sum,
      if shard.changed then SafetensorsIO.encodeHeaderJson(shard.header).length.toLong else shard.source.payloadStart.longValue))
    val generatedBytes = generated.values.foldLeft(0L)((sum, file) => Math.addExact(sum, file.content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length.toLong))
    Vector(plan.domain.payloadBytes.longValue, headers, assets, generatedBytes, reserve).foldLeft(0L)(Math.addExact)

  /** Recompute the saved plan, create staging files, verify all bytes and publish a new model.
    * Write manifest and verification beside the plan; publish without replacing an existing destination.
    * Failures after staging begins retain staging and a diagnostic rather than a completed model.
    * @return the validated absolute output path
    */
  def applyPlan(planPath: Path, modelOutput: Path, referenceValidator: ReferenceValidator,
      options: ProcessingOptions = ProcessingOptions()): Path =
    val prepared = PlanStore.loadVerified(planPath)
    val inputs = Vector(prepared.source.directory, prepared.kanjiDirectory)
    val target = ProcessingIO.outputPath(options.outputRoot, modelOutput, inputs)
    val run = planPath.toAbsolutePath.normalize().getParent
    ProcessingIO.outputPath(options.outputRoot, run, inputs)
    Diagnostics.check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), target, "publish", "model output already exists")
    Vector("manifest.json", "verification.json", "publication.json").foreach(name =>
      Diagnostics.check(!Files.exists(run.resolve(name), LinkOption.NOFOLLOW_LINKS), run, "apply", s"$name already exists; use a fresh run"))
    var storePath = options.outputRoot.toAbsolutePath.normalize()
    while !Files.exists(storePath) do storePath = storePath.getParent
    val needed = requiredBytes(prepared, options.reserveBytes)
    Diagnostics.check(options.availableBytes(storePath) >= needed, target, "capacity", s"need at least $needed free bytes for copy fallback and reserve")
    val stage = ProcessingIO.createDirectory(options.outputRoot, options.outputRoot.resolve(".staging").resolve(UUID.randomUUID().toString), inputs)
    options.progress(s"Staging: $stage; worst-case bytes: $needed")
    try
      val methods = scala.collection.mutable.Map.empty[String, String]
      val generated = outputs(prepared.domain)
      val shards = prepared.domain.shards.map(s => s.source.path.getFileName.toString -> s).toMap
      outputNames(prepared).foreach { name =>
        options.progress(s"Writing $name")
        val out = ProcessingIO.relative(stage, name)
        Files.createDirectories(out.getParent)
        generated.get(name) match
          case Some(file) => FileIO.writeNewUtf8(out, file.content); methods(name) = "generated"
          case None => shards.get(name) match
            case Some(shard) if shard.changed => BinaryTransfer.rewrite(shard, out, options.bufferBytes); methods(name) = "row-projection"
            case _ => methods(name) = BinaryTransfer.copyFile(ProcessingIO.relative(prepared.source.directory, name), out, options.transfer, options.bufferBytes)
      }
      val verification = verifyModel(prepared, stage, referenceValidator, options)
      Diagnostics.check(InputInventory.capture(prepared.source) == prepared.inputs, planPath, "inputs", "source changed during apply")
      val inputHashes = prepared.inputs.map { file =>
        options.progress(s"Hashing input ${file.name}")
        file.name -> Json.fromString(FileIO.sha256(ProcessingIO.relative(prepared.source.directory, file.name)))
      }
      val outputFiles = outputNames(prepared).map { name =>
        options.progress(s"Hashing output $name")
        val path = ProcessingIO.relative(stage, name)
        Json.obj("name" -> Json.fromString(name), "bytes" -> Json.fromLong(Files.size(path)), "sha256" -> Json.fromString(FileIO.sha256(path)), "method" -> Json.fromString(methods(name)))
      }
      Diagnostics.check(InputInventory.capture(prepared.source) == prepared.inputs, planPath, "inputs", "source changed while hashing")
      val manifest = Json.obj("plan" -> Json.fromString(planPath.toAbsolutePath.normalize().toString), "planSha256" -> Json.fromString(FileIO.sha256(planPath)),
        "source" -> Json.fromString(prepared.source.directory.toString), "family" -> Json.fromString(prepared.domain.family.name),
        "modelOutput" -> Json.fromString(target.toString), "policySha256" -> Json.fromString(ProcessingIO.signature(prepared.settings.json)),
        "originalRows" -> Json.fromInt(prepared.domain.projection.originalSize), "compactRows" -> Json.fromInt(prepared.domain.projection.compactSize),
        "payloadBytes" -> Json.fromLong(prepared.domain.payloadBytes.longValue), "sourceFileHashes" -> Json.obj(inputHashes*),
        "files" -> Json.fromValues(outputFiles), "verification" -> verification.json)
      ProcessingIO.write(run.resolve("verification.json"), verification.json)
      ProcessingIO.write(run.resolve("manifest.json"), manifest)
      Files.createDirectories(target.getParent)
      options.transfer.publishNew(stage, target)
      ProcessingIO.write(run.resolve("publication.json"), Json.obj("modelOutput" -> Json.fromString(target.toString), "publishedAt" -> Json.fromString(java.time.Instant.now().toString)))
      target
    catch
      case NonFatal(error) =>
        val diagnostic = stage.resolve("failure.json")
        if Files.isDirectory(stage) && !Files.exists(diagnostic) then
          try ProcessingIO.write(diagnostic, Json.obj("error" -> Json.fromString(Option(error.getMessage).getOrElse(error.getClass.getName)), "staging" -> Json.fromString(stage.toString), "modelOutput" -> Json.fromString(target.toString)))
          catch case NonFatal(_) => ()
        throw error

  /** Revalidate source identity, all manifest hashes and complete tensor byte correspondence. */
  def verifyManifest(manifestPath: Path, referenceValidator: ReferenceValidator,
      options: ProcessingOptions = ProcessingOptions()): VerificationResult =
    val manifest = JsonIO.read(manifestPath)
    def field(name: String) = JsonFields.field(manifest, name, manifestPath, "manifest")
    val planPath = Path.of(JsonFields.text(field("plan"), manifestPath, "plan"))
    Diagnostics.check(FileIO.sha256(planPath) == JsonFields.text(field("planSha256"), manifestPath, "planSha256"), manifestPath, "planSha256", "plan changed since publication")
    val prepared = PlanStore.loadVerified(planPath)
    Diagnostics.check(JsonFields.text(field("source"), manifestPath, "source") == prepared.source.directory.toString &&
      JsonFields.int(field("originalRows"), manifestPath, "originalRows") == prepared.domain.projection.originalSize &&
      JsonFields.int(field("compactRows"), manifestPath, "compactRows") == prepared.domain.projection.compactSize &&
      JsonFields.long(field("payloadBytes"), manifestPath, "payloadBytes") == prepared.domain.payloadBytes.longValue &&
      JsonFields.text(field("policySha256"), manifestPath, "policySha256") == ProcessingIO.signature(prepared.settings.json),
      manifestPath, "manifest", "manifest dimensions, source or policy differ from plan")
    val output = Path.of(JsonFields.text(field("modelOutput"), manifestPath, "modelOutput"))
    ProcessingIO.outputPath(options.outputRoot, output, Vector(prepared.source.directory, prepared.kanjiDirectory))
    val expectedInputs = JsonFields.obj(field("sourceFileHashes"), manifestPath, "sourceFileHashes")
    Diagnostics.check(expectedInputs.keys.toSet == prepared.inputs.map(_.name).toSet, manifestPath, "sourceFileHashes", "source hash set differs from plan")
    prepared.inputs.foreach { file =>
      options.progress(s"Checking input hash ${file.name}")
      val expected = JsonFields.text(expectedInputs(file.name).get, manifestPath, file.name)
      Diagnostics.check(FileIO.sha256(ProcessingIO.relative(prepared.source.directory, file.name)) == expected, manifestPath, file.name, "source hash mismatch")
    }
    val entries = JsonFields.array(field("files"), manifestPath, "files")
    val names = entries.map(entry => JsonFields.text(JsonFields.field(entry, "name", manifestPath, "files"), manifestPath, "name"))
    Diagnostics.check(names.distinct.size == names.size && names.toSet == outputNames(prepared).toSet, manifestPath, "files", "file inventory differs from plan")
    entries.zip(names).foreach { (entry, name) =>
      options.progress(s"Checking output hash $name")
      val path = ProcessingIO.relative(output, name)
      Diagnostics.check(!Files.isSymbolicLink(path) && Files.isRegularFile(path), path, "output", "expected independent regular file")
      val bytes = JsonFields.long(JsonFields.field(entry, "bytes", manifestPath, "files"), manifestPath, "bytes")
      val hash = JsonFields.text(JsonFields.field(entry, "sha256", manifestPath, "files"), manifestPath, "sha256")
      Diagnostics.check(Files.size(path) == bytes && FileIO.sha256(path) == hash, path, "output hash", "output bytes or hash changed")
    }
    verifyModel(prepared, output, referenceValidator, options)

  private def verifyModel(prepared: PreparedPlan, output: Path, referenceValidator: ReferenceValidator, options: ProcessingOptions): VerificationResult =
    val model = SnapshotLoader.load(output)
    val generated = outputs(prepared.domain)
    Diagnostics.check(InputInventory.files(output).map(output.relativize(_).toString).toSet == outputNames(prepared).toSet,
      output, "inventory", "unexpected or missing model files")
    generated.foreach { (name, expected) =>
      Diagnostics.check(FileIO.readUtf8(output.resolve(name)) == expected.content, output.resolve(name), "generated file", "content differs from plan")
    }
    val registered = (model.tokenizer.vocabulary.map(_.id.intValue) ++ model.tokenizer.addedTokens.map(_.id.intValue)).toSet
    Diagnostics.check(registered == (0 until prepared.domain.projection.compactSize).toSet, output, "IDs", "IDs must be contiguous and match tensor rows")
    val family = model.config.family
    BpeGraph.from(model.tokenizer, family.tokenizer)
    TokenReferences.requiredIds(model.config.original, family.tokenIdScopes, family.tokenIdFields)
    TokenReferences.requiredIds(model.generation)
    TokenReferences.requiredTokenizerIds(model.tokenizer.original, model.tokenizerConfig)
    prepared.inputs.foreach { file =>
      val path = ProcessingIO.relative(output, file.name)
      Diagnostics.check(!Files.isSymbolicLink(path) && !Files.isSameFile(path, ProcessingIO.relative(prepared.source.directory, file.name)), path, "output", "output depends on input inode or symlink")
      if !generated.contains(file.name) && !file.headerOnly then
        Diagnostics.check(FileIO.sha256(path) == file.sha256, path, "asset", "copied asset differs from source")
    }
    val tensors = prepared.domain.shards.flatMap { shard =>
      options.progress(s"Comparing every tensor in ${shard.source.path.getFileName}")
      BinaryTransfer.verify(shard, output.resolve(shard.source.path.getFileName), options.bufferBytes)
    }
    val reference = referenceValidator(prepared, output)
    Diagnostics.check(reference.hcursor.get[Boolean]("passed").contains(true), output, "reference tokenizer", "reference validation did not pass")
    VerificationResult(tensors, reference)

  private def outputs(plan: ModelUpdatePlan): Map[String, OutputFile] = plan.outputFiles.map(file => file.name -> file).toMap

  /** Every source file, plus generated files the source lacks, in a stable order. */
  private def outputNames(plan: PreparedPlan): Vector[String] =
    (plan.inputs.map(_.name) ++ plan.domain.outputFiles.map(_.name)).distinct.sorted
