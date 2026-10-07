package me.nsmr.vocabsurgery.evaluation

import io.circe.{Json, JsonObject}
import java.nio.file.Path
import me.nsmr.vocabsurgery.io.{Diagnostics, JsonFields, JsonIO}
import me.nsmr.vocabsurgery.runtime.{DraftRecord, DraftStatistics, DraftSwitch, InferenceRuntime, RunningServer, ServeRequest}
import me.nsmr.vocabsurgery.processing.{ProcessingIO, ProcessingOptions}

/** Fixed prompts for speed: each is sent `trials` times per draft mode, the first `warmups` left out of the summary. */
final case class SpeedProbes(prompts: Vector[(String, String)], trials: Int, warmups: Int, maxTokens: Int, temperature: Double, extra: JsonObject):
  require(prompts.nonEmpty && prompts.map(_._1).distinct.size == prompts.size, "prompt names must be present and unique")
  require(trials > warmups && warmups >= 0, "trials must exceed warm-ups")

object SpeedProbes:
  def load(path: Path): SpeedProbes = Diagnostics.guard(path, "speed prompts") {
    val json = JsonIO.read(path)
    def field(name: String) = JsonFields.field(json, name, path, "speed prompts")
    SpeedProbes(JsonFields.array(field("prompts"), path, "prompts").map(p =>
        JsonFields.text(JsonFields.field(p, "name", path, "prompts"), path, "name") -> JsonFields.text(JsonFields.field(p, "text", path, "prompts"), path, "text")),
      JsonFields.int(field("trials"), path, "trials"), JsonFields.int(field("warmups"), path, "warmups"),
      JsonFields.int(field("maxTokens"), path, "maxTokens"), field("temperature").asNumber.map(_.toDouble).getOrElse(Diagnostics.invalid(path, "temperature", "expected a number")),
      json.hcursor.downField("extra").focus.flatMap(_.asObject).getOrElse(JsonObject.empty))
  }

/** One timed request; `draft` is None on a runtime without a draft switch. */
final case class SpeedRow(prompt: String, draft: Option[Boolean], trial: Int, warmup: Boolean, response: ChatResponse):
  def mode: String = draft.fold("default")(enabled => if enabled then "mtp" else "serial")
  def json: Json = Json.obj("prompt" -> Json.fromString(prompt), "mode" -> Json.fromString(mode), "trial" -> Json.fromInt(trial),
    "warmup" -> Json.fromBoolean(warmup), "ttft" -> Json.fromDoubleOrNull(response.ttftSeconds), "total" -> Json.fromDoubleOrNull(response.seconds),
    "decodeTokensPerSecond" -> response.decodeTokensPerSecond.fold(Json.Null)(Json.fromDoubleOrNull),
    "completionTokens" -> response.completionTokens.fold(Json.Null)(Json.fromInt), "promptTokens" -> response.promptTokens.fold(Json.Null)(Json.fromInt),
    "chars" -> Json.fromInt(response.text.length), "text" -> Json.fromString(response.text))

/** Request-level draft statistics matched to the requests sent, and their totals per group. */
final case class DraftAttribution(group: String, name: String, record: DraftRecord)

object DraftAttribution:
  /** Match a runtime's per-request records, in finishing order, to the requests sent one at a time. The counts and every
    * completion-token count must agree; anything else means the log holds other traffic.
    */
  def attribute(records: Vector[DraftRecord], sent: Vector[(String, String, Option[Int])], log: Path): Vector[DraftAttribution] =
    Diagnostics.check(records.size == sent.size, log, "draft statistics", s"${records.size} logged requests but ${sent.size} sent")
    records.zip(sent).map { case (record, (group, name, tokens)) =>
      Diagnostics.check(tokens.contains(record.completionTokens), log, "draft statistics",
        s"$name generated ${tokens.getOrElse("?")} tokens but the log says ${record.completionTokens}")
      DraftAttribution(group, name, record)
    }

  /** Accepted / proposed drafts, tokens per round, per group; requests without drafts are left out. */
  def totals(rows: Vector[DraftAttribution]): Json =
    Json.obj(rows.filter(_.record.proposed > 0).groupBy(_.group).toVector.sortBy(_._1).map { (group, items) =>
      val records = items.map(_.record)
      val (accepted, proposed, tokens, rounds) = (records.map(_.accepted).sum, records.map(_.proposed).sum, records.map(_.completionTokens).sum, records.map(_.rounds).sum)
      group -> Json.obj("accepted" -> Json.fromInt(accepted), "proposed" -> Json.fromInt(proposed), "rate" -> Json.fromDoubleOrNull(accepted.toDouble / proposed),
        "tokensPerRound" -> Json.fromDoubleOrNull(tokens.toDouble / rounds))
    }*)

