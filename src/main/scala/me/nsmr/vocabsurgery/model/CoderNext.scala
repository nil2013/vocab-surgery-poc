package me.nsmr.vocabsurgery.model

import io.circe.Json

/** Qwen3-Coder-Next: a text-only checkpoint with its dimensions and token IDs at the top of `config.json`. */
object CoderNext extends ModelFamily:
  val name = "coder-next"
  def accepts(config: Json): Boolean = ModelFamily.modelType(config).contains("qwen3_next")
  val vocabSize: Vector[JsonPath] = Vector(JsonPath("vocab_size"))
  val hiddenSize: JsonPath = JsonPath("hidden_size")
  val tokenIdScopes: Vector[JsonPath] = Vector(JsonPath.root)

  def vocabularyModules(config: Json): Vector[String] =
    ModelFamily.requireUntied(config, JsonPath("tie_word_embeddings"))
    Vector("model.embed_tokens", "lm_head")

  val requiredTokens: Set[String] = Set("<|endoftext|>", "<|im_start|>", "<|im_end|>",
    "<|fim_prefix|>", "<|fim_middle|>", "<|fim_suffix|>", "<|fim_pad|>", "<|repo_name|>", "<|file_sep|>",
    "<tool_call>", "</tool_call>", "<tool_response>", "</tool_response>", "<think>", "</think>")
