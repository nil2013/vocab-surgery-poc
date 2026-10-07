package me.nsmr.vocabsurgery.evaluation

import io.circe.{Json, JsonObject}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO}
import me.nsmr.vocabsurgery.processing.{ProcessingIO, ProcessingOptions}
import me.nsmr.vocabsurgery.runtime.{InferenceRuntime, ResourceSampler, ServeRequest, ServedModel}
import me.nsmr.vocabsurgery.sandbox.Sandbox

/** What the comparison needs from outside the JVM (B3 in docs/EVALUATION_ARCHITECTURE.md) and from the plan:
  * per-case prompt token counts before and after (chat templates rendered by the Python reference), the surviving
  * logits result (or a not-run record), client package versions, a way to find the token covering a character of a
  * baseline output, the source ids the pruned model keeps, and the plan's predicted payload saving.
  */
final case class ComparisonReferences(tokenization: Vector[Json], logits: Json, versions: Json,
    locate: Vector[(String, String, Int)] => Map[String, Option[Int]], keptSourceIds: Set[Int], predictedPayloadReductionBytes: Long)

/** A before/after comparison on this project's fixed suite: each checkpoint is served in turn by the runtime. */
final case class ComparisonSettings(trials: Int = 5, warmups: Int = 1, seedBase: Long = 2026, extra: JsonObject = JsonObject.empty, port: Int = 18080,
    readyTimeout: scala.concurrent.duration.FiniteDuration = scala.concurrent.duration.Duration(30, "minutes")):
  require(trials >= 1 && warmups >= 0, "invalid trials / warmups")

