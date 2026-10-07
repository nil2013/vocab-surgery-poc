package me.nsmr.vocabsurgery.runtime

import io.circe.Json
import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Duration as JavaDuration
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO}
import me.nsmr.vocabsurgery.processing.ProcessingOptions

/** oMLX on macOS: every served model is an entry of a model directory of symlinks, served on loopback with no prefix or
  * Hugging Face cache, one request at a time, offline. Models are loaded and unloaded through its API.
  */
final case class OMlx(python: Path) extends InferenceRuntime with ModelResidency:
  val name = "omlx"
  private val http = HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(10)).build()

  /** The interpreter as given when it is looked up on PATH, otherwise absolute: the server runs in its own directory. */
  private val interpreter = if python.getNameCount == 1 && !python.isAbsolute then python.toString else python.toAbsolutePath.normalize.toString

  private[runtime] def command(directory: Path, port: Int): Vector[String] =
    Vector(interpreter, "-m", "omlx.cli", "serve", "--model-dir", directory.resolve("models").toString, "--base-path",
      directory.resolve("data").toString, "--host", "127.0.0.1", "--port", port.toString, "--no-cache", "--no-hf-cache",
      "--max-concurrent-requests", "1")

  def serve(request: ServeRequest, options: ProcessingOptions): RunningServer =
    val version = Runtimes.output(Vector(interpreter, "-c", "import importlib.metadata; print(importlib.metadata.version('omlx'))"), python)
    ServerProcess.start(name, request, options, request.models.map(_.checkpoint), directory =>
      val models = Files.createDirectory(directory.resolve("models"))
      request.models.foreach(model => Files.createSymbolicLink(models.resolve(model.name), model.checkpoint.toRealPath()))
      (command(directory, request.port), Map("HF_HUB_OFFLINE" -> "1", "TRANSFORMERS_OFFLINE" -> "1"),
        Json.obj("omlxVersion" -> Json.fromString(version), "prefixCache" -> Json.False, "concurrency" -> Json.fromInt(1))))

  private def post(endpoint: Endpoint, model: String, action: String): Int =
    val uri = URI.create(s"${endpoint.base}/models/${URLEncoder.encode(model, StandardCharsets.UTF_8).replace("+", "%20")}/$action")
    http.send(HttpRequest.newBuilder(uri).timeout(JavaDuration.ofMinutes(10)).header("Content-Type", "application/json")
      .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding()).statusCode()

  /** Load a model; a just-unloaded model's memory can lag oMLX's admission check, so HTTP 507 alone is retried for 20 s. */
  def load(endpoint: Endpoint, model: String): Unit =
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
    var status = post(endpoint, model, "load")
    while status == 507 && System.nanoTime() < deadline do
      Thread.sleep(1000L)
      status = post(endpoint, model, "load")
    Diagnostics.check(status / 100 == 2, Path.of(endpoint.base.toString), "load", s"loading $model answered HTTP $status")

  /** Unload a model; HTTP 400 means it was not loaded. */
  def unload(endpoint: Endpoint, model: String): Unit =
    val status = post(endpoint, model, "unload")
    Diagnostics.check(status / 100 == 2 || status == 400, Path.of(endpoint.base.toString), "unload", s"unloading $model answered HTTP $status")

/** The experimental TensorFold clone set up by scripts/flash-next/setup-tensorfold.sh, serving one checkpoint on CUDA.
  * Its requests switch MTP drafts with a `draft` field, its log reports each request's accepted drafts, and a draft list
  * of upstream token ids can replace its built-in one.
  */
final case class TensorFoldClone(directory: Path, context: Int = 65536, pleOnSsd: Boolean = true, draftVocabulary: Option[Path] = None,
    cudaHome: Path = Path.of("/usr/local/cuda"), promptCache: Boolean = true) extends InferenceRuntime with DraftSwitch with DraftStatistics with DraftList:
  val name = "tensorfold"
  /** The clone as an absolute path: the server runs in its own directory. */
  private val home = directory.toAbsolutePath.normalize
  private val done = raw"done req-\S+ prompt=(\d+) .*?tokens=(\d+) .*?tok/s=([\d.]+) .*?rounds=(\d+) accepted=(\d+)/(\d+)".r.unanchored

  private[runtime] def command(model: ServedModel, port: Int): Vector[String] =
    Vector(home.resolve(".venv/bin/tensorfold").toString, "serve", model.checkpoint.toAbsolutePath.normalize.toString, "--host", "127.0.0.1", "--port", port.toString,
      "--name", model.name) ++ (if pleOnSsd then Vector("--ple-on-ssd") else Vector.empty) ++ Vector("--context", context.toString) ++
      draftVocabulary.toVector.flatMap(path => Vector("--draft-vocab", path.toAbsolutePath.normalize.toString)) ++
      (if promptCache then Vector.empty else Vector("--prompt-cache-gib", "0"))

  private[runtime] def environment: Map[String, String] =
    Map("TORCH_EXTENSIONS_DIR" -> home.resolve(".torch-extensions").toString,
      "PATH" -> (cudaHome.resolve("bin").toString + java.io.File.pathSeparator + sys.env.getOrElse("PATH", "/usr/bin:/bin")))

  def serve(request: ServeRequest, options: ProcessingOptions): RunningServer =
    Diagnostics.check(request.models.size == 1, directory, "serve", "the TensorFold clone serves one checkpoint at a time")
    Diagnostics.check(Files.isExecutable(directory.resolve(".venv/bin/tensorfold")), directory, "serve", "no clone here; run scripts/flash-next/setup-tensorfold.sh")
    draftVocabulary.foreach(path => Diagnostics.check(Files.isRegularFile(path), path, "draft vocabulary", "no such file"))
    val revision = Runtimes.output(Vector("git", "-C", directory.toString, "rev-parse", "HEAD"), directory)
    ServerProcess.start(name, request, options, request.models.map(_.checkpoint) :+ directory, _ =>
      (command(request.models.head, request.port), environment, Json.obj("clone" -> Json.fromString(directory.toAbsolutePath.normalize.toString),
        "cloneRevision" -> Json.fromString(revision), "context" -> Json.fromInt(context), "pleOnSsd" -> Json.fromBoolean(pleOnSsd),
        "promptCache" -> Json.fromBoolean(promptCache),
        "draftVocabulary" -> draftVocabulary.fold(Json.Null)(path => Json.obj("path" -> Json.fromString(path.toAbsolutePath.normalize.toString),
          "sha256" -> Json.fromString(FileIO.sha256(path)))))))

  def draftField(enabled: Boolean): (String, Json) = "draft" -> Json.fromBoolean(enabled)

  def builtinDraftList: Path = home.resolve("src/tensorfold/families/qwen4_exp/cuda/draft_vocab.txt")

  def draftRecords(log: Path): Vector[DraftRecord] =
    FileIO.readUtf8(log).linesIterator.collect { case done(prompt, tokens, rate, rounds, accepted, proposed) =>
      DraftRecord(prompt.toInt, tokens.toInt, rate.toDouble, rounds.toInt, accepted.toInt, proposed.toInt)
    }.toVector

object Runtimes:
  /** The trimmed standard output of a short command that must succeed. */
  private[runtime] def output(command: Vector[String], at: Path): String =
    val process = ProcessBuilder(command.asJava).redirectErrorStream(true).start()
    process.getOutputStream.close()
    val text = String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
    Diagnostics.check(process.waitFor(60L, TimeUnit.SECONDS) && process.exitValue() == 0, at, "runtime", s"${command.head} failed: ${text.take(500)}")
    text
