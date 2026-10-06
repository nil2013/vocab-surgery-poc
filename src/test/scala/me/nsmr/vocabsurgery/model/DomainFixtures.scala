package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference
import me.nsmr.vocabsurgery.tokenizer.*

object DomainFixtures:
  val policy = PruningPolicy(JapaneseKanjiReference.fromCharacters(CodePoint.sequence("日本").toSet))
  val foreign = ByteLevel.encode(TokenSurface("ع"))

  def snapshot(): ModelSnapshot =
    val tokenizer = TokenizerFixtures.create(Vector("ab", foreign), Vector(BpeMerge("a", "b"), BpeMerge(foreign.take(1), foreign.drop(1))))
    val rows = TokenizerFixtures.rows(tokenizer) + 3
    val eos = TokenizerFixtures.id(tokenizer, "<|im_end|>").intValue
    val configJson = Json.obj("model_type" -> Json.fromString("qwen3_next"), "vocab_size" -> Json.fromInt(rows), "hidden_size" -> Json.fromInt(8),
      "tie_word_embeddings" -> Json.False, "eos_token_id" -> Json.arr(Json.fromInt(eos)),
      "quantization" -> Json.obj("bits" -> Json.fromInt(4), "group_size" -> Json.fromInt(4), "mode" -> Json.fromString("affine"),
        "model.layers.0.body" -> Json.obj("bits" -> Json.fromInt(8))), "architecture_extra" -> Json.fromInt(123))
    val config = ModelConfig("qwen3_next", rows, 8, false, QuantizationConfig(QuantizationSpec(4, 4, "affine"), Map("model.layers.0.body" -> QuantizationSpec(8, 4, "affine"))), Map.empty, configJson)
    var cursor = 0L
    def tensor(name: String, dtype: TensorDType, shape: Vector[Long]): TensorMetadata =
      val length = shape.foldLeft(dtype.byteWidth.toLong)(Math.multiplyExact)
      val value = TensorMetadata(name, dtype, shape, ByteRange(ByteOffset(cursor), ByteCount(length)))
      cursor += length
      value
    val tensors = Vector(tensor("body", TensorDType.U8, Vector(3L))) ++
      Vector("model.embed_tokens", "lm_head").flatMap { module => Vector(
        tensor(s"$module.weight", TensorDType.U32, Vector(rows.toLong, 1L)),
        tensor(s"$module.scales", TensorDType.BF16, Vector(rows.toLong, 2L)),
        tensor(s"$module.biases", TensorDType.BF16, Vector(rows.toLong, 2L))) }
    val header = Json.obj((Vector("__metadata__" -> Json.obj("format" -> Json.fromString("mlx"))) ++ tensors.map(t => t.name -> Json.obj(
      "dtype" -> Json.fromString(t.dtype.toString), "shape" -> Json.fromValues(t.shape.map(Json.fromLong)),
      "data_offsets" -> Json.arr(Json.fromLong(t.dataRange.start.longValue), Json.fromLong(t.dataRange.endExclusive.longValue)), "future" -> Json.fromString("preserve"))))*)
    val shard = SafetensorsFile(Path.of("nonexistent/input.safetensors"), ByteCount(cursor + 16L), ByteCount(8L), ByteOffset(16L), tensors, Map("format" -> "mlx"), header, "unused-in-pure-tests")
    val indexJson = Json.obj("metadata" -> Json.obj("total_size" -> Json.fromLong(cursor), "total_parameters" -> Json.fromLong(9007199254740993L), "future" -> Json.fromInt(77)),
      "weight_map" -> Json.obj(tensors.map(t => t.name -> Json.fromString("input.safetensors"))*), "extra" -> Json.fromInt(99))
    val index = ShardIndex(tensors.map(t => t.name -> "input.safetensors").toMap, Some(ByteCount(cursor)), Some(9007199254740993L), indexJson)
    val generation = GenerationConfig(Map.empty, Json.obj("eos_token_id" -> Json.arr(Json.fromInt(eos)), "suppress_tokens" -> Json.arr(Json.fromInt(TokenizerFixtures.id(tokenizer, foreign).intValue)), "temperature" -> Json.fromDoubleOrNull(0.8)))
    ModelSnapshot(Path.of("nonexistent"), config, generation, tokenizer, Json.obj("eos_token" -> Json.fromString("<|im_end|>")), index,
      Vector(shard), TextAsset(Path.of("nonexistent/chat_template.jinja"), "<|im_start|>{{ message }}<|im_end|>"), Vector.empty)
