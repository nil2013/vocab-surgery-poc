package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.model.*
import me.nsmr.vocabsurgery.core.*

class ProcessingSuite extends munit.FunSuite:
  private val kanji = Path.of("kanji-data")

  test("policy round trip rejects unknown fields, unsupported versions, bad scalars and missing files") {
    val path = Path.of("synthetic-policy.json")
    val settings = PolicySettings()
    assertEquals(PolicySettings.parse(settings.json, path), settings)
    Vector(Json.obj("oops" -> Json.True), Json.obj("formatVersion" -> Json.fromInt(2)),
      Json.obj("unicodeVersion" -> Json.fromString("16.0.0")), Json.obj("japaneseExtra" -> Json.arr(Json.fromInt(0xd800))),
      Json.obj("explicitForbidden" -> Json.arr(Json.fromInt(-1))), Json.obj("protectedIds" -> Json.arr(Json.fromInt(-1))),
      Json.obj("forbiddenScripts" -> Json.arr(Json.fromString("nonsense"))), Json.obj("unregisteredRows" -> Json.fromString("keep")),
      Json.obj("parameterCount" -> Json.fromString("infer")), Json.obj("pruneHanOutsideReference" -> Json.Null))
      .foreach(json => intercept[DataException](PolicySettings.parse(json, path)))
    intercept[DataException](PolicySettings.load(Path.of("nonexistent-policy.json")))
  }

  test("saved plan creates analysis artifacts and verifies input identity and deterministic operations") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      val path = CheckpointProcessor.plan(input, kanji, PolicySettings(), output.resolve("runs/test"), options)
      val loaded = PlanStore.loadVerified(path)
      assertEquals(loaded.domain.removedRows, 4)
      assert(Files.isRegularFile(path.getParent.resolve("kanji-missing-texts.jsonl")))
      assert(Files.isRegularFile(path.getParent.resolve("tokens.jsonl")))
      assertEquals(loaded.domain.projection.originalToCompactJson, JsonIO.read(path.getParent.resolve("original-to-compact.json")))
      val original = JsonIO.read(path)
      Files.writeString(path, original.mapObject(_.add("domainSha256", Json.fromString("bad"))).noSpaces)
      intercept[DataException](PlanStore.loadVerified(path))
      Files.writeString(path, original.noSpaces)
      Files.writeString(input.resolve("README.md"), "modified after plan\n")
      intercept[DataException](PlanStore.loadVerified(path))
    }
  }

  test("apply and verify project exact packed rows with copy fallback and independent outputs") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output, cloner = FileCloner.disabled, bufferBytes = 7)
      val path = CheckpointProcessor.plan(input, kanji, PolicySettings(), output.resolve("runs/apply"), options)
      val before = FileIO.sha256(input.resolve("input.safetensors"))
      val model = CheckpointProcessor.applyPlan(path, output.resolve("models/pruned"), ProcessingFixtures.reference, options)
      assertEquals(FileIO.sha256(input.resolve("input.safetensors")), before)
      assertEquals(SnapshotLoader.load(model).config.vocabSize, PlanStore.loadVerified(path).domain.projection.compactSize)
      assertEquals(CheckpointProcessor.verifyManifest(path.getParent.resolve("manifest.json"), ProcessingFixtures.reference, options).tensors.size, 7)
      val manifestPath = path.getParent.resolve("manifest.json")
      val manifest = JsonIO.read(manifestPath)
      Files.writeString(manifestPath, manifest.mapObject(_.add("compactRows", Json.fromInt(1))).noSpaces)
      intercept[DataException](CheckpointProcessor.verifyManifest(manifestPath, ProcessingFixtures.reference, options))
      Files.writeString(manifestPath, manifest.noSpaces)
      assert(!Files.isSameFile(input.resolve("README.md"), model.resolve("README.md")))
      assert(Files.isRegularFile(path.getParent.resolve("publication.json")))
      intercept[DataException](CheckpointProcessor.applyPlan(path, model, ProcessingFixtures.reference, options))
      val checkpoint = model.resolve("input.safetensors")
      Using.resource(FileChannel.open(checkpoint, StandardOpenOption.WRITE)) { channel =>
        channel.write(ByteBuffer.wrap(Array[Byte](0x42)), SafetensorsIO.read(checkpoint).payloadStart.longValue)
      }
      intercept[DataException](CheckpointProcessor.verifyManifest(path.getParent.resolve("manifest.json"), ProcessingFixtures.reference, options))
    }
  }

  test("insufficient space stops before staging; failed validation leaves diagnostics without publication") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output, cloner = FileCloner.disabled)
      val path = CheckpointProcessor.plan(input, kanji, PolicySettings(), output.resolve("runs/failure"), options)
      val target = output.resolve("models/failed")
      intercept[DataException](CheckpointProcessor.applyPlan(path, target, ProcessingFixtures.reference, options.copy(availableBytes = _ => 0L)))
      assert(!Files.exists(output.resolve(".staging")))
      val reject: CheckpointProcessor.ReferenceValidator = (_, _) => Json.obj("passed" -> Json.False)
      intercept[DataException](CheckpointProcessor.applyPlan(path, target, reject, options))
      assert(!Files.exists(target))
      Using.resource(Files.list(output.resolve(".staging"))) { paths =>
        val stages = paths.iterator().asScala.toVector
        assertEquals(stages.size, 1)
        assert(Files.isRegularFile(stages.head.resolve("failure.json")))
      }
    }
  }

  test("output paths cannot escape, overlap inputs, follow symlinks or overwrite directories") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      intercept[DataException](CheckpointProcessor.plan(input, kanji, PolicySettings(), directory.resolve("outside"), options))
      Files.createDirectories(output)
      Files.createSymbolicLink(output.resolve("linked"), input)
      intercept[DataException](CheckpointProcessor.plan(input, kanji, PolicySettings(), output.resolve("linked/run"), options))
      CheckpointProcessor.inspect(input, output.resolve("runs/inspect"), options)
      intercept[DataException](CheckpointProcessor.inspect(input, output.resolve("runs/inspect"), options))
      intercept[DataException](ProcessingIO.relative(input, "../outside"))
      intercept[DataException](ProcessingIO.relative(input, "/absolute"))
    }
  }

  test("clone creates an independent inode, and binary comparison catches a wrong surviving byte") {
    ProcessingFixtures.withModel { (_, input, output) =>
      Files.createDirectories(output)
      val source = input.resolve("README.md")
      val dest = output.resolve("copy.md")
      val method = BinaryTransfer.copyFile(source, dest)
      assert(Set("apfs-clone", "buffered-copy")(method))
      assert(!Files.isSameFile(source, dest))
      Files.writeString(dest, "changed independently")
      assertEquals(Files.readString(source), "Synthetic checkpoint fixture\n")
      intercept[DataException](BinaryTransfer.copyFile(source, dest))
      val plan = PlanStore.prepare(input, kanji, PolicySettings())
      val shard = plan.domain.shards.head
      val checkpoint = output.resolve("projected.safetensors")
      BinaryTransfer.rewrite(shard, checkpoint, 3)
      assertEquals(BinaryTransfer.verify(shard, checkpoint, 3).map(_.comparedBytes).sum, shard.payloadBytes.longValue)
      Using.resource(FileChannel.open(checkpoint, StandardOpenOption.WRITE)) { channel =>
        channel.write(ByteBuffer.wrap(Array[Byte](1)), SafetensorsIO.read(checkpoint).payloadStart.longValue + 1L)
      }
      intercept[DataException](BinaryTransfer.verify(shard, checkpoint, 3))
    }
  }

  test("sparse source range beyond 2 GiB rewrites without payload-sized allocation") {
    val directory = Files.createTempDirectory("vocab-large-projection-")
    val path = directory.resolve("input.safetensors")
    val filler = TensorMetadata("body", TensorDType.U8, Vector(3L * 1024 * 1024 * 1024), ByteRange(ByteOffset(0), ByteCount(3L * 1024 * 1024 * 1024)))
    val rows = TensorMetadata("rows", TensorDType.U8, Vector(3L, 2L), ByteRange(filler.dataRange.endExclusive, ByteCount(6)))
    try
      val header = SafetensorsIO.encodeHeader(Vector(filler, rows))
      Using.resource(FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { channel =>
        channel.write(ByteBuffer.wrap(header))
        channel.write(ByteBuffer.wrap(Array[Byte](1, 2, 3, 4, 5, 6)), header.length.toLong + rows.dataRange.start.longValue)
      }
      val metadata = SafetensorsIO.read(path)
      val projection = me.nsmr.vocabsurgery.tokenizer.VocabularyProjection.fromSurvivors(3, Vector(OriginalTokenId(2)))
      val onlyRows = TensorOperations.projectRows(rows, projection, ByteOffset(0))
      val plannedHeader = Json.obj("rows" -> Json.obj("dtype" -> Json.fromString("U8"), "shape" -> Json.arr(Json.fromInt(1), Json.fromInt(2)), "data_offsets" -> Json.arr(Json.fromInt(0), Json.fromInt(2))))
      val plan = ShardProjection(metadata, Vector(onlyRows), plannedHeader, ByteCount(2), true)
      val target = directory.resolve("output.safetensors")
      BinaryTransfer.rewrite(plan, target, 1)
      val written = Files.readAllBytes(target)
      assertEquals(written.takeRight(2).toVector, Vector[Byte](5, 6))
      assertEquals(BinaryTransfer.verify(plan, target, 1).head.comparedBytes, 2L)
    finally Using.resource(Files.list(directory))(_.iterator().asScala.foreach(Files.delete))
      Files.delete(directory)
  }
