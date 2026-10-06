package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference
import me.nsmr.vocabsurgery.tokenizer.*

object DomainFixtures:
  val policy = CharacterPolicy(JapaneseKanjiReference.fromCharacters(CodePoint.sequence("日本").toSet))
  val foreign = ByteLevel.encode(TokenSurface("ع"))
  private val quantization = Json.obj("bits" -> Json.fromInt(4), "group_size" -> Json.fromInt(4), "mode" -> Json.fromString("affine"))

  /** Coder-Next layout: dimensions and token IDs at the top of `config.json`, three unregistered rows. */
  def snapshot(): ModelSnapshot =
    val tokenizer = TokenizerFixtures.create(Vector("ab", foreign), Vector(BpeMerge("a", "b"), BpeMerge(foreign.take(1), foreign.drop(1))))
    val rows = TokenizerFixtures.rows(tokenizer) + 3
    val eos = TokenizerFixtures.id(tokenizer, "<|im_end|>").intValue
    val configJson = Json.obj("model_type" -> Json.fromString("qwen3_next"), "vocab_size" -> Json.fromInt(rows), "hidden_size" -> Json.fromInt(8),
      "tie_word_embeddings" -> Json.False, "eos_token_id" -> Json.arr(Json.fromInt(eos)),
      "quantization" -> quantization.mapObject(_.add("model.layers.0.body", Json.obj("bits" -> Json.fromInt(8)))),
      "architecture_extra" -> Json.fromInt(123))
    val config = ModelConfig(CoderNext, rows, 8, Some(QuantizationConfig(QuantizationSpec(4, 4, "affine"), Map("model.layers.0.body" -> QuantizationSpec(8, 4, "affine")))), configJson)
    val generation = Json.obj("eos_token_id" -> Json.arr(Json.fromInt(eos)), "suppress_tokens" -> Json.arr(Json.fromInt(TokenizerFixtures.id(tokenizer, foreign).intValue)), "temperature" -> Json.fromDoubleOrNull(0.8))
    build(config, tokenizer, generation, Json.obj("eos_token" -> Json.fromString("<|im_end|>")), Vector("model.embed_tokens", "lm_head"), Map.empty, Map.empty)

  /** Flash-Next layout: nested `text_config`, vision token IDs, `language_model.*` modules, a PLE layer and slow tokenizer files. */
  def flashNext(): ModelSnapshot =
    val tokenizer = TokenizerFixtures.create(Vector("ab", foreign), Vector(BpeMerge("a", "b"), BpeMerge(foreign.take(1), foreign.drop(1))),
      (FlashNext.requiredTokens + "<|image_pad|>").toVector.sorted)
    val rows = TokenizerFixtures.rows(tokenizer) + 3
    def id(content: String) = Json.fromInt(TokenizerFixtures.id(tokenizer, content).intValue)
    val eos = id("<|im_end|>")
    val configJson = Json.obj("model_type" -> Json.fromString("qwen4_exp"), "eos_token_id" -> Json.arr(eos, id("<|endoftext|>")),
      "image_token_id" -> id("<|image_pad|>"), "tie_word_embeddings" -> Json.False,
      "generation_config" -> Json.obj("eos_token_id" -> Json.arr(eos), "pad_token_id" -> id("<|endoftext|>"), "top_k" -> Json.fromInt(20)),
      "quantization" -> quantization, "quantization_config" -> quantization,
      "text_config" -> Json.obj("vocab_size" -> Json.fromInt(rows), "hidden_size" -> Json.fromInt(8), "bos_token_id" -> id("<|endoftext|>"),
        "eos_token_id" -> id("<|endoftext|>"), "pad_token_id" -> Json.Null, "tie_word_embeddings" -> Json.False,
        "ple_layer_ids" -> Json.arr(Json.fromInt(2)), "mtp_use_dedicated_embeddings" -> Json.False),
      "vision_config" -> Json.obj("depth" -> Json.fromInt(1)))
    val config = ModelConfig(FlashNext, rows, 8, Some(QuantizationConfig(QuantizationSpec(4, 4, "affine"), Map.empty)), configJson)
    val tokenizerConfig = Json.obj("eos_token" -> Json.fromString("<|im_end|>"),
      "extra_special_tokens" -> Json.obj("image_token" -> Json.fromString("<|image_pad|>")))
    val vocab = Json.obj(tokenizer.vocabulary.map(t => t.encoded -> Json.fromInt(t.id.intValue))*).noSpaces
    val merges = tokenizer.merges.map(m => s"${m.left} ${m.right}").mkString("", "\n", "\n")
    build(config, tokenizer, Json.obj("eos_token_id" -> Json.arr(eos)), tokenizerConfig,
      Vector("language_model.model.embed_tokens", "language_model.lm_head"), Map("vocab.json" -> vocab, "merges.txt" -> merges), Map.empty)

  private def build(config: ModelConfig, tokenizer: TokenizerData, generation: Json, tokenizerConfig: Json, modules: Vector[String],
      companions: Map[String, String], familyFiles: Map[String, Json]): ModelSnapshot =
    val rows = config.vocabSize.toLong
    var cursor = 0L
    def tensor(name: String, dtype: TensorDType, shape: Vector[Long]): TensorMetadata =
      val length = shape.foldLeft(dtype.byteWidth.toLong)(Math.multiplyExact)
      val value = TensorMetadata(name, dtype, shape, ByteRange(ByteOffset(cursor), ByteCount(length)))
      cursor += length
      value
    val tensors = Vector(tensor("body", TensorDType.U8, Vector(3L))) ++
      modules.flatMap { module => Vector(
        tensor(s"$module.weight", TensorDType.U32, Vector(rows, 1L)),
        tensor(s"$module.scales", TensorDType.BF16, Vector(rows, 2L)),
        tensor(s"$module.biases", TensorDType.BF16, Vector(rows, 2L))) }
    val header = Json.obj((Vector("__metadata__" -> Json.obj("format" -> Json.fromString("mlx"))) ++ tensors.map(t => t.name -> Json.obj(
      "dtype" -> Json.fromString(t.dtype.toString), "shape" -> Json.fromValues(t.shape.map(Json.fromLong)),
      "data_offsets" -> Json.arr(Json.fromLong(t.dataRange.start.longValue), Json.fromLong(t.dataRange.endExclusive.longValue)), "future" -> Json.fromString("preserve"))))*)
    val shard = SafetensorsFile(Path.of("nonexistent/input.safetensors"), ByteCount(cursor + 16L), ByteCount(8L), ByteOffset(16L), tensors, Map("format" -> "mlx"), header, "unused-in-pure-tests")
    val indexJson = Json.obj("metadata" -> Json.obj("total_size" -> Json.fromLong(cursor), "total_parameters" -> Json.fromLong(9007199254740993L), "future" -> Json.fromInt(77)),
      "weight_map" -> Json.obj(tensors.map(t => t.name -> Json.fromString("input.safetensors"))*), "extra" -> Json.fromInt(99))
    val index = ShardIndex(tensors.map(t => t.name -> "input.safetensors").toMap, Some(ByteCount(cursor)), Some(9007199254740993L), indexJson)
    ModelSnapshot(Path.of("nonexistent"), config, generation, tokenizer, tokenizerConfig, companions, familyFiles, index,
      Vector(shard), Some(TextAsset(Path.of("nonexistent/chat_template.jinja"), "<|im_start|>{{ message }}<|im_end|>")), Vector.empty)

  /** Replace a module's packed weight and companions with one dense BF16 weight of `hidden` columns. */
  def dense(source: ModelSnapshot, module: String): ModelSnapshot =
    val shard = source.shards.head
    val rows = source.config.vocabSize.toLong
    var cursor = 0L
    val tensors = shard.tensors.filterNot(t => t.name == s"$module.scales" || t.name == s"$module.biases").map { tensor =>
      val shape = if tensor.name == s"$module.weight" then Vector(rows, source.config.hiddenSize.toLong) else tensor.shape
      val dtype = if tensor.name == s"$module.weight" then TensorDType.BF16 else tensor.dtype
      val length = shape.foldLeft(dtype.byteWidth.toLong)(Math.multiplyExact)
      val result = TensorMetadata(tensor.name, dtype, shape, ByteRange(ByteOffset(cursor), ByteCount(length)))
      cursor += length
      result
    }
    val header = Json.obj(tensors.map(t => t.name -> Json.obj("dtype" -> Json.fromString(t.dtype.toString), "shape" -> Json.fromValues(t.shape.map(Json.fromLong)),
      "data_offsets" -> Json.arr(Json.fromLong(t.dataRange.start.longValue), Json.fromLong(t.dataRange.endExclusive.longValue))))*)
    val weightMap = tensors.map(t => t.name -> "input.safetensors").toMap
    val indexJson = Json.obj("metadata" -> Json.obj("total_size" -> Json.fromLong(cursor)), "weight_map" -> Json.obj(weightMap.toVector.map((k, v) => k -> Json.fromString(v))*))
    source.copy(shards = Vector(shard.copy(fileSize = ByteCount(cursor + 16L), tensors = tensors, originalHeader = header)),
      index = ShardIndex(weightMap, Some(ByteCount(cursor)), None, indexJson))
