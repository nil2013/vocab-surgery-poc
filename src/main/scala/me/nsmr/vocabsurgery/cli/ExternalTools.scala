package me.nsmr.vocabsurgery.cli

import io.circe.Json
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.processing.*
import me.nsmr.vocabsurgery.unicode.UnicodeData

/** Invoke fixed repository helpers with argument vectors; never interpolate a shell command. */
object ExternalTools:
  /** Build the mandatory offline tokenizer validator, using disposable projection and ICU NFC inputs. */
  def reference(python: Path, helpers: Path): CheckpointProcessor.ReferenceValidator = (prepared, output) =>
    val directory = Files.createTempDirectory("vocab-reference-")
    try
      val mapping = directory.resolve("mapping.json")
      val nfc = directory.resolve("nfc.json")
      ProcessingIO.write(mapping, prepared.domain.projection.compactToOriginalJson)
      ProcessingIO.write(nfc, Json.fromValues(prepared.domain.policy.normalizationSamples.map(cp =>
        Json.arr(Json.fromString(cp.text), Json.fromString(UnicodeData.normalize(TokenSurface(cp.text)).text)))))
      val report = directory.resolve("result.json")
      run(python, helpers.resolve("check_tokenizer.py"), Vector("--source", prepared.source.directory.toString, "--model", output.toString,
        "--mapping", mapping.toString, "--nfc", nfc.toString, "--report", report.toString), directory.resolve("helper.log"), 180)
      JsonIO.read(report)
    finally Using.resource(Files.walk(directory)) { paths => paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete) }

  /** Run a repository helper with a new combined-output log; terminate its descendants on timeout. */
  def run(python: Path, script: Path, args: Vector[String], log: Path, timeoutSeconds: Int): Unit =
    require(timeoutSeconds > 0, "timeout must be positive")
    Diagnostics.check(Files.isRegularFile(script), script, "helper", "helper script is missing")
    Diagnostics.check(!Files.exists(log), log, "helper log", "log already exists")
    val process = new ProcessBuilder((Vector(python.toString, script.toString) ++ args).asJava)
      .redirectErrorStream(true).redirectOutput(log.toFile).start()
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds.toLong)
    var waiting = 0
    while !process.waitFor(1L, TimeUnit.SECONDS) && System.nanoTime() < deadline do
      waiting += 1
      if waiting % 15 == 0 then System.err.println(s"Helper running; progress log: $log")
    if process.isAlive then
      process.descendants().forEach(_.destroyForcibly())
      process.destroyForcibly()
      process.waitFor(5L, TimeUnit.SECONDS)
      Diagnostics.invalid(script, "helper", s"helper exceeded $timeoutSeconds seconds; log: $log")
    Diagnostics.check(process.exitValue() == 0, script, "helper", s"helper failed (exit ${process.exitValue()}): ${FileIO.readUtf8(log).take(8000)}")
