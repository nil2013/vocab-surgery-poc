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

  /** Token ids from Hugging Face tokenizers through scripts/tokenization.py, for the tokenizer.json at `tokenizer`. */
  def tokenization(python: Path, helpers: Path, tokenizer: Path): me.nsmr.vocabsurgery.runtime.ReferenceTokenization =
    new me.nsmr.vocabsurgery.runtime.ReferenceTokenization:
      private def call(command: String, texts: Vector[String]): Json =
        val directory = Files.createTempDirectory("vocab-tokenization-")
        try
          val input = directory.resolve("texts.json")
          val out = directory.resolve("result.json")
          ProcessingIO.write(input, Json.fromValues(texts.map(Json.fromString)))
          run(python, helpers.resolve("tokenization.py"), Vector(command, "--tokenizer", tokenizer.toString, "--texts", input.toString,
            "--out", out.toString), directory.resolve("helper.log"), 3600)
          JsonIO.read(out, 1L << 30)
        finally Using.resource(Files.walk(directory)) { paths => paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete) }
      def counts(texts: Vector[String]): Map[Int, Long] =
        val out = call("count", texts)
        JsonFields.array(out, tokenizer, "counts").map { pair =>
          val values = JsonFields.array(pair, tokenizer, "counts")
          JsonFields.int(values(0), tokenizer, "id") -> JsonFields.long(values(1), tokenizer, "count")
        }.toMap
      def encode(texts: Vector[String]): Vector[Vector[Int]] =
        JsonFields.array(call("encode", texts), tokenizer, "encodings").map(ids => JsonFields.array(ids, tokenizer, "ids").map(JsonFields.int(_, tokenizer, "id")))

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