/** Decode speed, TTFT and, on runtimes that report them, draft acceptance for fixed prompts. */
object SpeedMeasurement:
  private def median(values: Vector[Double]): Option[Double] =
    val sorted = values.sorted
    Option.when(sorted.nonEmpty)(if sorted.size % 2 == 1 then sorted(sorted.size / 2) else (sorted(sorted.size / 2 - 1) + sorted(sorted.size / 2)) / 2)

  /** Send every prompt `trials` times per draft mode (both modes when the runtime can switch drafts), one at a time. */
  def measure(client: ChatClient, model: String, probes: SpeedProbes, switch: Option[DraftSwitch], progress: SpeedRow => Unit = _ => ()): Vector[SpeedRow] =
    val modes = switch.fold(Vector(Option.empty[Boolean]))(_ => Vector(Some(true), Some(false)))
    for
      (name, text) <- probes.prompts
      draft <- modes
      trial <- 0 until probes.trials
    yield
      val extra = draft.fold(probes.extra)(enabled => probes.extra.add(switch.get.draftField(enabled)._1, switch.get.draftField(enabled)._2))
      val request = ChatRequest(model, ChatInput.Messages(Vector(Json.obj("role" -> Json.fromString("user"), "content" -> Json.fromString(text)))),
        probes.maxTokens, Some(probes.temperature), extra = extra)
      val row = SpeedRow(name, draft, trial, trial < probes.warmups, client.stream(request))
      progress(row)
      row

  /** Medians per prompt and mode over the measured (non-warm-up) rows. */
  def summary(rows: Vector[SpeedRow]): Json =
    Json.obj(rows.filterNot(_.warmup).groupBy(row => s"${row.prompt}/${row.mode}").toVector.sortBy(_._1).map { (key, group) =>
      key -> Json.obj("decodeTokensPerSecondMedian" -> median(group.flatMap(_.response.decodeTokensPerSecond)).fold(Json.Null)(Json.fromDoubleOrNull),
        "ttftMedian" -> median(group.map(_.response.ttftSeconds)).fold(Json.Null)(Json.fromDoubleOrNull),
        "completionTokens" -> Json.fromValues(group.map(_.response.completionTokens.fold(Json.Null)(Json.fromInt))),
        "charsPerSecondMedian" -> median(group.map(r => r.response.text.length / math.max(1e-9, r.response.seconds - r.response.ttftSeconds))).fold(Json.Null)(Json.fromDoubleOrNull))
    }*)

  /** Serve, measure, stop, then attribute the runtime's draft statistics; writes `output` (a new file) and returns it. */
  def run(runtime: InferenceRuntime, request: ServeRequest, probes: SpeedProbes, output: Path, options: ProcessingOptions,
      progress: SpeedRow => Unit = _ => ()): Json =
    val target = ProcessingIO.outputPath(options.outputRoot, output, request.models.map(_.checkpoint))
    val model = request.models.head.name
    val (server, rows) =
      val server = runtime.serve(request, options)
      try (server, measure(ChatClient(server.endpoint.base), model, probes, Option(runtime).collect { case s: DraftSwitch => s }, progress))
      finally server.close()
    val drafts = runtime match
      case statistics: DraftStatistics =>
        val sent = rows.map(row => (s"${row.prompt}/${row.mode}", s"${row.prompt}/${row.mode}#${row.trial}", row.response.completionTokens))
        DraftAttribution.totals(DraftAttribution.attribute(statistics.draftRecords(server.log), sent, server.log))
      case _ => Json.Null
    val result = Json.obj("server" -> server.metadata, "probes" -> Json.obj("trials" -> Json.fromInt(probes.trials), "warmups" -> Json.fromInt(probes.warmups),
        "maxTokens" -> Json.fromInt(probes.maxTokens), "temperature" -> Json.fromDoubleOrNull(probes.temperature), "extra" -> Json.fromJsonObject(probes.extra)),
      "summary" -> summary(rows), "drafts" -> drafts, "rows" -> Json.fromValues(rows.map(_.json)))
    ProcessingIO.write(target, result)
    result
