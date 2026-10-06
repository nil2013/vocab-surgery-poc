package me.nsmr.vocabsurgery.cli

import io.circe.Json
import java.io.PrintStream
import java.nio.file.{Files, Path}
import scala.util.control.NonFatal
import scopt.{DefaultOEffectSetup, OParser}
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.processing.*

/** Parsed CLI values; required-option and count validation belongs to the scopt command schema. */
final case class Arguments(command: String = "", source: Path = Path.of("."), runDirectory: Path = Path.of("."),
    kanji: Path = Path.of("kanji-data"), policy: Option[Path] = None, plan: Path = Path.of("."), modelOutput: Path = Path.of("."),
    manifest: Path = Path.of("."), python: Path = Path.of("python3"), report: Option[Path] = None, suite: Path = Path.of("."),
    url: String = "", baselineModel: String = "", prunedModel: String = "", evaluationOutput: Path = Path.of("."),
    apiKeyEnvironment: String = "OMLX_API_KEY", trials: Int = 5, warmups: Int = 1, timeout: Int = 3600, copyOnly: Boolean = false,
    serverEnvironment: Option[Path] = None)

/** Thin command dispatch; domain decisions and file mutation remain in their respective layers. */
object Main:
  private val builder = OParser.builder[Arguments]
  private val parser =
    import builder.*
    def python = opt[String]("python").required().valueName("<interpreter>").action((v, c) => c.copy(python = Path.of(v)))
      .text("Python with tokenizers and transformers installed; no packages are downloaded")
    def manifest = opt[String]("manifest").required().action((v, c) => c.copy(manifest = Path.of(v)))
    OParser.sequence(programName("vocab-surgery"), head("vocab-surgery", ProcessingIO.ToolVersion),
      help("help").text("Show command usage (also: help <command>)"),
      cmd("inspect").action((_, c) => c.copy(command = "inspect")).text("Inspect source metadata without reading weights").children(
        opt[String]("source").required().action((v, c) => c.copy(source = Path.of(v))),
        opt[String]("run-dir").required().action((v, c) => c.copy(runDirectory = Path.of(v)))),
      cmd("plan").action((_, c) => c.copy(command = "plan")).text("Save a deterministic pruning plan and analysis").children(
        opt[String]("source").required().action((v, c) => c.copy(source = Path.of(v))),
        opt[String]("run-dir").required().action((v, c) => c.copy(runDirectory = Path.of(v))),
        opt[String]("kanji-dir").action((v, c) => c.copy(kanji = Path.of(v))).text("Default: kanji-data"),
        opt[String]("policy").action((v, c) => c.copy(policy = Some(Path.of(v)))).text("Optional policy JSON; omitted uses the initial policy")),
      cmd("apply").action((_, c) => c.copy(command = "apply")).text("Revalidate plan, stage, compare every tensor and publish a new model").children(
        opt[String]("plan").required().action((v, c) => c.copy(plan = Path.of(v))),
        opt[String]("model-out").required().action((v, c) => c.copy(modelOutput = Path.of(v))), python,
        opt[Unit]("copy-only").action((_, c) => c.copy(copyOnly = true)).text("Disable APFS clone; retain capacity checks and bounded copying")),
      cmd("verify").action((_, c) => c.copy(command = "verify")).text("Recheck manifest hashes, full tensor correspondence and reference tokenizer").children(
        manifest, python, opt[String]("report").action((v, c) => c.copy(report = Some(Path.of(v)))).text("Optional new verification report under outputs")),
      cmd("evaluate").action((_, c) => c.copy(command = "evaluate")).text("Compare source and pruned models through an explicitly supplied oMLX server").children(
        manifest, python, opt[String]("suite").required().action((v, c) => c.copy(suite = Path.of(v))),
        opt[String]("omlx-url").required().action((v, c) => c.copy(url = v)),
        opt[String]("baseline-model").required().action((v, c) => c.copy(baselineModel = v)),
        opt[String]("pruned-model").required().action((v, c) => c.copy(prunedModel = v)),
        opt[String]("evaluation-out").required().action((v, c) => c.copy(evaluationOutput = Path.of(v))),
        opt[String]("api-key-env").action((v, c) => c.copy(apiKeyEnvironment = v)).text("Name of environment variable containing the key; default OMLX_API_KEY"),
        opt[String]("server-environment").action((v, c) => c.copy(serverEnvironment = Some(Path.of(v)))).text("Dedicated server.json for version and sampled process RSS"),
        opt[Int]("trials").action((v, c) => c.copy(trials = v)).validate(v => if v > 0 then success else failure("trials must be positive")),
        opt[Int]("warmups").action((v, c) => c.copy(warmups = v)).validate(v => if v >= 0 then success else failure("warmups cannot be negative")),
        opt[Int]("timeout-seconds").action((v, c) => c.copy(timeout = v)).validate(v => if v > 0 then success else failure("timeout must be positive"))))

  def main(args: Array[String]): Unit = sys.exit(execute(args.toVector))

  /** Return an exit code without terminating the JVM, so dispatch can be integration-tested. */
  def execute(args: Vector[String], options: ProcessingOptions = ProcessingOptions(), helpers: Path = Path.of("scripts"),
      out: PrintStream = System.out, err: PrintStream = System.err): Int =
    val normalized = if args.isEmpty then Vector("--help") else if args.head == "help" then args.tail :+ "--help" else args
    if normalized.contains("--help") && (normalized.size == 1 || Set("inspect", "plan", "apply", "verify", "evaluate")(normalized.head)) then
      out.println(OParser.usage(parser))
      return 0
    val (parsed, effects) = OParser.runParser(parser, normalized, Arguments())
    var argumentError = false
    var helpCompleted = false
    OParser.runEffects(effects, new DefaultOEffectSetup:
      override def displayToOut(message: String): Unit = out.println(message)
      override def displayToErr(message: String): Unit = err.println(message)
      override def reportError(message: String): Unit = { argumentError = true; err.println(s"Error: $message") }
      override def terminate(state: Either[String, Unit]): Unit = if state.isLeft then argumentError = true else helpCompleted = true
    )
    if argumentError then return 2
    if helpCompleted then return 0
    parsed match
      case None => if argumentError then 2 else 0
      case Some(config) =>
        val runtime = options.copy(progress = message => err.println(message))
        try
          val result = config.command match
            case "inspect" => Json.obj("runDirectory" -> Json.fromString(CheckpointProcessor.inspect(config.source, config.runDirectory, runtime).toString))
            case "plan" =>
              val settings = config.policy.fold(PolicySettings())(PolicySettings.load)
              val path = CheckpointProcessor.plan(config.source, config.kanji, settings, config.runDirectory, runtime)
              Json.obj("plan" -> Json.fromString(path.toString), "summary" -> JsonIO.read(path.getParent.resolve("summary.json")))
            case "apply" => Json.obj("modelOutput" -> Json.fromString(CheckpointProcessor.applyPlan(config.plan, config.modelOutput, ExternalTools.reference(config.python, helpers),
              if config.copyOnly then runtime.copy(cloner = FileCloner.disabled) else runtime).toString))
            case "verify" =>
              val result = CheckpointProcessor.verifyManifest(config.manifest, ExternalTools.reference(config.python, helpers), runtime).json
              config.report.foreach { path =>
                val report = ProcessingIO.outputPath(runtime.outputRoot, path)
                Files.createDirectories(report.getParent)
                ProcessingIO.write(report, result)
              }
              result
            case "evaluate" => evaluate(config, runtime, helpers)
            case _ => throw IllegalArgumentException("a command is required; use --help")
          out.println(ProcessingIO.canonical(result))
          if config.command == "evaluate" && result.hcursor.downField("logits").get[Boolean]("passed").contains(false) then 4 else 0
        catch
          case NonFatal(error) =>
            err.println(Option(error.getMessage).getOrElse(error.getClass.getName))
            if config.command == "verify" then 3 else 1

  private def evaluate(config: Arguments, options: ProcessingOptions, helpers: Path): Json =
    Diagnostics.check(config.url.startsWith("http://") || config.url.startsWith("https://"), Path.of("omlx-url"), "URL", "expected HTTP(S) endpoint")
    CheckpointProcessor.verifyManifest(config.manifest, ExternalTools.reference(config.python, helpers), options)
    val manifest = JsonIO.read(config.manifest)
    val source = Path.of(JsonFields.text(JsonFields.field(manifest, "source", config.manifest, "manifest"), config.manifest, "source"))
    val model = Path.of(JsonFields.text(JsonFields.field(manifest, "modelOutput", config.manifest, "manifest"), config.manifest, "modelOutput"))
    val directory = ProcessingIO.createDirectory(options.outputRoot, config.evaluationOutput, Vector(source, model))
    val prepared = PlanStore.loadVerified(Path.of(JsonFields.text(JsonFields.field(manifest, "plan", config.manifest, "manifest"), config.manifest, "plan")))
    val forbidden = (0 to 0x10ffff).iterator.filter(cp => cp < 0xd800 || cp > 0xdfff)
      .flatMap(cp => prepared.domain.policy.forbidden(me.nsmr.vocabsurgery.core.CodePoint(cp)))
      .map(f => Json.arr(Json.fromInt(f.character.intValue), Json.fromValues(f.reasons.toVector.map(_.code).sorted.map(Json.fromString))))
    ProcessingIO.write(directory.resolve("forbidden-codepoints.json"), Json.fromValues(forbidden.toVector))
    FileIO.copyRange(config.suite, directory.resolve("suite.jsonl"), me.nsmr.vocabsurgery.core.ByteRange(me.nsmr.vocabsurgery.core.ByteOffset(0), me.nsmr.vocabsurgery.core.ByteCount(Files.size(config.suite))))
    val extra = config.serverEnvironment.toVector.flatMap(path => Vector("--server-environment", path.toString))
    ExternalTools.run(config.python, helpers.resolve("evaluate.py"), Vector("--manifest", config.manifest.toAbsolutePath.toString,
      "--suite", directory.resolve("suite.jsonl").toString, "--omlx-url", config.url, "--baseline-model", config.baselineModel,
      "--pruned-model", config.prunedModel, "--output", directory.toString, "--api-key-env", config.apiKeyEnvironment,
      "--trials", config.trials.toString, "--warmups", config.warmups.toString) ++ extra, directory.resolve("helper.log"), config.timeout)
    JsonIO.read(directory.resolve("metrics.json"))
