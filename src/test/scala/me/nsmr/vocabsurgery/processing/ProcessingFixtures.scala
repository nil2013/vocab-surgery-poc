package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.JsonIO
import me.nsmr.vocabsurgery.model.{DomainFixtures, ModelSnapshot, SafetensorsIO}

object ProcessingFixtures:
  def withModel[A](f: (Path, Path, Path) => A): A = withSnapshot(DomainFixtures.snapshot())(f)

  /** Write `fixture` as a checkpoint directory with a deterministic payload; pass (temporary root, input, output root). */
  def withSnapshot[A](fixture: ModelSnapshot)(f: (Path, Path, Path) => A): A =
    val directory = Files.createTempDirectory("vocab-processing-")
    val input = Files.createDirectory(directory.resolve("input"))
    val output = directory.resolve("outputs")
    try
      JsonIO.writeNew(input.resolve("config.json"), fixture.config.original)
      JsonIO.writeNew(input.resolve("generation_config.json"), fixture.generation)
      JsonIO.writeNew(input.resolve("tokenizer_config.json"), fixture.tokenizerConfig)
      val byteLevel = Json.obj("type" -> Json.fromString("ByteLevel"), "add_prefix_space" -> Json.False, "trim_offsets" -> Json.True, "use_regex" -> Json.False)
      val tokenizer = fixture.tokenizer.original.mapObject(_.add("version", Json.fromString("1.0")).add("truncation", Json.Null).add("padding", Json.Null)
        .add("pre_tokenizer", byteLevel).add("post_processor", byteLevel).add("decoder", byteLevel))
      JsonIO.writeNew(input.resolve("tokenizer.json"), tokenizer)
      JsonIO.writeNew(input.resolve("model.safetensors.index.json"), fixture.index.original)
      fixture.chatTemplate.foreach(template => Files.writeString(input.resolve("chat_template.jinja"), template.text))
      fixture.tokenizerCompanions.foreach((name, text) => Files.writeString(input.resolve(name), text))
      fixture.familyFiles.foreach((name, json) => JsonIO.writeNew(input.resolve(name), json))
      Files.writeString(input.resolve("README.md"), "Synthetic checkpoint fixture\n")
      val header = SafetensorsIO.encodeHeaderJson(fixture.shards.head.originalHeader)
      val size = fixture.shards.head.fileSize.longValue - fixture.shards.head.payloadStart.longValue
      val payload = (0 until size.toInt).map(i => (i * 31).toByte).toArray
      Files.write(input.resolve("input.safetensors"), header ++ payload)
      f(directory, input, output)
    finally Using.resource(Files.walk(directory)) { paths =>
      paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
    }

  val reference: CheckpointProcessor.ReferenceValidator = (plan, output) =>
    val json = JsonIO.read(output.resolve("tokenizer.json"))
    assert(json == plan.domain.json("tokenizer.json"))
    Json.obj("passed" -> Json.True, "provider" -> Json.fromString("synthetic-test-contract"))
