package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.{Files, Path}
import scala.util.Using
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*

class ModelLoaderSuite extends munit.FunSuite:
  private def fixture[A](f: Path => A): A =
    val directory = Files.createTempDirectory("vocab-model-test-")
    try
      val config = JsonIO.parse("""{
        "model_type":"qwen3_next","vocab_size":4,"hidden_size":8,"tie_word_embeddings":false,
        "bos_token_id":3,"eos_token_id":[3],"quantization":{"bits":4,"group_size":4,"mode":"affine",
          "model.layers.0.mlp.gate":{"bits":8}},"extra":{"keep":true}}
      """, directory)
      JsonIO.writeNew(directory.resolve("config.json"), config)
      JsonIO.writeNew(directory.resolve("generation_config.json"), Json.obj("eos_token_id" -> Json.arr(Json.fromInt(3))))
      val tokenizer = JsonIO.parse("""{
        "model":{"type":"BPE","vocab":{"a":0,"b":1,"ab":2},"merges":[["a","b"]]},
        "added_tokens":[{"id":3,"content":"<tool_call>","special":false,"single_word":false,
          "lstrip":false,"rstrip":false,"normalized":false}],"normalizer":{"type":"NFC"}}
      """, directory)
      JsonIO.writeNew(directory.resolve("tokenizer.json"), tokenizer)
      JsonIO.writeNew(directory.resolve("tokenizer_config.json"), Json.obj("tool_parser_type" -> Json.fromString("qwen3_coder")))
      FileIO.writeNewUtf8(directory.resolve("chat_template.jinja"), "{{ messages }}")
      var offset = 0L
      val tensors = Vector("model.embed_tokens", "lm_head").flatMap { prefix =>
        Vector("weight", "scales", "biases").map { suffix =>
          val dtype = if suffix == "weight" then TensorDType.U32 else TensorDType.BF16
          val shape = if suffix == "weight" then Vector(4L, 1L) else Vector(4L, 2L)
          val tensor = TensorMetadata(s"$prefix.$suffix", dtype, shape,
            ByteRange(ByteOffset(offset), ByteCount(16L)))
          offset += 16L
          tensor
        }
      }
      val header = SafetensorsIO.encodeHeader(tensors)
      Files.write(directory.resolve("model.safetensors"), header ++ Array.fill[Byte](offset.toInt)(0))
      val index = Json.obj(
        "metadata" -> Json.obj("total_size" -> Json.fromLong(offset), "total_parameters" -> Json.fromLong(64L)),
        "weight_map" -> Json.obj(tensors.map(tensor => tensor.name -> Json.fromString("model.safetensors"))*))
      JsonIO.writeNew(directory.resolve("model.safetensors.index.json"), index)
      f(directory)
    finally
      import scala.jdk.CollectionConverters.*
      Using.resource(Files.walk(directory)) { paths =>
        paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      }

  private def replace(path: Path, json: Json): Unit =
    Files.writeString(path, json.noSpaces)

  test("snapshot loads typed metadata and retains unknown fields and protocol flags") {
    fixture { directory =>
      val snapshot = SnapshotLoader.load(directory)
      assertEquals(snapshot.config.vocabSize, 4)
      assertEquals(snapshot.config.family, CoderNext)
      assertEquals(snapshot.config.quantization.map(_.overrides("model.layers.0.mlp.gate")), Some(QuantizationSpec(8, 4, "affine")))
      assertEquals(snapshot.config.original.hcursor.downField("extra").get[Boolean]("keep"), Right(true))
      assertEquals(snapshot.tokenizer.addedTokens.head.special, false)
      assertEquals(snapshot.tokenizer.merges, Vector(BpeMerge("a", "b")))
      assertEquals(snapshot.shards.head.tensors.size, 6)
      assertEquals(snapshot.metadataFingerprints.size, 6)
      assertEquals(snapshot.generation.hcursor.get[Vector[Int]]("eos_token_id"), Right(Vector(3)))
      assertEquals(snapshot.tokenizerCompanions, Map.empty)
    }
  }

  test("index, headers and payload totals must agree") {
    fixture { directory =>
      val path = directory.resolve("model.safetensors.index.json")
      val index = JsonIO.read(path)
      replace(path, index.mapObject(_.add("metadata", Json.obj("total_size" -> Json.fromLong(95L)))))
      val error = intercept[DataException](SnapshotLoader.load(directory))
      assertEquals(error.location, "metadata.total_size")
      replace(path, index.mapObject(_.add("weight_map", Json.obj("unknown" -> Json.fromString("model.safetensors")))))
      intercept[DataException](SnapshotLoader.load(directory))
    }
  }

  test("shard filenames cannot escape the snapshot directory") {
    fixture { directory =>
      val path = directory.resolve("model.safetensors.index.json")
      Vector("../outside.safetensors", "/tmp/outside.safetensors", "a/b.safetensors", "a\\b.safetensors").foreach { filename =>
        replace(path, Json.obj("weight_map" -> Json.obj("x" -> Json.fromString(filename))))
        intercept[DataException](ShardIndexLoader.load(path))
      }
    }
  }

  test("tokenizer rejects duplicate IDs, ID/content conflicts and merges with missing results") {
    fixture { directory =>
      val path = directory.resolve("tokenizer.json")
      val base = JsonIO.read(path)
      val model = base.hcursor.downField("model").focus.get
      val duplicate = model.mapObject(_.add("vocab", Json.obj("a" -> Json.fromInt(0), "b" -> Json.fromInt(0))))
      replace(path, base.mapObject(_.add("model", duplicate)))
      intercept[DataException](TokenizerLoader.load(path))
      val missing = model.mapObject(_.add("vocab", Json.obj("a" -> Json.fromInt(0), "b" -> Json.fromInt(1))))
      replace(path, base.mapObject(_.add("model", missing)))
      intercept[DataException](TokenizerLoader.load(path))
      val added = base.hcursor.downField("added_tokens").focus.get.asArray.get.head
      val conflicting = added.mapObject(_.add("id", Json.fromInt(0)))
      replace(path, base.mapObject(_.add("added_tokens", Json.arr(conflicting))))
      intercept[DataException](TokenizerLoader.load(path))
      val stringMerge = model.mapObject(_.add("merges", Json.arr(Json.fromString("a b"))))
      replace(path, base.mapObject(_.add("model", stringMerge)))
      assertEquals(TokenizerLoader.load(path).merges, Vector(BpeMerge("a", "b")))
    }
  }

  test("invalid config IDs and mismatched quantization settings retain field context") {
    fixture { directory =>
      val path = directory.resolve("config.json")
      val config = JsonIO.read(path)
      replace(path, config.mapObject(_.add("vocab_size", Json.fromInt(0))))
      intercept[DataException](ConfigLoader.loadModel(path))
      replace(path, config.mapObject(_.add("quantization_config", Json.obj("bits" -> Json.fromInt(8), "group_size" -> Json.fromInt(4)))))
      intercept[DataException](ConfigLoader.loadModel(path))
      replace(path, config.mapObject(_.add("model_type", Json.fromString("unknown"))))
      assert(intercept[DataException](ConfigLoader.loadModel(path)).getMessage.contains("no model family"))
      replace(path, config.mapObject(_.add("eos_token_id", Json.fromInt(-1))))
      assert(intercept[DataException](SnapshotLoader.load(directory)).getMessage.contains("eos_token_id"))
    }
  }

  test("snapshot rejects unregistered config IDs and missing required files") {
    fixture { directory =>
      val generation = directory.resolve("generation_config.json")
      replace(generation, Json.obj("eos_token_id" -> Json.fromInt(4)))
      val invalid = intercept[DataException](SnapshotLoader.load(directory))
      assertEquals(invalid.source, generation)
      assertEquals(invalid.location, "$.eos_token_id")
      Files.delete(generation)
      val missing = intercept[DataException](SnapshotLoader.load(directory))
      assertEquals(missing.kind, ErrorKind.MissingFile)
      assertEquals(missing.source, generation)
    }
  }

  test("chat template is optional and source slow tokenizer files are read when present") {
    fixture { directory =>
      Files.delete(directory.resolve("chat_template.jinja"))
      Files.writeString(directory.resolve("merges.txt"), "a b\n")
      val snapshot = SnapshotLoader.load(directory)
      assertEquals(snapshot.chatTemplate, None)
      assertEquals(snapshot.tokenizerCompanions, Map("merges.txt" -> "a b\n"))
      assertEquals(snapshot.metadataFingerprints.size, 6)
    }
  }

  test("surgery companion tensors must exist and have consistent vocabulary rows") {
    fixture { directory =>
      val path = directory.resolve("config.json")
      val config = JsonIO.read(path)
      replace(path, config.mapObject(_.add("vocab_size", Json.fromInt(5))))
      val error = intercept[DataException](SnapshotLoader.load(directory))
      assert(error.location.contains("embed_tokens"))
    }
  }
