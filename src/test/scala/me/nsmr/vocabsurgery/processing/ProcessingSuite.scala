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
  private val default = PolicyFile.default

  private def characters(json: Json): Json = json.mapObject(_.add("kind", Json.fromString("characters")))

  test("policy file round trip requires a known kind and rejects unknown fields, bad scalars and missing files") {
    val path = Path.of("synthetic-policy.json")
    val file = PolicyFile.default
    assertEquals(PolicyFile.parse(file.json, path), file)
    intercept[DataException](PolicyFile.parse(Json.obj(), path))
    intercept[DataException](PolicyFile.parse(Json.obj("kind" -> Json.fromString("frequency")), path))
    Vector(Json.obj("oops" -> Json.True), Json.obj("formatVersion" -> Json.fromInt(2)),
      Json.obj("unicodeVersion" -> Json.fromString("16.0.0")), Json.obj("japaneseExtra" -> Json.arr(Json.fromInt(0xd800))),
      Json.obj("explicitForbidden" -> Json.arr(Json.fromInt(-1))), Json.obj("protectedIds" -> Json.arr(Json.fromInt(-1))),
      Json.obj("forbiddenScripts" -> Json.arr(Json.fromString("nonsense"))), Json.obj("unregisteredRows" -> Json.fromString("keep")),
      Json.obj("parameterCount" -> Json.fromString("infer")), Json.obj("pruneHanOutsideReference" -> Json.Null),
      Json.obj("protectMath" -> Json.Null), Json.obj("protectMath" -> Json.fromString("true")), Json.obj("japaneseReference" -> Json.True))
      .foreach(json => intercept[DataException](PolicyFile.parse(characters(json), path)))
    intercept[DataException](PolicyFile.load(Path.of("nonexistent-policy.json")))
  }

  test("character policy protects Math by default and records the switch") {
    val path = Path.of("policy.json")
    val parsed = PolicyFile.parse(characters(Json.obj()), path).settings.asInstanceOf[CharacterPolicySettings]
    assert(parsed.protectMath)
    assertEquals(parsed.japaneseReference, Path.of("kanji-data"))
    val disabled = PolicyFile(CharacterPolicySettings(protectMath = false))
    assertEquals(PolicyFile.parse(disabled.json, path), disabled)
    assertEquals(disabled.json.hcursor.get[Boolean]("protectMath"), Right(false))
    val reference = me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference.fromCharacters(Set(CodePoint(0x65e5)))
    assert(CharacterPolicySettings().domain(reference).protectMath)
    assert(!CharacterPolicySettings(protectMath = false).domain(reference).protectMath)
  }

  test("coding profile preserves Greek and Canadian Aboriginal and explicitly targets the five reviewed Han characters") {
    val settings = PolicyFile.load(Path.of("policies/ja-en-coding-v2.json")).settings.asInstanceOf[CharacterPolicySettings]
    assert(settings.protectMath)
    assertEquals(settings.explicitForbidden, CodePoint.sequence("个开关无网").toSet)
    assert(settings.forbiddenScripts(me.nsmr.vocabsurgery.unicode.UnicodeScript.fromName("Hebrew")))
    assert(settings.forbiddenScripts(me.nsmr.vocabsurgery.unicode.UnicodeScript.fromName("Thai")))
    Vector("Greek", "Canadian_Aboriginal", "Latin", "Han", "Hiragana", "Katakana", "Common", "Inherited")
      .foreach(name => assert(!settings.forbiddenScripts(me.nsmr.vocabsurgery.unicode.UnicodeScript.fromName(name))))
  }

  test("deletion list reads JSON string lines, skips comments and blanks, and rejects duplicates or bare text") {
    val directory = Files.createTempDirectory("vocab-deletion-list-")
    try
      val path = directory.resolve("list.txt")
      Files.writeString(path, "# Greek fragments\n\n\" yang\"\n\"\\n\\n\"\n  \"什么\"  \r\n")
      assertEquals(DeletionList.read(path), Vector(" yang", "\n\n", "什么"))
      Vector("yang\n", "\"a\"\n\"a\"\n", "\"\"\n", "1\n").foreach { text =>
        Files.writeString(path, text)
        intercept[DataException](DeletionList.read(path))
      }
    finally Using.resource(Files.walk(directory))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete))
  }

  test("listed tokens are deleted, protected ones kept as conflicts, unmatched entries reported, and list edits invalidate the plan") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val list = directory.resolve("deleted.txt")
      Files.writeString(list, "\"ab\"\n\"<tool_call>\"\n\"not-in-this-model\"\n")
      val policy = PolicyFile(CharacterPolicySettings(deletedTokens = Some(list)))
      assertEquals(PolicyFile.parse(policy.json, Path.of("p.json")), policy)
      val options = ProcessingOptions(outputRoot = output)
      val path = CheckpointProcessor.plan(input, policy, output.resolve("runs/listed"), options)
      val prepared = PlanStore.loadVerified(path)
      val tokens = prepared.domain.analysis.tokens
      val ab = tokens.find(_.content == "ab").get
      assert(prepared.domain.analysis.cleanup.directDeleted(ab.id))
      assert(tokens.find(_.content == "<tool_call>").exists(t => t.protectedToken && t.deletion.exists(_.code == "listed")))
      val summary = JsonIO.read(path.getParent.resolve("summary.json"))
      val deletion = summary.hcursor.downField("policy").downField("deletionList")
      assertEquals(deletion.get[Int]("matchedTokens"), Right(2))
      assertEquals(deletion.get[Int]("keptByProtection"), Right(1))
      assertEquals(deletion.get[Vector[String]]("unmatchedExamples"), Right(Vector("not-in-this-model")))
      assertEquals(summary.hcursor.downField("rowsAboveMultiple").get[Int]("64"), Right(prepared.domain.projection.compactSize % 64))
      assertEquals(Files.readString(path.getParent.resolve("deletion-list-unmatched.jsonl")), "\"not-in-this-model\"\n")
      Files.writeString(list, "\"ab\"\n")
      intercept[DataException](PlanStore.loadVerified(path))
    }
  }

  test("saved plan creates analysis artifacts and verifies input identity and deterministic operations") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      val path = CheckpointProcessor.plan(input, default, output.resolve("runs/test"), options)
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
      val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform), bufferBytes = 7)
      val path = CheckpointProcessor.plan(input, default, output.resolve("runs/apply"), options)
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
      val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform))
      val path = CheckpointProcessor.plan(input, default, output.resolve("runs/failure"), options)
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

  test("Flash-Next apply writes PLE token IDs and slow tokenizer files, and verification requires exactly the planned set") {
    ProcessingFixtures.withSnapshot(DomainFixtures.flashNext()) { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform))
      val path = CheckpointProcessor.plan(input, default, output.resolve("runs/flash"), options)
      val prepared = PlanStore.loadVerified(path)
      val model = CheckpointProcessor.applyPlan(path, output.resolve("models/flash"), ProcessingFixtures.reference, options)
      val table = me.nsmr.vocabsurgery.model.PleTokenIds.parse(JsonIO.read(model.resolve("ple-token-ids.json")))
      assertEquals(table.originalTokenIds, prepared.domain.projection.compactToOriginal.map(_.intValue))
      assertEquals(Files.readString(model.resolve("merges.txt")), prepared.domain.file("merges.txt").content)
      val manifest = JsonIO.read(path.getParent.resolve("manifest.json"))
      val methods = manifest.hcursor.downField("files").values.get.map(f => f.hcursor.get[String]("name").toOption.get -> f.hcursor.get[String]("method").toOption.get).toMap
      assertEquals(methods("ple-token-ids.json"), "generated")
      assertEquals(methods("README.md"), "buffered-copy")
      assertEquals(manifest.hcursor.get[String]("family"), Right("flash-next"))
      CheckpointProcessor.verifyManifest(path.getParent.resolve("manifest.json"), ProcessingFixtures.reference, options)
      val again = CheckpointProcessor.plan(model, default, output.resolve("runs/again"), options)
      assertEquals(PlanStore.loadVerified(again).domain.removedRows, 0)
      Files.delete(model.resolve("ple-token-ids.json"))
      intercept[DataException](CheckpointProcessor.verifyManifest(path.getParent.resolve("manifest.json"), ProcessingFixtures.reference, options))
    }
  }

  test("publication refuses a target created after staging, and copying keeps the base publication") {
    ProcessingFixtures.withModel { (_, input, output) =>
      Files.createDirectories(output.resolve("stage"))
      Files.createDirectories(output.resolve("taken"))
      intercept[DataException](FileTransfer.platform.publishNew(output.resolve("stage"), output.resolve("taken")))
      assert(Files.isDirectory(output.resolve("stage")))
      FileTransfer.copying(FileTransfer.platform).publishNew(output.resolve("stage"), output.resolve("published"))
      assert(Files.isDirectory(output.resolve("published")) && !Files.exists(output.resolve("stage")))
      assertEquals(FileTransfer.copying(FileTransfer.platform).cloneNew(input.resolve("README.md"), output.resolve("x")), None)
      assert(!Files.exists(output.resolve("x")))
    }
  }

  test("no-deletion processing copies the entire checkpoint byte-for-byte") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val loaded = SnapshotLoader.load(input)
      val rows = (loaded.tokenizer.vocabulary.map(_.id) ++ loaded.tokenizer.addedTokens.map(_.id)).distinct.size
      var cursor = 0L
      val tensors = loaded.shards.head.tensors.map { tensor =>
        val shape = if tensor.name == "body" then tensor.shape else rows.toLong +: tensor.shape.tail
        val length = shape.foldLeft(tensor.dtype.byteWidth.toLong)(Math.multiplyExact)
        val result = tensor.copy(shape = shape, dataRange = ByteRange(ByteOffset(cursor), ByteCount(length)))
        cursor += length
        result
      }
      val header = tensors.foldLeft(loaded.shards.head.originalHeader.asObject.get) { (fields, tensor) =>
        fields.add(tensor.name, fields(tensor.name).get.mapObject(_.add("shape", Json.fromValues(tensor.shape.map(Json.fromLong)))
          .add("data_offsets", Json.arr(Json.fromLong(tensor.dataRange.start.longValue), Json.fromLong(tensor.dataRange.endExclusive.longValue)))))
      }
      Files.write(input.resolve("input.safetensors"), SafetensorsIO.encodeHeaderJson(Json.fromJsonObject(header)) ++ (0 until cursor.toInt).map(_.toByte).toArray)
      Files.writeString(input.resolve("config.json"), loaded.config.original.mapObject(_.add("vocab_size", Json.fromInt(rows))).noSpaces)
      Files.writeString(input.resolve("model.safetensors.index.json"), loaded.index.original.mapObject(fields => fields.add("metadata", fields("metadata").get.mapObject(_.add("total_size", Json.fromLong(cursor))))).noSpaces)
      val settings = PolicyFile(CharacterPolicySettings(forbiddenScripts = Set.empty, pruneHanOutsideReference = false))
      val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform))
      val plan = CheckpointProcessor.plan(input, settings, output.resolve("runs/noop"), options)
      assertEquals(PlanStore.loadVerified(plan).domain.removedRows, 0)
      val result = CheckpointProcessor.applyPlan(plan, output.resolve("models/noop"), ProcessingFixtures.reference, options)
      assertEquals(FileIO.sha256(result.resolve("input.safetensors")), FileIO.sha256(input.resolve("input.safetensors")))
    }
  }

  test("changed kanji source hashes prevent application even when the glyph set is unchanged") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val reference = Files.createDirectory(directory.resolve("kanji"))
      Vector("JIS漢字.csv", "常用漢字.csv", "教育漢字.csv", "子の名に使える漢字.csv", "常用漢字表本表.json", "漢検漢字辞典漢字.csv")
        .foreach(name => Files.copy(kanji.resolve(name), reference.resolve(name)))
      val plan = CheckpointProcessor.plan(input, PolicyFile(CharacterPolicySettings(japaneseReference = reference)), output.resolve("runs/kanji"), ProcessingOptions(outputRoot = output))
      val path = reference.resolve("常用漢字表本表.json")
      val records = JsonIO.read(path).asArray.get
      Files.writeString(path, Json.fromValues(records.updated(0, records.head.mapObject(_.add("fixtureChange", Json.True)))).noSpaces)
      intercept[DataException](PlanStore.loadVerified(plan))
    }
  }

  test("output paths cannot escape, overlap inputs, follow symlinks or overwrite directories") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      intercept[DataException](CheckpointProcessor.plan(input, default, directory.resolve("outside"), options))
      Files.createDirectories(output)
      Files.createSymbolicLink(output.resolve("linked"), input)
      intercept[DataException](CheckpointProcessor.plan(input, default, output.resolve("linked/run"), options))
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
      assert(Set("clonefile", "ficlone", "buffered-copy")(method))
      assert(!Files.isSameFile(source, dest))
      Files.writeString(dest, "changed independently")
      assertEquals(Files.readString(source), "Synthetic checkpoint fixture\n")
      intercept[DataException](BinaryTransfer.copyFile(source, dest))
      val plan = PlanStore.prepare(input, default)
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
