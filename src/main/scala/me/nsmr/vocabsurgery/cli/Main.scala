package me.nsmr.vocabsurgery.cli

import io.circe.Json
import java.io.PrintStream
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal
import scopt.{DefaultOEffectSetup, OParser}
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.processing.*
import me.nsmr.vocabsurgery.sandbox.*

/** Parsed CLI values; required-option and count validation belongs to the scopt command schema. */
final case class Arguments(command: String = "", source: Path = Path.of("."), runDirectory: Path = Path.of("."),
    policy: Option[Path] = None, plan: Path = Path.of("."), modelOutput: Path = Path.of("."),
    manifest: Path = Path.of("."), python: Path = Path.of("python3"), report: Option[Path] = None, suite: Path = Path.of("evaluation/default-suite.jsonl"),
    evaluationOutput: Path = Path.of("."), extra: Option[String] = None, logits: Boolean = false, ratings: Option[Path] = None,
    ids: Path = Path.of("."), idsOutput: Path = Path.of("."), referenceName: String = "", referenceVersion: Option[String] = None, sha256: Option[String] = None,
    profileSource: Option[Path] = None, profileText: Option[String] = None, kanjiDirectory: Path = Path.of("kanji-data"),
    unihanDirectory: Option[Path] = None, jmdictDirectory: Option[Path] = None,
    trials: Int = 5, warmups: Int = 1, copyOnly: Boolean = false, sandbox: SandboxArguments = SandboxArguments(), serve: ServeArguments = ServeArguments())

/** Options of the serve command; runtime-specific ones apply only to their runtime. */
final case class ServeArguments(runtime: String = "", models: Vector[(String, Path)] = Vector.empty, serverDirectory: Path = Path.of("."),
    port: Int = 18080, readyTimeoutSeconds: Int = 1800, python: Path = Path.of("python3"), cloneDirectory: Path = Path.of("outputs/tensorfold"),
    context: Int = 65536, pleOnSsd: Boolean = true, draftVocabulary: Option[Path] = None, cudaHome: Path = Path.of("/usr/local/cuda"),
    promptCache: Boolean = true, command: Vector[String] = Vector.empty, probes: Path = Path.of("evaluation/speed-prompts.json"), output: Option[Path] = None,
    material: Path = Path.of("."), copyTo: Option[Path] = None):
  def request: me.nsmr.vocabsurgery.runtime.ServeRequest =
    me.nsmr.vocabsurgery.runtime.ServeRequest(models.map((name, path) => me.nsmr.vocabsurgery.runtime.ServedModel(name, path)), serverDirectory, port,
      scala.concurrent.duration.FiniteDuration(readyTimeoutSeconds.toLong, "seconds"))
  def inferenceRuntime: me.nsmr.vocabsurgery.runtime.InferenceRuntime = runtime match
    case "omlx" => me.nsmr.vocabsurgery.runtime.OMlx(python)
    case "tensorfold" => me.nsmr.vocabsurgery.runtime.TensorFoldClone(cloneDirectory, context, pleOnSsd, draftVocabulary, cudaHome, promptCache)
    case other => throw IllegalArgumentException(s"unknown runtime $other; choose omlx or tensorfold")

/** Options of the sandbox commands. */
final case class SandboxArguments(choice: Option[String] = None, workDirectory: Path = Path.of("."), readable: Vector[Path] = Vector.empty,
    environment: Map[String, String] = Map.empty, timeoutSeconds: Int = 60, command: Vector[String] = Vector.empty):
  def sandboxChoice: SandboxChoice = choice.fold(SandboxChoice.fromEnvironment)(SandboxChoice.parse)

