package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*

/** Typed access to surgery-relevant fields at the family's paths; all other fields are retained verbatim as JSON values. */
object ConfigLoader:
  def loadModel(path: Path): ModelConfig = Diagnostics.guard(path, "model config") {
    val root = JsonIO.read(path)
    val fields = JsonFields.obj(root, path, "config")
    val family = ModelFamily.of(root)
    def required(at: JsonPath): Json = at.get(root).filterNot(_.isNull).getOrElse(Diagnostics.invalid(path, at.text, "required field is missing or null"))
    val sizes = family.vocabSize.map(at => positiveInt(required(at), path, at.text))
    Diagnostics.check(sizes.distinct.size == 1, path, family.vocabSize.map(_.text).mkString(", "), "vocabulary sizes disagree")
    val hiddenSize = positiveInt(required(family.hiddenSize), path, family.hiddenSize.text)
    val mlxQuant = fields("quantization").filterNot(_.isNull).map(parseQuantization(_, path, "quantization"))
    val hfQuant = fields("quantization_config").filterNot(_.isNull).map(parseQuantization(_, path, "quantization_config"))
    Diagnostics.check(mlxQuant.isEmpty || hfQuant.isEmpty || mlxQuant == hfQuant,
      path, "quantization", "quantization and quantization_config disagree")
    ModelConfig(family, sizes.head, hiddenSize, mlxQuant.orElse(hfQuant), root)
  }

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