object Comparison:
  val Phases: Vector[String] = Vector("baseline", "pruned")
  private val SpeedFields = Vector("seconds", "ttftSeconds", "prefillTokensPerSecond", "decodeTokensPerSecond", "scalarsPerSecond")

  /** Median (mean of the middle pair), p95 (the ceil(0.95 n)-th smallest), min and max; None without values. */
  def quantiles(values: Vector[Double]): Json =
    if values.isEmpty then Json.Null
    else
      val sorted = values.sorted
      val n = sorted.size
      val median = if n % 2 == 1 then sorted(n / 2) else (sorted(n / 2 - 1) + sorted(n / 2)) / 2
      Json.obj("median" -> num(median), "p95" -> num(sorted(math.max(0, math.ceil(n * 0.95).toInt - 1))), "min" -> num(sorted.head), "max" -> num(sorted.last))

  private def num(value: Double): Json = Json.fromDoubleOrNull(value)
  private def codePoints(text: String): Vector[Int] = text.codePoints().toArray.toVector

  /** Python's json.dumps: ", " and ": " separators and ASCII escapes, as the reference sent tool results. */
  def pythonJson(value: Json): String = value.fold("null", _.toString, _.toString, s => Json.fromString(s).noSpaces.flatMap(c => if c < 128 then c.toString else f"\\u${c.toInt}%04x"),
    items => items.map(pythonJson).mkString("[", ", ", "]"), fields => fields.toVector.map((k, v) => s"${pythonJson(Json.fromString(k))}: ${pythonJson(v)}").mkString("{", ", ", "}"))

  /** Python's str() of a JSON-decoded value, as the reference looked for a tool result in the follow-up answer. */
  def pythonStr(value: Json): String = value.asString.getOrElse(pythonRepr(value))
  private def pythonRepr(value: Json): String = value.fold("None", b => if b then "True" else "False", _.toString,
    s => "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'", items => items.map(pythonRepr).mkString("[", ", ", "]"),
    fields => fields.toVector.map((k, v) => s"${pythonRepr(Json.fromString(k))}: ${pythonRepr(v)}").mkString("{", ", ", "}"))

  /** The streamed response of one case, checked as the controlled comparison requires: output, usage and no cached prefix. */
  private def stream(client: ChatClient, model: String, item: SuiteCase, settings: ComparisonSettings, temperature: Double, seed: Long,
      messages: Option[Vector[Json]] = None): ChatResponse =
    val input = (item.input, messages) match
      case (ChatInput.Messages(_, tools), Some(override_)) => ChatInput.Messages(override_, tools)
      case (other, _) => other
    val response = client.stream(ChatRequest(model, input, item.maxTokens, Some(temperature), Some(seed), Some(1.0), extra = settings.extra))
    if response.text.isEmpty && response.reasoning.isEmpty && response.toolCalls.isEmpty then throw ChatError(s"${item.id}: no output")
    val usage = response.usage.hcursor
    usage.downField("prompt_tokens_details").get[Int]("cached_tokens").toOption match
      case None => throw ChatError("server did not report cache usage; controlled comparison cannot be verified")
      case Some(cached) if cached != 0 => throw ChatError("prefix cache hit invalidates the controlled benchmark; serve without a prompt cache")
      case _ => ()
    if !Vector("prompt_tokens", "completion_tokens").forall(field => usage.get[Int](field).toOption.exists(_ >= 0)) then throw ChatError("invalid usage token count")
    response

  /** The reference's record of one response. */
  private def responseJson(response: ChatResponse): JsonObject =
    val usage = response.usage.hcursor
    val decodeSeconds = math.max(0.0, response.seconds - response.ttftSeconds)
    val prompt = usage.get[Int]("prompt_tokens").getOrElse(0)
    val completion = usage.get[Int]("completion_tokens").getOrElse(0)
    JsonObject("text" -> Json.fromString(response.text), "reasoning" -> Json.fromString(response.reasoning), "toolCalls" -> Json.fromValues(response.toolCalls),
      "finishReason" -> Json.fromString(response.finishReason), "usage" -> response.usage, "seconds" -> num(response.seconds),
      "ttftSeconds" -> num(response.ttftSeconds), "sseEvents" -> Json.fromInt(response.events),
      "prefillTokensPerSecond" -> usage.downField("prompt_tokens_per_second").focus.getOrElse(Json.Null),
      "decodeTokensPerSecond" -> usage.downField("generation_tokens_per_second").focus.getOrElse(Json.Null),
      "clientPrefillEstimate" -> (if response.ttftSeconds > 0 then num(prompt / response.ttftSeconds) else Json.Null),
      "clientDecodeEstimate" -> (if decodeSeconds > 0 then num(math.max(0, completion - 1) / decodeSeconds) else Json.Null),
      "scalarsPerSecond" -> (if response.seconds > 0 then num(response.text.length / response.seconds) else Json.Null))

  private def appendLine(path: Path, value: Json): Unit =
    Files.writeString(path, value.noSpaces + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND)

  /** Serve each phase's checkpoint in turn and record every case; returns the rows per phase and peak server memory. */
  private def phase(label: String, runtime: InferenceRuntime, checkpoint: ServedModel, suite: Vector[SuiteCase], settings: ComparisonSettings,
      forbidden: Int => Vector[String], toolchains: Toolchains, sandbox: Sandbox, output: Path, options: ProcessingOptions,
      progress: String => Unit): (Vector[Json], Json) =
    val rows = output.resolve(s"$label.jsonl")
    Files.createFile(rows)
    val server = runtime.serve(ServeRequest(Vector(checkpoint), output.resolve("servers").resolve(label), settings.port, settings.readyTimeout), options)
    val sampler = ResourceSampler(server.pid)
    val client = ChatClient(server.endpoint.base)
    val records = Vector.newBuilder[Json]
    try
      for item <- suite do
        if item.benchmark then (0 until settings.warmups).foreach(_ => stream(client, checkpoint.name, item, settings, 0.0, settings.seedBase))
        val repeats = if item.sampling || item.benchmark then settings.trials else 1
        for trial <- 0 until repeats do
          progress(s"$label: ${item.id} trial ${trial + 1}/$repeats")
          val temperature = if item.sampling then 0.7 else 0.0
          val response = stream(client, checkpoint.name, item, settings, temperature, settings.seedBase + trial)
          val generated = codePoints(response.text + response.reasoning + response.toolCalls.flatMap(_.hcursor.downField("function").get[String]("arguments").toOption).mkString)
          val reasons = generated.flatMap(forbidden).groupBy(identity).view.mapValues(_.size).toVector.sortBy(_._1)
          var row = responseJson(response).deepMerge(JsonObject("id" -> Json.fromString(item.id), "category" -> Json.fromString(item.category.name),
            "language" -> item.code.fold(Json.Null)(c => Json.fromString(c.language.name)), "trial" -> Json.fromInt(trial),
            "benchmark" -> Json.fromBoolean(item.benchmark), "benchmarkLength" -> Json.fromString(item.benchmarkLength),
            "temperature" -> num(temperature), "seed" -> Json.fromLong(settings.seedBase + trial),
            "forbiddenScalars" -> Json.fromInt(generated.count(cp => forbidden(cp).nonEmpty)), "outputScalars" -> Json.fromInt(generated.size),
            "forbiddenByReason" -> Json.obj(reasons.map((reason, count) => reason -> Json.fromInt(count))*)))
          item.code.foreach(task => row = row.add("codeCheck", CodeChecks.check(task, response.text, toolchains, sandbox).json))
          item.expectedTool.filter(_ => item.category == CaseCategory.Tool).foreach { expected =>
            val call = response.toolCalls match
              case Vector(single) => Some(single)
              case _ => None
            val valid = call.exists { c =>
              val function = c.hcursor.downField("function")
              c.hcursor.get[String]("id").exists(_.nonEmpty) && function.get[String]("name").contains(expected.name) &&
                function.get[String]("arguments").toOption.flatMap(a => io.circe.jawn.parse(a).toOption).contains(expected.arguments)
            }
            if valid then
              val followupMessages = item.messages ++ Vector(
                Json.obj("role" -> Json.fromString("assistant"), "content" -> (if response.text.isEmpty then Json.Null else Json.fromString(response.text)),
                  "tool_calls" -> Json.fromValues(response.toolCalls)),
                Json.obj("role" -> Json.fromString("tool"), "tool_call_id" -> call.get.hcursor.downField("id").focus.get,
                  "content" -> Json.fromString(pythonJson(expected.result))))
              val followup = stream(client, checkpoint.name, item, settings, 0.0, settings.seedBase, Some(followupMessages))
              row = row.add("toolResponse", Json.fromJsonObject(responseJson(followup)))
                .add("toolPassed", Json.fromBoolean(followup.text.trim.nonEmpty && followup.text.contains(pythonStr(expected.result))))
            else row = row.add("toolPassed", Json.False)
          }
          val json = Json.fromJsonObject(row)
          records += json
          appendLine(rows, json)
    finally
      sampler.close()
      server.close()
    (records.result(), Json.obj("peakSampledServerRssBytes" -> sampler.peakBytes.fold(Json.Null)(Json.fromLong), "sampleIntervalSeconds" -> num(0.25),
      "server" -> server.metadata))

  /** The reference's conclusions from both phases' rows, the tokenization rows, the logits result and optional ratings. */
  def summarize(baseline: Vector[Json], pruned: Vector[Json], tokenization: Vector[Json], logits: Json, ratings: Option[Vector[Json]]): Json =
    def str(row: Json, field: String) = row.hcursor.get[String](field).toOption
    def int(row: Json, field: String) = row.hcursor.get[Int](field).getOrElse(0)
    def bool(row: Json, field: String) = row.hcursor.get[Boolean](field).getOrElse(false)
    def double(row: Json, field: String) = row.hcursor.downField(field).focus.flatMap(_.asNumber).map(_.toDouble)
    def phaseSummary(records: Vector[Json]): JsonObject =
      def category(name: String) = records.filter(r => str(r, "category").contains(name))
      val sampled = category("contamination")
      def first(name: String) = category(name).filter(r => int(r, "trial") == 0)
      def passed(rows: Vector[Json]) = rows.count(_.hcursor.downField("codeCheck").get[Boolean]("passed").getOrElse(false))
      val benchmark = records.filter(r => bool(r, "benchmark"))
      def speed(rows: Vector[Json]) = Json.obj(SpeedFields.map(field => field -> quantiles(rows.flatMap(r => double(r, field))))*)
      val forbiddenScalars = sampled.map(r => int(r, "forbiddenScalars")).sum
      val outputScalars = sampled.map(r => int(r, "outputScalars")).sum
      val contaminated = sampled.count(r => int(r, "forbiddenScalars") > 0)
      val reasons = sampled.flatMap(r => r.hcursor.downField("forbiddenByReason").focus.flatMap(_.asObject).toVector.flatMap(_.toVector))
        .groupMapReduce(_._1)(_._2.asNumber.flatMap(_.toInt).getOrElse(0))(_ + _)
      val groups = benchmark.map(r => (str(r, "category").getOrElse(""), str(r, "benchmarkLength").getOrElse("unspecified"))).distinct.sorted
      JsonObject("outputs" -> Json.fromInt(records.size), "codingPassed" -> Json.fromInt(passed(first("coding"))), "codingCases" -> Json.fromInt(first("coding").size),
        "fimPassed" -> Json.fromInt(passed(first("fim"))), "fimCases" -> Json.fromInt(first("fim").size),
        "toolPassed" -> Json.fromInt(first("tool").count(r => bool(r, "toolPassed"))), "toolCases" -> Json.fromInt(first("tool").size),
        "contaminationOutputs" -> Json.fromInt(sampled.size), "contaminatedOutputs" -> Json.fromInt(contaminated),
        "forbiddenScalars" -> Json.fromInt(forbiddenScalars), "outputScalars" -> Json.fromInt(outputScalars),
        "eosObserved" -> Json.fromBoolean(records.exists(r => str(r, "finishReason").contains("stop"))), "speed" -> speed(benchmark),
        "contaminatedOutputRate" -> (if sampled.isEmpty then Json.Null else num(contaminated.toDouble / sampled.size)),
        "forbiddenScalarRate" -> (if outputScalars == 0 then Json.Null else num(forbiddenScalars.toDouble / outputScalars)),
        "forbiddenByReason" -> Json.obj(reasons.toVector.sortBy(_._1).map((k, v) => k -> Json.fromInt(v))*),
        "speedByCategoryAndLength" -> Json.fromValues(groups.map((name, length) => Json.obj("category" -> Json.fromString(name), "length" -> Json.fromString(length),
          "metrics" -> speed(benchmark.filter(r => str(r, "category").contains(name) && str(r, "benchmarkLength").getOrElse("unspecified") == length))))))
    val b = phaseSummary(baseline)
    val p = phaseSummary(pruned)
    def n(phase: JsonObject, field: String) = phase(field).flatMap(_.asNumber).flatMap(_.toInt).getOrElse(0)
    val ratios = tokenization.filter(r => str(r, "category").exists(Set("japanese", "coding"))).flatMap(r => double(r, "ratio"))
    val baselineFirst = baseline.filter(r => int(r, "trial") == 0).map(r => str(r, "id").getOrElse("") -> r).toMap
    val divergence = pruned.filter(r => int(r, "trial") == 0 && !str(r, "category").contains("contamination")).map { row =>
      val before = codePoints(str(baselineFirst(str(row, "id").get), "text").getOrElse(""))
      val after = codePoints(str(row, "text").getOrElse(""))
      val firstDifference = before.zip(after).indexWhere((x, y) => x != y) match
        case -1 => Option.when(before.size != after.size)(math.min(before.size, after.size))
        case index => Some(index)
      Json.obj("id" -> row.hcursor.downField("id").focus.get, "firstScalarDifference" -> firstDifference.fold(Json.Null)(Json.fromInt))
    }
    val quality = ratings match
      case None => Json.obj("state" -> Json.fromString("pending-human-review"))
      case Some(rows) =>
        val expected = baseline.filter(r => str(r, "category").contains("japanese")).flatMap(str(_, "id")).toSet
        if rows.size != expected.size || rows.flatMap(str(_, "id")).toSet != expected then throw IllegalArgumentException("ratings must cover every Japanese case exactly once")
        def score(row: Json, field: String) = row.hcursor.downField(field).focus.flatMap(j => if j.isNumber then j.asNumber.map(_.toDouble) else None)
        if rows.isEmpty || rows.exists(r => !Vector("baseline", "pruned").forall(f => score(r, f).exists(s => s >= 1 && s <= 5))) then
          throw IllegalArgumentException("ratings require 1..5 numeric before/after scores")
        val delta = rows.map(r => score(r, "pruned").get - score(r, "baseline").get).sum / rows.size
        Json.obj("state" -> Json.fromString("reviewed"), "meanDelta" -> num(delta), "passed" -> Json.fromBoolean(delta >= -0.5 && !rows.exists(r => bool(r, "majorBreakdown"))))
    val codingGate = if n(b, "codingCases") == 0 then None else Some(n(p, "codingPassed").toDouble / n(p, "codingCases") >= n(b, "codingPassed").toDouble / n(b, "codingCases") - 0.1)
    val contamination = if n(b, "contaminatedOutputs") == 0 then "reduction_not_observable"
      else if n(p, "contaminatedOutputs").toDouble / math.max(1, n(p, "contaminationOutputs")) < n(b, "contaminatedOutputs").toDouble / math.max(1, n(b, "contaminationOutputs")) then "reduced"
      else "not_reduced"
    val tokenizationQuantiles = quantiles(ratios)
    val tokenizationGate = tokenizationQuantiles.hcursor.get[Double]("p95").toOption.map(_ <= 1.10)
    val fimGate = Option.when(n(p, "fimCases") > 0)(n(p, "fimPassed") == n(p, "fimCases"))
    val toolGate = Option.when(n(p, "toolCases") > 0)(n(p, "toolPassed") == n(p, "toolCases"))
    def opt(value: Option[Boolean]) = value.fold(Json.Null)(Json.fromBoolean)
    val eos = Vector(b, p).forall(_("eosObserved").flatMap(_.asBoolean).contains(true))
    val achieved = logits.hcursor.get[Boolean]("passed").contains(true) && Vector(tokenizationGate, codingGate, fimGate, toolGate,
      quality.hcursor.get[Boolean]("passed").toOption).forall(_.contains(true)) && eos && contamination == "reduced"
    def coverageCount(language: String) = baseline.filter(r => str(r, "language").contains(language) && str(r, "category").contains("coding")).flatMap(str(_, "id")).distinct.size
    val japaneseCases = baseline.filter(r => str(r, "category").contains("japanese")).flatMap(str(_, "id")).distinct.size
    val complete = japaneseCases >= 20 && Vector("python", "scala", "typescript").forall(coverageCount(_) >= 10) && n(b, "fimCases") >= 10 &&
      n(b, "toolCases") >= 10 && n(b, "contaminationOutputs") >= 150 && n(p, "contaminationOutputs") >= 150
    Json.obj("phases" -> Json.obj("baseline" -> Json.fromJsonObject(b), "pruned" -> Json.fromJsonObject(p)), "tokenization" -> tokenizationQuantiles,
      "tokenizationGate" -> opt(tokenizationGate), "codingGate" -> opt(codingGate), "fimGate" -> opt(fimGate), "toolGate" -> opt(toolGate),
      "logits" -> logits, "japaneseQuality" -> quality, "contaminationConclusion" -> Json.fromString(contamination),
      "greedyDivergence" -> Json.fromValues(divergence), "taskGoalsFullyAchieved" -> Json.fromBoolean(achieved && complete),
      "coverage" -> Json.obj("japaneseCases" -> Json.fromInt(japaneseCases),
        "codingByLanguage" -> Json.obj(Vector("python", "scala", "typescript").map(l => l -> Json.fromInt(coverageCount(l)))*),
        "completeInitialSuite" -> Json.fromBoolean(complete)))

  private def logicalBytes(directory: Path): Long =
    Using.resource(Files.walk(directory))(_.iterator().asScala.filter(p => p != directory && Files.isRegularFile(p)).map(Files.size(_)).sum)

  /** Run both phases and write environment.json, <phase>.jsonl, tokenizer.jsonl, metrics.json, ratings-template.json and
    * REPORT.md into `output`, a new directory under the output root.
    */
  def run(runtime: InferenceRuntime, suitePath: Path, baseline: ServedModel, pruned: ServedModel, references: ComparisonReferences,
      forbidden: Int => Vector[String], toolchains: Toolchains, sandbox: Sandbox, settings: ComparisonSettings, output: Path,
      options: ProcessingOptions, progress: String => Unit = _ => ()): Json =
    val suite = QualitySuite.load(suitePath)
    val languages = suite.flatMap(_.code).map(_.language).toSet
    if languages(CodeLanguage.Scala) then Diagnostics.check(Files.isDirectory(toolchains.scalaCompiler), toolchains.scalaCompiler, "toolchain", "Scala compiler jars are missing; run sbt stageCli")
    if languages(CodeLanguage.TypeScript) then Diagnostics.check(toolchains.node.isDefined && toolchains.tsc.isDefined, suitePath, "toolchain",
      "TypeScript checks need fetch-reference node and fetch-reference typescript")
    val directory = ProcessingIO.createDirectory(options.outputRoot, output, Vector(baseline.checkpoint, pruned.checkpoint))
    Files.copy(suitePath, directory.resolve("suite.jsonl"))
    ProcessingIO.lines(directory.resolve("tokenizer.jsonl"), references.tokenization.iterator)
    val phases = Vector(baseline, pruned).zip(Phases).map((model, label) =>
      phase(label, runtime, model, suite, settings, forbidden, toolchains, sandbox, directory, options, progress))
    ProcessingIO.write(directory.resolve("environment.json"), Json.obj("runtime" -> Json.fromString(runtime.name), "clientPackageVersions" -> references.versions,
      "servers" -> Json.obj(Phases.zip(phases).map((label, p) => label -> p._2.hcursor.downField("server").focus.get)*), "concurrency" -> Json.fromInt(1),
      "prefixCache" -> Json.fromString("cached prompt tokens must be zero"), "suiteSha256" -> Json.fromString(FileIO.sha256(suitePath)),
      "trials" -> Json.fromInt(settings.trials), "warmups" -> Json.fromInt(settings.warmups), "seedBase" -> Json.fromLong(settings.seedBase),
      "extra" -> Json.fromJsonObject(settings.extra)))
    val summary = summarize(phases(0)._1, phases(1)._1, references.tokenization, references.logits, None)
    val baselineText = phases(0)._1.filter(_.hcursor.get[Int]("trial").contains(0)).map(r => r.hcursor.get[String]("id").getOrElse("") -> r.hcursor.get[String]("text").getOrElse("")).toMap
    val divergences = summary.hcursor.downField("greedyDivergence").as[Vector[Json]].getOrElse(Vector.empty)
    val positions = divergences.flatMap(d => d.hcursor.get[Int]("firstScalarDifference").toOption.map(pos => (d.hcursor.get[String]("id").getOrElse(""), pos)))
      .map((id, pos) => (id, baselineText(id), pos))
    val located = if positions.isEmpty then Map.empty[String, Option[Int]] else references.locate(positions)
    val annotated = divergences.map { d =>
      val id = d.hcursor.get[String]("id").getOrElse("")
      if d.hcursor.get[Int]("firstScalarDifference").isLeft then d
      else
        val candidate = located.getOrElse(id, None)
        d.deepMerge(Json.obj("retokenizedBaselineCandidate" -> candidate.fold(Json.Null)(Json.fromInt),
          "candidateDeleted" -> Json.fromBoolean(candidate.exists(c => !references.keptSourceIds(c))),
          "candidateEvidence" -> Json.fromString("retokenized decoded output; raw generated IDs are not returned by this API")))
    }
    val metrics = summary.mapObject(_.add("greedyDivergence", Json.fromValues(annotated))
      .add("serverMemory", Json.obj(Phases.zip(phases).map((label, p) => label -> p._2.mapObject(_.remove("server")))*))
      .add("size", Json.obj("sourceLogicalBytes" -> Json.fromLong(logicalBytes(baseline.checkpoint)), "prunedLogicalBytes" -> Json.fromLong(logicalBytes(pruned.checkpoint)),
        "predictedPayloadReductionBytes" -> Json.fromLong(references.predictedPayloadReductionBytes), "apfsPhysicalBytes" -> Json.Null)))
    ProcessingIO.write(directory.resolve("metrics.json"), metrics)
    ProcessingIO.write(directory.resolve("ratings-template.json"), Json.fromValues(suite.filter(_.category == CaseCategory.Japanese).map(c =>
      Json.obj("id" -> Json.fromString(c.id), "baseline" -> Json.Null, "pruned" -> Json.Null, "majorBreakdown" -> Json.False))))
    val m = metrics.hcursor
    def shown(field: String) = m.downField(field).focus.fold("None")(j => if j.isNull then "None" else j.asBoolean.fold(j.noSpaces)(b => if b then "True" else "False"))
    FileIO.writeNewUtf8(directory.resolve("REPORT.md"), Vector("# Before / after evaluation", "", s"Model: `${pruned.checkpoint}`", "",
      s"Runtime: ${runtime.name}. Static verification: passed before evaluation. Surviving logits gate: ${m.downField("logits").downField("passed").focus.fold("None")(_.noSpaces)}.", "",
      s"Coding gate: ${shown("codingGate")}; FIM gate: ${shown("fimGate")}; tool-call gate: ${shown("toolGate")}.", "",
      s"Japanese quality: ${m.downField("japaneseQuality").get[String]("state").getOrElse("")}. Fill ratings-template.json in a separate ratings file for a human assessment.", "",
      s"Contamination: ${m.get[String]("contaminationConclusion").getOrElse("")}. This measures lexical pruning effects and does not establish generation prohibition.", "",
      "Detailed measured values and denominators are in metrics.json; raw outputs are in baseline.jsonl and pruned.jsonl.", "",
      "All TASK.md goals are not claimed achieved. Quality, contamination and speed conclusions remain independent.", "").mkString("\n"))
    metrics

  /** Recompute the conclusions from a saved comparison and complete human ratings, without repeating inference. */
  def review(evaluation: Path, ratingsPath: Path, out: Path, options: ProcessingOptions): Json =
    def lines(name: String) = FileIO.readUtf8(evaluation.resolve(name)).linesIterator.filter(_.trim.nonEmpty)
      .map(line => io.circe.jawn.parse(line).fold(e => Diagnostics.invalid(evaluation.resolve(name), "line", e.message), identity)).toVector
    val ratings = me.nsmr.vocabsurgery.io.JsonIO.read(ratingsPath).asArray.getOrElse(Diagnostics.invalid(ratingsPath, "ratings", "expected an array"))
    val expected = lines("suite.jsonl").filter(_.hcursor.get[String]("category").contains("japanese")).flatMap(_.hcursor.get[String]("id").toOption).toSet
    Diagnostics.check(ratings.size == expected.size && ratings.flatMap(_.hcursor.get[String]("id").toOption).toSet == expected, ratingsPath, "ratings",
      "ratings must cover every Japanese case exactly once")
    val previous = me.nsmr.vocabsurgery.io.JsonIO.read(evaluation.resolve("metrics.json"))
    val tokenization = lines("tokenizer.jsonl")
    val result = summarize(lines("baseline.jsonl"), lines("pruned.jsonl"), tokenization, previous.hcursor.downField("logits").focus.getOrElse(Json.Null), Some(ratings))
      .mapObject(_.add("size", previous.hcursor.downField("size").focus.getOrElse(Json.Null)).add("serverMemory", previous.hcursor.downField("serverMemory").focus.getOrElse(Json.Null)))
    ProcessingIO.write(ProcessingIO.outputPath(options.outputRoot, out), result)
    result
