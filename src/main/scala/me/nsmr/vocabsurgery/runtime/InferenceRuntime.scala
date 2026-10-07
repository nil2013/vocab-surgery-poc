package me.nsmr.vocabsurgery.runtime

import io.circe.Json
import java.net.{InetAddress, ServerSocket, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration as JavaDuration
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO}
import me.nsmr.vocabsurgery.processing.{ProcessingIO, ProcessingOptions}

/** A checkpoint to serve under a model name. */
final case class ServedModel(name: String, checkpoint: Path):
  require(name.nonEmpty && name.forall(c => c.isLetterOrDigit || "-_.".contains(c)), s"invalid served model name $name")

/** An OpenAI-compatible endpoint: `base` ends in /v1 and serves `models`. */
final case class Endpoint(base: URI, models: Vector[String]):
  def json: Json = Json.obj("base" -> Json.fromString(base.toString), "models" -> Json.fromValues(models.map(Json.fromString)))

/** What to serve and where: `directory` is a new directory under the output root for the log and metadata. */
final case class ServeRequest(models: Vector[ServedModel], directory: Path, port: Int, readyTimeout: FiniteDuration = 15.minutes):
  require(models.nonEmpty, "nothing to serve")
  require(models.map(_.name).distinct.size == models.size, "served model names must be unique")
  require(port > 0 && port < 65536, "invalid port")

/** A server process started by an inference runtime; closing it stops the process and its descendants. */
trait RunningServer extends AutoCloseable:
  def runtime: String
  def endpoint: Endpoint
  def pid: Long
  def log: Path
  /** What was started: command, versions and options, as written to server.json. */
  def metadata: Json
  def alive: Boolean

/** A way to serve checkpoints behind an OpenAI-compatible endpoint (B2 in docs/EVALUATION_ARCHITECTURE.md). */
trait InferenceRuntime:
  def name: String
  def serve(request: ServeRequest, options: ProcessingOptions = ProcessingOptions()): RunningServer

/** A runtime whose requests can switch its MTP drafts on and off. */
trait DraftSwitch:
  /** The request field that switches drafts for one request. */
  def draftField(enabled: Boolean): (String, Json)

/** Per-request draft statistics a runtime writes to its log, in the order requests finished. */
final case class DraftRecord(promptTokens: Int, completionTokens: Int, tokensPerSecond: Double, rounds: Int, accepted: Int, proposed: Int)

/** A runtime whose log reports how many drafted tokens each request accepted. */
trait DraftStatistics:
  def draftRecords(log: Path): Vector[DraftRecord]

/** A runtime whose served models are loaded and unloaded explicitly through its API. */
trait ModelResidency:
  def load(endpoint: Endpoint, model: String): Unit
  def unload(endpoint: Endpoint, model: String): Unit

/** A command run against a served endpoint, as `serve` and external evaluation materials do. */
object EndpointCommand:
  /** Run `command` in `directory` (the current one when None) with VOCAB_ENDPOINT (the /v1 base) and VOCAB_MODELS
    * (comma-separated served names) set, and return its exit code. Its standard output and error go to `output`, so
    * the caller's standard output stays free for its own result.
    */
  def run(command: Vector[String], endpoint: Endpoint, directory: Option[Path], output: java.io.OutputStream): Int =
    require(command.nonEmpty, "no command to run")
    val builder = ProcessBuilder(command.asJava).redirectInput(ProcessBuilder.Redirect.INHERIT).redirectErrorStream(true)
    directory.foreach(path => builder.directory(path.toFile))
    builder.environment().put("VOCAB_ENDPOINT", endpoint.base.toString)
    builder.environment().put("VOCAB_MODELS", endpoint.models.mkString(","))
    val process = builder.start()
    Using.resource(process.getInputStream)(_.transferTo(output))
    output.flush()
    process.waitFor()

