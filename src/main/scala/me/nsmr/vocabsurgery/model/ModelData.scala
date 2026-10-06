package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*

final case class QuantizationSpec(bits: Int, groupSize: Int, mode: String)
final case class QuantizationConfig(default: QuantizationSpec, overrides: Map[String, QuantizationSpec])

/** The dimensions surgery needs, read where `family` keeps them. Unknown fields stay in the immutable original JSON. */
final case class ModelConfig(
    family: ModelFamily,
    vocabSize: Int,
    hiddenSize: Int,
    quantization: Option[QuantizationConfig],
    original: Json
)

/** The key is ByteLevel encoded text, not a decoded TokenSurface. */
final case class LexicalToken(encoded: String, id: OriginalTokenId)
final case class BpeMerge(left: String, right: String)
final case class AddedToken(
    id: OriginalTokenId,
    content: String,
    special: Boolean,
    singleWord: Boolean,
    leftStrip: Boolean,
    rightStrip: Boolean,
    normalized: Boolean,
    original: Json
)

final case class TokenizerData(
    vocabulary: Vector[LexicalToken],
    merges: Vector[BpeMerge],
    addedTokens: Vector[AddedToken],
    original: Json
)

final case class ShardIndex(
    weightMap: Map[String, String],
    totalSize: Option[ByteCount],
    totalParameters: Option[Long],
    original: Json
)

final case class SourceFingerprint(path: Path, sha256: String)
final case class TextAsset(path: Path, text: String)

/** All input metadata for surgery. Tensor payloads are deliberately absent.
  * `tokenizerCompanions` and `familyFiles` hold the source copies of the files the tokenizer scheme and the family name.
  */
final case class ModelSnapshot(
    directory: Path,
    config: ModelConfig,
    generation: Json,
    tokenizer: TokenizerData,
    tokenizerConfig: Json,
    tokenizerCompanions: Map[String, String],
    familyFiles: Map[String, Json],
    index: ShardIndex,
    shards: Vector[SafetensorsFile],
    chatTemplate: Option[TextAsset],
    metadataFingerprints: Vector[SourceFingerprint]
)
