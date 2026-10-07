package me.nsmr.vocabsurgery.evaluation

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, LinkOption, Path, StandardCopyOption}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.Diagnostics
import me.nsmr.vocabsurgery.processing.{ProcessingIO, ProcessingOptions}
import me.nsmr.vocabsurgery.runtime.{InferenceRuntime, ServeRequest}

/** An external evaluation material's documented CLI to run against a served endpoint (B1 in
  * docs/EVALUATION_ARCHITECTURE.md). The material keeps its runner, scoring, code execution and result formats; this
  * project only serves the model, runs the command with VOCAB_ENDPOINT and VOCAB_MODELS set, and records where and
  * how it ran. `copy` is a new directory under the output root to run in, so a runner that writes into its own tree
  * leaves the material untouched.
  */
final case class ExternalRun(material: Path, copy: Option[Path], command: Vector[String]):
  require(command.nonEmpty, "no command to run")

object ExternalRun:
  /** The material's git revision, or None outside a work tree. */
  private def revision(material: Path): Option[String] =
    try
      val process = ProcessBuilder("git", "-C", material.toString, "rev-parse", "HEAD").redirectErrorStream(true).start()
      process.getOutputStream.close()
      val text = String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      Option.when(process.waitFor(30L, TimeUnit.SECONDS) && process.exitValue() == 0)(text)
    catch case _: java.io.IOException => None

  /** Copy the material's tree, without its .git, into a new directory under the output root. */
  private def copyTree(material: Path, copy: Path, options: ProcessingOptions): Path =
    val target = ProcessingIO.createDirectory(options.outputRoot, copy, Vector(material))
    val source = material.toRealPath()
    Using.resource(Files.walk(source)) { paths =>
      paths.iterator().asScala.filterNot(path => source.relativize(path).iterator().asScala.exists(_.toString == ".git")).foreach { path =>
        val destination = target.resolve(source.relativize(path).toString)
        if Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) then Files.createDirectories(destination)
        else Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS)
      }
    }
    target

  /** Serve, run the material's command in the material (or its copy), stop, and record the run in the server directory. */
  def run(runtime: InferenceRuntime, request: ServeRequest, external: ExternalRun, options: ProcessingOptions): Json =
    Diagnostics.check(Files.isDirectory(external.material), external.material, "material", "not a directory")
    val workDirectory = external.copy.fold(external.material.toRealPath())(copy => copyTree(external.material, copy, options))
    val server = runtime.serve(request, options)
    val exitCode =
      try
        val builder = ProcessBuilder(external.command.asJava).directory(workDirectory.toFile).inheritIO()
        builder.environment().put("VOCAB_ENDPOINT", server.endpoint.base.toString)
        builder.environment().put("VOCAB_MODELS", server.endpoint.models.mkString(","))
        builder.start().waitFor()
      finally server.close()
    val record = Json.obj("material" -> Json.fromString(external.material.toRealPath().toString),
      "materialRevision" -> revision(external.material).fold(Json.Null)(Json.fromString),
      "workDirectory" -> Json.fromString(workDirectory.toString), "command" -> Json.fromValues(external.command.map(Json.fromString)),
      "exitCode" -> Json.fromInt(exitCode), "server" -> server.metadata)
    ProcessingIO.write(server.log.getParent.resolve("external-run.json"), record)
    record
