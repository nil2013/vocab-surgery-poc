package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*

/** Typed access to surgery-relevant fields; all other fields are retained verbatim as JSON values. */
object ConfigLoader:
  def loadModel(path: Path): ModelConfig = Diagnostics.guard(path, "model config") {
    val root = JsonIO.read(path)
    val fields = JsonFields.obj(root, path, "config")
    val modelType = JsonFields.text(JsonFields.field(root, "model_type", path, "config"), path, "model_type")
    val vocabSize = positiveInt(JsonFields.field(root, "vocab_size", path, "config"), path, "vocab_size")
    val hiddenSize = positiveInt(JsonFields.field(root, "hidden_size", path, "config"), path, "hidden_size")
    val tied = fields("tie_word_embeddings").map(JsonFields.bool(_, path, "tie_word_embeddings")).getOrElse(false)
    val mlxQuant = fields("quantization").filterNot(_.isNull).map(parseQuantization(_, path, "quantization"))
    val hfQuant = fields("quantization_config").filterNot(_.isNull).map(parseQuantization(_, path, "quantization_config"))
    val quantization = mlxQuant.orElse(hfQuant).getOrElse {
      Diagnostics.invalid(path, "quantization", "quantized checkpoint requires quantization settings")
    }
    Diagnostics.check(mlxQuant.isEmpty || hfQuant.isEmpty || mlxQuant == hfQuant,
      path, "quantization", "quantization and quantization_config disagree")
    ModelConfig(modelType, vocabSize, hiddenSize, tied, quantization, tokenIds(root, path), root)
  }

  def loadGeneration(path: Path): GenerationConfig =
    val root = JsonIO.read(path)
    JsonFields.obj(root, path, "generation config")
    GenerationConfig(tokenIds(root, path), root)

  private def tokenIds(root: Json, path: Path): Map[String, TokenIdSetting] =
    val fields = JsonFields.obj(root, path, "config")
    Vector("bos_token_id", "eos_token_id", "pad_token_id", "decoder_start_token_id")
      .flatMap { name => fields(name).filterNot(_.isNull).map { value =>
        val setting = value.asArray match
          case Some(ids) =>
            Diagnostics.check(ids.nonEmpty, path, name, "token ID list must not be empty")
            TokenIdSetting.Multiple(ids.zipWithIndex.map { (id, index) => tokenId(id, path, s"$name[$index]") })
          case None => TokenIdSetting.Single(tokenId(value, path, name))
        name -> setting
      }}.toMap

  private def parseQuantization(value: Json, path: Path, location: String): QuantizationConfig =
    val fields = JsonFields.obj(value, path, location)
    val bits = positiveInt(JsonFields.field(value, "bits", path, location), path, s"$location.bits")
    val group = positiveInt(JsonFields.field(value, "group_size", path, location), path, s"$location.group_size")
    val mode = fields("mode").map(JsonFields.text(_, path, s"$location.mode")).getOrElse("affine")
    val default = QuantizationSpec(bits, group, mode)
    val overrides = fields.toVector.filterNot((name, _) => Set("bits", "group_size", "mode").contains(name))
      .map { (name, overrideJson) =>
        val at = s"$location.$name"
        val overrideFields = JsonFields.obj(overrideJson, path, at)
        val overrideBits = overrideFields("bits").map(positiveInt(_, path, s"$at.bits")).getOrElse(bits)
        val overrideGroup = overrideFields("group_size").map(positiveInt(_, path, s"$at.group_size")).getOrElse(group)
        val overrideMode = overrideFields("mode").map(JsonFields.text(_, path, s"$at.mode")).getOrElse(mode)
        name -> QuantizationSpec(overrideBits, overrideGroup, overrideMode)
      }.toMap
    QuantizationConfig(default, overrides)

  private[vocabsurgery] def tokenId(value: Json, path: Path, location: String): OriginalTokenId =
    val id = JsonFields.int(value, path, location)
    Diagnostics.check(id >= 0, path, location, "token ID must be nonnegative")
    OriginalTokenId(id)

  private def positiveInt(value: Json, path: Path, location: String): Int =
    val number = JsonFields.int(value, path, location)
    Diagnostics.check(number > 0, path, location, "expected positive integer")
    number