/** Starting, readiness and stopping shared by the runtimes. */
private[runtime] object ServerProcess:
  private val http = HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(5)).build()

  def endpoint(port: Int, models: Vector[String]): Endpoint = Endpoint(URI.create(s"http://127.0.0.1:$port/v1"), models)

  def serving(endpoint: Endpoint): Boolean =
    try http.send(HttpRequest.newBuilder(URI.create(s"${endpoint.base}/models")).timeout(JavaDuration.ofSeconds(5)).GET().build(),
      HttpResponse.BodyHandlers.discarding()).statusCode() == 200
    catch case _: java.io.IOException => false

  def requireFreePort(port: Int, at: Path): Unit =
    val free = try { ServerSocket(port, 1, InetAddress.getLoopbackAddress).close(); true } catch case _: java.io.IOException => false
    Diagnostics.check(free, at, "port", s"127.0.0.1:$port is already in use")

  /** Create the server directory, start `command` with its output in server.log, record server.json and wait until the
    * endpoint lists its models. A process that exits or misses the deadline is stopped and reported with its log tail.
    */
  def start(runtime: String, request: ServeRequest, options: ProcessingOptions, protectedRoots: Vector[Path],
      prepare: Path => (Vector[String], Map[String, String], Json)): RunningServer =
    requireFreePort(request.port, request.directory)
    val directory = ProcessingIO.createDirectory(options.outputRoot, request.directory, protectedRoots)
    val (command, environment, details) = prepare(directory)
    val log = directory.resolve("server.log")
    val builder = ProcessBuilder(command.asJava).directory(directory.toFile).redirectErrorStream(true).redirectOutput(log.toFile)
    builder.environment().putAll(environment.asJava)
    val process = builder.start()
    process.getOutputStream.close()
    val served = endpoint(request.port, request.models.map(_.name))
    val metadata = Json.obj("runtime" -> Json.fromString(runtime), "pid" -> Json.fromLong(process.pid()), "endpoint" -> served.json,
      "command" -> Json.fromValues(command.map(Json.fromString)),
      "models" -> Json.fromValues(request.models.map(m => Json.obj("name" -> Json.fromString(m.name), "checkpoint" -> Json.fromString(m.checkpoint.toString))))).deepMerge(details)
    ProcessingIO.write(directory.resolve("server.json"), metadata)
    val server = Running(runtime, served, process, log, metadata)
    val deadline = System.nanoTime() + request.readyTimeout.toNanos
    while !serving(served) do
      if !process.isAlive then
        server.close()
        Diagnostics.invalid(log, "serve", s"$runtime exited (code ${process.exitValue()}) before serving: ${tail(log)}")
      if System.nanoTime() > deadline then
        server.close()
        Diagnostics.invalid(log, "serve", s"$runtime did not serve within ${request.readyTimeout}: ${tail(log)}")
      Thread.sleep(2000L)
    server

  private def tail(log: Path): String = if Files.isRegularFile(log) then FileIO.readUtf8(log).linesIterator.toVector.takeRight(20).mkString("\n") else ""

  private final case class Running(runtime: String, endpoint: Endpoint, process: Process, log: Path, metadata: Json) extends RunningServer:
    def pid: Long = process.pid()
    def alive: Boolean = process.isAlive
    def close(): Unit =
      val descendants = process.descendants().iterator().asScala.toVector
      process.destroy()
      descendants.foreach(_.destroy())
      if !process.waitFor(30L, TimeUnit.SECONDS) then process.destroyForcibly()
      descendants.filter(_.isAlive).foreach(_.destroyForcibly())
      process.waitFor(10L, TimeUnit.SECONDS)

/** The peak resident memory of a process, sampled with `ps` every `interval` until closed (None until a sample). */
final class ResourceSampler(pid: Long, interval: scala.concurrent.duration.FiniteDuration = 250.millis) extends AutoCloseable:
  @volatile private var peak: Option[Long] = None
  @volatile private var running = true
  private val thread = Thread(() =>
    while running do
      try
        val process = ProcessBuilder("ps", "-o", "rss=", "-p", pid.toString).redirectErrorStream(true).start()
        val text = String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim
        if process.waitFor(5L, TimeUnit.SECONDS) && text.nonEmpty && text.forall(_.isDigit) then
          peak = Some(math.max(peak.getOrElse(0L), text.toLong * 1024))
      catch case _: java.io.IOException => ()
      Thread.sleep(interval.toMillis))
  thread.setDaemon(true)
  thread.start()
  def peakBytes: Option[Long] = peak
  def close(): Unit =
    running = false
    thread.join(5000L)
