package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.tokenizer.VocabularyProjection

/** For each vocabulary row of a Flash-Next checkpoint, the token ID its PLE n-gram hashing was trained with.
  * PLE hashes token IDs to pick table rows, so a checkpoint with renumbered rows needs this table to keep
  * looking up the rows the upstream IDs selected.
  */
final case class PleTokenIds(originalVocabSize: Int, originalTokenIds: Vector[Int]):
  DomainChecks.check(originalTokenIds.nonEmpty && originalTokenIds.head >= 0 && originalTokenIds.last < originalVocabSize &&
    originalTokenIds.indices.tail.forall(i => originalTokenIds(i - 1) < originalTokenIds(i)),
    "invalid-ple-token-ids", PleTokenIds.FileName, "IDs must be strictly increasing within the original vocabulary")

  /** The table for a checkpoint that keeps `projection`'s rows of the checkpoint this table describes. */
  def project(projection: VocabularyProjection): PleTokenIds =
    DomainChecks.check(projection.originalSize == originalTokenIds.size, "ple-token-ids-mismatch", PleTokenIds.FileName,
      "table length differs from the source vocabulary rows")
    PleTokenIds(originalVocabSize, projection.compactToOriginal.map(id => originalTokenIds(id.intValue)))

  def json: Json = Json.obj("originalVocabSize" -> Json.fromInt(originalVocabSize),
    "originalTokenIds" -> Json.fromValues(originalTokenIds.map(Json.fromInt)))

object PleTokenIds:
  val FileName = "ple-token-ids.json"

  /** An unmodified checkpoint: every row still has the ID PLE was trained with. */
  def upstream(vocabSize: Int): PleTokenIds = PleTokenIds(vocabSize, Vector.range(0, vocabSize))

  def parse(json: Json): PleTokenIds =
    val size = DomainJson.integer(DomainJson.required(json, "originalVocabSize", FileName), "originalVocabSize")
    val ids = DomainJson.array(DomainJson.required(json, "originalTokenIds", FileName), "originalTokenIds")
    PleTokenIds(size, ids.map(DomainJson.integer(_, "originalTokenIds")))

/** Qwen3.8-Flash-Next (`qwen4_exp`): a vision-language checkpoint whose text model sits under `text_config`
  * and `language_model.*`, with PLE n-gram tables keyed by token-ID hashes and an MTP block sharing the head.
  */
object FlashNext extends ModelFamily:
  val name = "flash-next"
  def accepts(config: Json): Boolean = ModelFamily.modelType(config).contains("qwen4_exp")
  private val text = JsonPath("text_config")
  val vocabSize: Vector[JsonPath] = Vector(text / "vocab_size")
  val hiddenSize: JsonPath = text / "hidden_size"
  val tokenIdScopes: Vector[JsonPath] = Vector(JsonPath.root, text, JsonPath("generation_config"))
  override val tokenIdFields: Set[String] = Set("image_token_id", "video_token_id", "vision_start_token_id", "vision_end_token_id")

  def vocabularyModules(config: Json): Vector[String] =
    ModelFamily.requireUntied(config, JsonPath("tie_word_embeddings"), text / "tie_word_embeddings")
    DomainChecks.check((text / "mtp_use_dedicated_embeddings").get(config).forall(_ == Json.False), "unsupported-model",
      "text_config.mtp_use_dedicated_embeddings", "expected MTP to share the vocabulary embedding")
    Vector("language_model.model.embed_tokens", "language_model.lm_head")

  val requiredTokens: Set[String] = Set("<|endoftext|>", "<|im_start|>", "<|im_end|>",
    "<tool_call>", "</tool_call>", "<tool_response>", "</tool_response>", "<think>", "</think>")

  override val familyFileNames: Set[String] = Set(PleTokenIds.FileName)

  /** A source that was itself pruned carries a table; projecting it keeps pointing at the upstream IDs. */
  override def familyFiles(config: Json, sourceFiles: Map[String, Json], projection: VocabularyProjection): Vector[OutputFile] =
    val pleLayers = (text / "ple_layer_ids").get(config).flatMap(_.asArray).getOrElse(Vector.empty)
    val source = sourceFiles.get(PleTokenIds.FileName).map(PleTokenIds.parse)
    if pleLayers.isEmpty then
      DomainChecks.check(source.isEmpty, "unexpected-ple-token-ids", PleTokenIds.FileName, "checkpoint has no PLE layers")
      Vector.empty
    else Vector(OutputFile.JsonFile(PleTokenIds.FileName, source.getOrElse(PleTokenIds.upstream(projection.originalSize)).project(projection).json))
