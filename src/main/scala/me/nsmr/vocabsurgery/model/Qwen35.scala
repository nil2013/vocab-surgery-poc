package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*

/** Qwen3.5 dense / MoE layout, including the Qwen3.8-27B and Ornith checkpoints whose configs retain these model types. */
object Qwen35 extends ModelFamily:
  val name = "qwen35"
  def accepts(config: Json): Boolean = ModelFamily.modelType(config).exists(Set("qwen3_5", "qwen3_5_moe"))
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