/** Thin command dispatch; domain decisions and file mutation remain in their respective layers. */
object Main:
  private val builder = OParser.builder[Arguments]
  private val parser =
    import builder.*
    def python = opt[String]("python").required().valueName("<interpreter>").action((v, c) => c.copy(python = Path.of(v)))
      .text("Python with tokenizers and transformers installed; no packages are downloaded")
    def manifest = opt[String]("manifest").required().action((v, c) => c.copy(manifest = Path.of(v)))
    def runtimeOptions: Seq[OParser[?, Arguments]] = Seq(
        opt[String]("runtime").required().action((v, c) => c.copy(serve = c.serve.copy(runtime = v)))
          .validate(v => if Set("omlx", "tensorfold")(v) then success else failure("--runtime must be omlx or tensorfold")).text("omlx | tensorfold"),
        opt[Int]("port").action((v, c) => c.copy(serve = c.serve.copy(port = v))).text("Loopback port; default 18080"),
        opt[Int]("ready-timeout-seconds").action((v, c) => c.copy(serve = c.serve.copy(readyTimeoutSeconds = v))).validate(v => if v > 0 then success else failure("timeout must be positive")),
        opt[String]("omlx-python").action((v, c) => c.copy(serve = c.serve.copy(python = Path.of(v)))).text("omlx: Python with omlx installed; default python3"),
        opt[String]("tensorfold-clone").action((v, c) => c.copy(serve = c.serve.copy(cloneDirectory = Path.of(v)))).text("tensorfold: clone from scripts/flash-next/setup-tensorfold.sh; default outputs/tensorfold"),
        opt[Int]("context").action((v, c) => c.copy(serve = c.serve.copy(context = v))).text("tensorfold: prompt and reply window; default 65536"),
        opt[Unit]("no-ple-on-ssd").action((_, c) => c.copy(serve = c.serve.copy(pleOnSsd = false))).text("tensorfold: keep the PLE tables in memory"),
        opt[String]("draft-vocab").action((v, c) => c.copy(serve = c.serve.copy(draftVocabulary = Some(Path.of(v))))).text("tensorfold: draft list of upstream token ids"),
        opt[String]("cuda-home").action((v, c) => c.copy(serve = c.serve.copy(cudaHome = Path.of(v)))).text("tensorfold: CUDA toolkit; default /usr/local/cuda"),
        opt[Unit]("no-prompt-cache").action((_, c) => c.copy(serve = c.serve.copy(promptCache = false))).text("tensorfold: prefill every prompt whole (patch 0004)"))
    def serveOptions: Seq[OParser[?, Arguments]] = runtimeOptions ++ Seq(
        opt[String]("model").required().unbounded().valueName("NAME=CHECKPOINT").action((v, c) => c.copy(serve = c.serve.copy(models =
          c.serve.models :+ (v.takeWhile(_ != '=') -> Path.of(v.dropWhile(_ != '=').drop(1)))))).validate(v => if v.contains('=') then success else failure("--model needs NAME=CHECKPOINT")),
        opt[String]("server-dir").required().action((v, c) => c.copy(serve = c.serve.copy(serverDirectory = Path.of(v)))).text("New directory under outputs for server.log and server.json"))
    def sandboxChoice = opt[String]("sandbox").action((v, c) => c.copy(sandbox = c.sandbox.copy(choice = Some(v))))
      .validate(v => if SandboxChoice.values.exists(_.name == v) then success else failure(s"--sandbox must be one of ${SandboxChoice.values.map(_.name).mkString(", ")}"))
      .text("auto (default, or VOCAB_SANDBOX), sandbox-exec or docker")
    OParser.sequence(programName("vocab-surgery"), head("vocab-surgery"),
      help("help").text("Show command usage (also: help <command>)"),
      cmd("inspect").action((_, c) => c.copy(command = "inspect")).text("Inspect source metadata without reading weights").children(
        opt[String]("source").required().action((v, c) => c.copy(source = Path.of(v))),
        opt[String]("run-dir").required().action((v, c) => c.copy(runDirectory = Path.of(v)))),
      cmd("plan").action((_, c) => c.copy(command = "plan")).text("Save a deterministic pruning plan and analysis").children(
        opt[String]("source").required().action((v, c) => c.copy(source = Path.of(v))),
        opt[String]("run-dir").required().action((v, c) => c.copy(runDirectory = Path.of(v))),
        opt[String]("policy").action((v, c) => c.copy(policy = Some(Path.of(v)))).text("Policy JSON; omitted uses the default character policy with kanji-data")),
      cmd("apply").action((_, c) => c.copy(command = "apply")).text("Revalidate plan, stage, compare every tensor and publish a new model").children(
        opt[String]("plan").required().action((v, c) => c.copy(plan = Path.of(v))),
        opt[String]("model-out").required().action((v, c) => c.copy(modelOutput = Path.of(v))), python,
        opt[Unit]("copy-only").action((_, c) => c.copy(copyOnly = true)).text("Copy instead of copy-on-write cloning; capacity checks and bounded copying remain")),
      cmd("verify").action((_, c) => c.copy(command = "verify")).text("Recheck manifest hashes, full tensor correspondence and reference tokenizer").children(
        manifest, python, opt[String]("report").action((v, c) => c.copy(report = Some(Path.of(v)))).text("Optional new verification report under outputs")),
      cmd("translate-ids").action((_, c) => c.copy(command = "translate-ids"))
        .text("Rewrite a list of source token IDs (e.g. a draft vocabulary) into the published model's IDs").children(
        opt[String]("manifest").required().action((v, c) => c.copy(manifest = Path.of(v))),
        opt[String]("ids").required().action((v, c) => c.copy(ids = Path.of(v))).text("Whitespace-separated source token IDs"),
        opt[String]("out").required().action((v, c) => c.copy(idsOutput = Path.of(v))).text("New file under the output root, one ID per line")),
      cmd("fetch-reference").action((_, c) => c.copy(command = "fetch-reference"))
        .text("Download reference data (unihan, jmdict) or evaluation tools (node, typescript) into <output root>/reference/<name>/<version>, checked by SHA-256 or the publisher's digest").children(
        arg[String]("<name>").action((v, c) => c.copy(referenceName = v)).text(ReferenceFetch.sources.map(_.name).mkString(" | ")),
        opt[String]("version").action((v, c) => c.copy(referenceVersion = Some(v))).text("unihan: Unicode version (default ICU's); jmdict: expected creation date; node, typescript: release (default 24.21.0, 6.0.2)"),
        opt[String]("sha256").action((v, c) => c.copy(sha256 = Some(v))).text("Expected download digest when none is built in")),
      cmd("profile-han").action((_, c) => c.copy(command = "profile-han"))
        .text("Write static facts (kanji lists, Unihan variants, JMdict usage) for the Han characters of a vocabulary or text").children(
        opt[String]("run-dir").required().action((v, c) => c.copy(runDirectory = Path.of(v))),
        opt[String]("source").action((v, c) => c.copy(profileSource = Some(Path.of(v)))).text("Snapshot whose tokenizer vocabulary is profiled"),
        opt[String]("text").action((v, c) => c.copy(profileText = Some(v))).text("Text whose characters are profiled"),
        opt[String]("kanji-dir").action((v, c) => c.copy(kanjiDirectory = Path.of(v))).text("Default: kanji-data"),
        opt[String]("unihan").action((v, c) => c.copy(unihanDirectory = Some(Path.of(v)))).text("Default: fetched Unihan of ICU's Unicode version"),
        opt[String]("jmdict").action((v, c) => c.copy(jmdictDirectory = Some(Path.of(v)))).text("Default: newest fetched JMdict"),
        checkConfig(c => if c.command == "profile-han" && c.profileSource.isDefined == c.profileText.isDefined
          then failure("profile-han needs exactly one of --source and --text") else success)),
      cmd("evaluate").action((_, c) => c.copy(command = "evaluate"))
        .text("Compare a manifest's source and pruned models on the fixed suite, serving each in turn with an inference runtime").children(
        (Seq(manifest, python.text("Python with tokenizers and transformers (and MLX for --logits); the reference implementations run on it"),
          opt[String]("suite").action((v, c) => c.copy(suite = Path.of(v))).text("Default evaluation/default-suite.jsonl"),
          opt[String]("evaluation-out").required().action((v, c) => c.copy(evaluationOutput = Path.of(v))).text("New directory under outputs"),
          opt[Int]("trials").action((v, c) => c.copy(trials = v)).validate(v => if v > 0 then success else failure("trials must be positive")),
          opt[Int]("warmups").action((v, c) => c.copy(warmups = v)).validate(v => if v >= 0 then success else failure("warmups cannot be negative")),
          opt[String]("extra").action((v, c) => c.copy(extra = Some(v))).text("JSON object added to every request, e.g. {\"chat_template_kwargs\":{\"enable_thinking\":false}}"),
          opt[Unit]("logits").action((_, c) => c.copy(logits = true)).text("Also compare surviving logits with scripts/logits.py (needs MLX)"),
          sandboxChoice) ++ runtimeOptions)*),
      cmd("review").action((_, c) => c.copy(command = "review")).text("Recompute a comparison's conclusions with complete human ratings, without inference").children(
        opt[String]("evaluation").required().action((v, c) => c.copy(evaluationOutput = Path.of(v))),
        opt[String]("ratings").required().action((v, c) => c.copy(ratings = Some(Path.of(v)))),
        opt[String]("out").required().action((v, c) => c.copy(report = Some(Path.of(v)))).text("New metrics JSON under outputs")),
      cmd("sandbox-check").action((_, c) => c.copy(command = "sandbox-check"))
        .text("Show which sandbox would confine generated code here (VOCAB_SANDBOX or --sandbox) and why others cannot").children(sandboxChoice),
      cmd("sandbox-run").action((_, c) => c.copy(command = "sandbox-run"))
        .text("Run a command confined: no network, host writes only in the work directory, reads only the system and listed trees").children(
        sandboxChoice,
        opt[String]("work-dir").required().action((v, c) => c.copy(sandbox = c.sandbox.copy(workDirectory = Path.of(v)))).text("Existing directory the command may write"),
        opt[String]("readable").unbounded().action((v, c) => c.copy(sandbox = c.sandbox.copy(readable = c.sandbox.readable :+ Path.of(v)))).text("Tree the command may read (repeatable)"),
        opt[String]("env").unbounded().valueName("NAME=VALUE").action((v, c) => c.copy(sandbox = c.sandbox.copy(environment =
          c.sandbox.environment + (v.takeWhile(_ != '=') -> v.dropWhile(_ != '=').drop(1))))).validate(v => if v.contains('=') then success else failure("--env needs NAME=VALUE")),
        opt[Int]("timeout-seconds").action((v, c) => c.copy(sandbox = c.sandbox.copy(timeoutSeconds = v))).validate(v => if v > 0 then success else failure("timeout must be positive")),
        arg[String]("<command>...").unbounded().required().action((v, c) => c.copy(sandbox = c.sandbox.copy(command = c.sandbox.command :+ v)))),
      cmd("serve").action((_, c) => c.copy(command = "serve"))
        .text("Serve checkpoints with an inference runtime; with a command after --, run it once ready (VOCAB_ENDPOINT, VOCAB_MODELS) and stop").children(
        (serveOptions :+ arg[String]("<command>...").unbounded().optional().action((v, c) => c.copy(serve = c.serve.copy(command = c.serve.command :+ v))))*),
      cmd("measure-speed").action((_, c) => c.copy(command = "measure-speed"))
        .text("Serve one checkpoint and measure decode speed, TTFT and, where the runtime reports it, draft acceptance on fixed prompts").children(
        (serveOptions ++ Seq(
          opt[String]("probes").action((v, c) => c.copy(serve = c.serve.copy(probes = Path.of(v)))).text("Speed prompts; default evaluation/speed-prompts.json"),
          opt[String]("out").required().action((v, c) => c.copy(serve = c.serve.copy(output = Some(Path.of(v))))).text("New result JSON under outputs")))*),
      cmd("run-external").action((_, c) => c.copy(command = "run-external"))
        .text("Serve, then run an external evaluation material's own CLI (after --) with VOCAB_ENDPOINT and VOCAB_MODELS, and record the run").children(
        (serveOptions ++ Seq(
          opt[String]("material").required().action((v, c) => c.copy(serve = c.serve.copy(material = Path.of(v)))).text("The material's directory; its runner and formats stay its own"),
          opt[String]("copy-to").action((v, c) => c.copy(serve = c.serve.copy(copyTo = Some(Path.of(v))))).text("New directory under outputs to copy the material into and run in"),
          arg[String]("<command>...").unbounded().required().action((v, c) => c.copy(serve = c.serve.copy(command = c.serve.command :+ v)))))*))


  def main(args: Array[String]): Unit = sys.exit(execute(args.toVector))

  /** Return an exit code without terminating the JVM, so dispatch can be integration-tested. */
  def execute(args: Vector[String], options: ProcessingOptions = ProcessingOptions(), helpers: Path = Path.of("scripts"),
      out: PrintStream = System.out, err: PrintStream = System.err): Int =
    val normalized = if args.isEmpty then Vector("--help") else if args.head == "help" then args.tail :+ "--help" else args
    if normalized.contains("--help") && (normalized.size == 1 || Set("inspect", "plan", "apply", "verify", "translate-ids", "fetch-reference", "profile-han", "evaluate", "sandbox-check", "sandbox-run", "serve", "measure-speed", "run-external", "review")(normalized.head)) then
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
              val policy = config.policy.fold(PolicyFile.default)(PolicyFile.load)
              val path = CheckpointProcessor.plan(config.source, policy, config.runDirectory, runtime)
              Json.obj("plan" -> Json.fromString(path.toString), "summary" -> JsonIO.read(path.getParent.resolve("summary.json")))
            case "apply" => Json.obj("modelOutput" -> Json.fromString(CheckpointProcessor.applyPlan(config.plan, config.modelOutput, ExternalTools.reference(config.python, helpers),
              if config.copyOnly then runtime.copy(transfer = FileTransfer.copying(runtime.transfer)) else runtime).toString))
            case "verify" =>
              val result = CheckpointProcessor.verifyManifest(config.manifest, ExternalTools.reference(config.python, helpers), runtime).json
              config.report.foreach { path =>
                val report = ProcessingIO.outputPath(runtime.outputRoot, path)
                Files.createDirectories(report.getParent)
                ProcessingIO.write(report, result)
              }
              result
            case "translate-ids" => CheckpointProcessor.translateIds(config.manifest, config.ids, config.idsOutput, runtime)
            case "fetch-reference" =>
              val source = ReferenceFetch.sources.find(_.name == config.referenceName)
                .getOrElse(throw IllegalArgumentException(s"unknown reference ${config.referenceName}; known: ${ReferenceFetch.sources.map(_.name).mkString(", ")}"))
              ReferenceFetch.fetch(source, config.referenceVersion, config.sha256, runtime)
            case "profile-han" =>
              val (references, inputs) = HanProfile.load(HanReferencePaths(config.kanjiDirectory, config.unihanDirectory, config.jmdictDirectory), runtime.outputRoot)
              val characters = config.profileSource.fold(HanProfile.fromText(config.profileText.get))(HanProfile.fromSnapshot)
              HanProfile.write(characters, config.profileSource.isDefined, references,
                inputs.mapObject(fields => config.profileSource.fold(fields)(source => fields.add("source", Json.fromString(source.toRealPath().toString)))),
                config.runDirectory, config.profileSource.toVector :+ config.kanjiDirectory, runtime)
            case "evaluate" => evaluate(config, runtime, helpers, err)
            case "review" => me.nsmr.vocabsurgery.evaluation.Comparison.review(config.evaluationOutput, config.ratings.get, config.report.get, runtime)
            case "sandbox-check" => Sandbox.report(config.sandbox.sandboxChoice)
            case "serve" => serve(config.serve, runtime, err)
            case "measure-speed" =>
              val arguments = config.serve
              me.nsmr.vocabsurgery.evaluation.SpeedMeasurement.run(arguments.inferenceRuntime, arguments.request,
                me.nsmr.vocabsurgery.evaluation.SpeedProbes.load(arguments.probes), arguments.output.get, runtime,
                row => err.println(s"${row.prompt}/${row.mode} #${row.trial}: ${row.response.completionTokens.getOrElse("?")} tokens, " +
                  f"${row.response.decodeTokensPerSecond.getOrElse(0.0)}%.1f tok/s")).mapObject(_.remove("rows"))
            case "run-external" =>
              val arguments = config.serve
              me.nsmr.vocabsurgery.evaluation.ExternalRun.run(arguments.inferenceRuntime, arguments.request,
                me.nsmr.vocabsurgery.evaluation.ExternalRun(arguments.material, arguments.copyTo, arguments.command), runtime)
            case "sandbox-run" =>
              val options = config.sandbox
              Sandbox.platform(options.sandboxChoice).run(options.command, Confinement(options.workDirectory, options.readable,
                options.environment, scala.concurrent.duration.FiniteDuration(options.timeoutSeconds.toLong, "seconds"))).json
            case _ => throw IllegalArgumentException("a command is required; use --help")
          out.println(ProcessingIO.canonical(result))
          if config.command == "evaluate" && result.hcursor.downField("logits").get[Boolean]("passed").contains(false) then 4
          else if Set("sandbox-run", "serve", "run-external")(config.command) && result.hcursor.get[Int]("exitCode").exists(_ != 0) then 5
          else if config.command == "sandbox-run" && result.hcursor.get[Boolean]("timedOut").contains(true) then 5
          else 0
        catch
          case NonFatal(error) =>
            err.println(Option(error.getMessage).getOrElse(error.getClass.getName))
            if config.command == "verify" then 3 else 1

  /** Serve, then run the given command against the endpoint and stop; without a command, serve until interrupted. */
  private def serve(arguments: ServeArguments, options: ProcessingOptions, err: PrintStream): Json =
    import me.nsmr.vocabsurgery.runtime.*
    val server = arguments.inferenceRuntime.serve(arguments.request, options)
    try
      err.println(s"Serving ${server.endpoint.models.mkString(", ")} at ${server.endpoint.base}; log ${server.log}")
      if arguments.command.isEmpty then
        Runtime.getRuntime.addShutdownHook(Thread(() => server.close()))
        while server.alive do Thread.sleep(1000L)
        server.metadata
      else
        val builder = ProcessBuilder(arguments.command*).inheritIO()
        builder.environment().put("VOCAB_ENDPOINT", server.endpoint.base.toString)
        builder.environment().put("VOCAB_MODELS", server.endpoint.models.mkString(","))
        val code = builder.start().waitFor()
        Json.obj("server" -> server.metadata, "exitCode" -> Json.fromInt(code))
    finally server.close()

  /** Verify the manifest, collect the reference results (tokenization, logits, versions) from the Python reference
    * implementations, and run the comparison with the chosen runtime, each checkpoint served in turn.
    */
  private def evaluate(config: Arguments, options: ProcessingOptions, helpers: Path, err: PrintStream): Json =
    import me.nsmr.vocabsurgery.evaluation.*
    import me.nsmr.vocabsurgery.runtime.ServedModel
    CheckpointProcessor.verifyManifest(config.manifest, ExternalTools.reference(config.python, helpers), options)
    val manifest = JsonIO.read(config.manifest)
    def field(name: String) = Path.of(JsonFields.text(JsonFields.field(manifest, name, config.manifest, "manifest"), config.manifest, name))
    val (source, model) = (field("source"), field("modelOutput"))
    val prepared = PlanStore.loadVerified(field("plan"))
    val rule = prepared.domain.policy.characterRule.getOrElse(Diagnostics.invalid(config.manifest, "policy",
      s"contamination metrics need a policy that decides by characters; ${prepared.domain.policy.name} does not"))
    val forbidden = (cp: Int) => if cp >= 0xd800 && cp <= 0xdfff then Vector.empty
      else rule.forbidden(me.nsmr.vocabsurgery.core.CodePoint(cp)).toVector.flatMap(_.reasons.toVector.map(_.code)).sorted
    val scratch = Files.createTempDirectory("vocab-evaluate-")
    try
      def reference(name: String, args: String*): Json =
        val out = scratch.resolve(s"$name.json")
        ExternalTools.run(config.python, helpers.resolve("tokenization.py"), args.toVector ++ Vector("--out", out.toString), scratch.resolve(s"$name.log"), 1800)
        JsonIO.read(out)
      val mapping = scratch.resolve("mapping.json")
      ProcessingIO.write(mapping, prepared.domain.projection.compactToOriginalJson)
      val tokenization = reference("tokenization", "suite", "--source", source.toString, "--model", model.toString, "--suite", config.suite.toString,
        "--mapping", mapping.toString).asArray.getOrElse(Vector.empty)
      val versions = reference("versions", "versions")
      val logits = if !config.logits then Json.obj("passed" -> Json.Null, "state" -> Json.fromString("not-run"))
        else
          val out = scratch.resolve("logits")
          try ExternalTools.run(config.python, helpers.resolve("logits.py"), Vector("compare", "--manifest", config.manifest.toAbsolutePath.toString,
            "--output", out.toString), scratch.resolve("logits.log"), 3600)
          catch case e: DataException if Files.isRegularFile(out.resolve("logits-summary.json")) => ()
          JsonIO.read(out.resolve("logits-summary.json"))
      var located = 0
      val locate = (positions: Vector[(String, String, Int)]) =>
        located += 1
        val input = scratch.resolve(s"positions-$located.json")
        ProcessingIO.write(input, Json.fromValues(positions.map((id, text, position) =>
          Json.obj("id" -> Json.fromString(id), "text" -> Json.fromString(text), "position" -> Json.fromInt(position)))))
        reference(s"located-$located", "locate", "--tokenizer", source.toString, "--positions", input.toString).asArray.getOrElse(Vector.empty)
          .map(row => row.hcursor.get[String]("id").getOrElse("") -> row.hcursor.get[Int]("candidate").toOption).toMap
      val kept = prepared.domain.projection.compactToOriginal.map(_.intValue).toSet
      val references = ComparisonReferences(tokenization, logits, versions, locate, kept,
        prepared.domain.savedPayloadBytes.longValue)
      val extra = config.extra.fold(io.circe.JsonObject.empty)(text => io.circe.jawn.parse(text).toOption.flatMap(_.asObject)
        .getOrElse(throw IllegalArgumentException("--extra must be a JSON object")))
      val runtime = config.serve.copy(promptCache = false).inferenceRuntime
      Comparison.run(runtime, config.suite, ServedModel("baseline", source), ServedModel("pruned", model), references, forbidden,
        Toolchains.detect(config.python, options.outputRoot), Sandbox.platform(config.sandbox.sandboxChoice),
        ComparisonSettings(config.trials, config.warmups, extra = extra, port = config.serve.port,
          readyTimeout = scala.concurrent.duration.FiniteDuration(config.serve.readyTimeoutSeconds.toLong, "seconds")),
        config.evaluationOutput, options, message => err.println(message))
    finally Using.resource(Files.walk(scratch))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete))
