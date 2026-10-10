package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.tokenizer.*

class ModelOperationsSuite extends munit.FunSuite:
  private def replaceConfig(source: ModelSnapshot)(f: ModelConfig => ModelConfig): ModelSnapshot = source.copy(config = f(source.config))

  test("pure plan compacts registered rows, projects all six tensors and preserves body / unknown fields") {
    val source = DomainFixtures.snapshot()
    val plan = ModelOperations.plan(source, DomainFixtures.policy)
    assertEquals(plan.removedRows, 4)
    assertEquals(plan.analysis.unregisteredIds.size, 3)
    assertEquals(plan.savedPayloadBytes.longValue, 4L * 24L)
    assertEquals(plan.json("config.json").hcursor.get[Int]("vocab_size"), Right(plan.projection.compactSize))
    assertEquals(plan.json("config.json").hcursor.downField("quantization").focus, source.config.original.hcursor.downField("quantization").focus)
    assertEquals(plan.json("config.json").hcursor.get[Int]("architecture_extra"), Right(123))
    val projected = plan.shards.flatMap(_.tensors).filter(_.source.name != "body")
    assertEquals(projected.size, 6)
    assert(projected.forall(_.result.shape.head == plan.projection.compactSize.toLong))
    assert(projected.forall(_.copies.map(_.source.length.longValue).sum == plan.projection.compactSize * 4L))
    val body = plan.shards.head.tensors.find(_.source.name == "body").get
    assertEquals(body.result, body.source)
    assertEquals(body.copies, Vector(CopySpan(body.source.dataRange, body.source.dataRange)))
    assertEquals(plan.shards.head.header.hcursor.downField("__metadata__").focus, source.shards.head.originalHeader.hcursor.downField("__metadata__").focus)
    assertEquals(plan.shards.head.header.hcursor.downField("lm_head.weight").get[String]("future"), Right("preserve"))
    assertEquals(plan.json("model.safetensors.index.json").hcursor.get[Int]("extra"), Right(99))
    assertEquals(plan.json("model.safetensors.index.json").hcursor.downField("metadata").get[Int]("future"), Right(77))
    assertEquals(plan.json("model.safetensors.index.json").hcursor.downField("metadata").get[Long]("total_size"), Right(plan.payloadBytes.longValue))
    assertEquals(plan.json("model.safetensors.index.json").hcursor.downField("weight_map").focus, source.index.original.hcursor.downField("weight_map").focus)
    assert(plan.json("model.safetensors.index.json").hcursor.downField("metadata").downField("total_parameters").focus.isEmpty)
    assert(plan.notices.exists(_.location == "index.metadata.total_parameters"))
    assert(plan.notices.exists(_.location == "generation.suppress_tokens"))
    assertEquals(plan.json("generation_config.json").hcursor.get[Vector[Int]]("suppress_tokens"), Right(Vector.empty))
    assertEquals(source.shards.head.tensors.head.dataRange.length.longValue, 3L)
    assertEquals(ModelOperations.plan(source, DomainFixtures.policy), plan)
  }

  test("known logical parameter counts update exact 64-bit values only under explicit policy") {
    val source = DomainFixtures.snapshot()
    val plan = ModelOperations.plan(source, DomainFixtures.policy, ParameterCountPolicy.VerifiedLogicalWeights)
    assertEquals(plan.json("model.safetensors.index.json").hcursor.downField("metadata").get[Long]("total_parameters"), Right(9007199254740993L - 4L * 16L))
    val lowIndex = source.index.original.mapObject(fields => fields.add("metadata", fields("metadata").get.mapObject(_.add("total_parameters", Json.fromInt(1)))))
    intercept[DomainException](ModelOperations.plan(source.copy(index = source.index.copy(original = lowIndex)), DomainFixtures.policy, ParameterCountPolicy.VerifiedLogicalWeights))
  }

  test("required metadata and tokenizer references automatically protect forbidden lexical tokens") {
    val source = DomainFixtures.snapshot()
    val foreign = TokenizerFixtures.id(source.tokenizer, DomainFixtures.foreign)
    val generation = source.generation.mapObject(_.add("forced_eos_token_id", Json.fromInt(foreign.intValue)))
    val plan = ModelOperations.plan(source.copy(generation = generation), DomainFixtures.policy)
    assertEquals(plan.removedRows, 3)
    assert(plan.analysis.protectedConflicts.exists(_.id == foreign))
    assertEquals(plan.json("generation_config.json").hcursor.get[Int]("forced_eos_token_id"), Right(plan.projection.requireCompact(foreign).intValue))
    val tokenizerJson = source.tokenizer.original.mapObject(_.add("padding", Json.obj("pad_id" -> Json.fromInt(foreign.intValue))))
    assert(ModelOperations.plan(source.copy(tokenizer = source.tokenizer.copy(original = tokenizerJson)), DomainFixtures.policy).projection.compact(foreign).isDefined)
  }

  test("missing protocol in a template and stale or unsupported references fail before planning payloads") {
    val source = DomainFixtures.snapshot()
    intercept[DomainException](ModelOperations.plan(source.copy(chatTemplate = source.chatTemplate.map(_.copy(text = "<|missing_token|>"))), DomainFixtures.policy))
    val config = source.config.copy(original = source.config.original.mapObject(_.add("bos_token_id", Json.fromInt(source.config.vocabSize - 1))))
    intercept[DomainException](ModelOperations.plan(source.copy(config = config), DomainFixtures.policy))
    val generation = source.generation.mapObject(_.add("future_token_ids", Json.arr(Json.fromInt(0))))
    intercept[DomainException](ModelOperations.plan(source.copy(generation = generation), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(source.copy(tokenizerConfig = Json.obj("pad_token" -> Json.fromString("missing"))), DomainFixtures.policy))
  }

  test("model, tokenizer, companion, index and source header inconsistencies fail with domain diagnostics") {
    val source = DomainFixtures.snapshot()
    intercept[DomainException](ModelOperations.plan(replaceConfig(source)(_.copy(family = FlashNext)), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(replaceConfig(source)(c => c.copy(original = c.original.mapObject(_.add("tie_word_embeddings", Json.True)))), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(replaceConfig(source)(c => c.copy(original = c.original.mapObject(_.add("model_type", Json.fromString("other"))))), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(replaceConfig(source)(_.copy(vocabSize = source.config.vocabSize + 1)), DomainFixtures.policy))
    val missing = source.shards.head.copy(tensors = source.shards.head.tensors.filterNot(_.name == "lm_head.biases"))
    intercept[DomainException](ModelOperations.plan(source.copy(shards = Vector(missing)), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(source.copy(shards = source.shards ++ source.shards), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(source.copy(index = source.index.copy(totalSize = Some(ByteCount(1)))), DomainFixtures.policy))
    val badHeader = source.shards.head.copy(originalHeader = source.shards.head.originalHeader.mapObject(_.remove("body")))
    intercept[DomainException](ModelOperations.plan(source.copy(shards = Vector(badHeader)), DomainFixtures.policy))
    val badShape = source.shards.head.copy(originalHeader = source.shards.head.originalHeader.mapObject(fields => fields.add("body", fields("body").get.mapObject(_.add("shape", Json.arr(Json.fromInt(4)))))))
    intercept[DomainException](ModelOperations.plan(source.copy(shards = Vector(badShape)), DomainFixtures.policy))
    Vector("normalizer" -> Json.obj("type" -> Json.fromString("NFKC")), "pre_tokenizer" -> Json.obj("type" -> Json.fromString("Whitespace")),
      "decoder" -> Json.obj("type" -> Json.fromString("Metaspace"))).foreach { (field, json) =>
      val tokenizer = source.tokenizer.copy(original = source.tokenizer.original.mapObject(_.add(field, json)))
      intercept[DomainException](ModelOperations.plan(source.copy(tokenizer = tokenizer), DomainFixtures.policy))
    }
    Vector("byte_fallback" -> Json.True, "ignore_merges" -> Json.True, "continuing_subword_prefix" -> Json.fromString("##")).foreach { (field, json) =>
      val tokenizer = source.tokenizer.copy(original = source.tokenizer.original.mapObject(fields => fields.add("model", fields("model").get.mapObject(_.add(field, json)))))
      intercept[DomainException](ModelOperations.plan(source.copy(tokenizer = tokenizer), DomainFixtures.policy))
    }
  }

  test("shard planning moves untouched payload ranges intact and rejects holes / overlaps") {
    val source = DomainFixtures.snapshot()
    val shard = source.shards.head
    val projection = VocabularyProjection.fromSurvivors(source.config.vocabSize, Vector(OriginalTokenId(0), OriginalTokenId(2)))
    val plan = ShardProjection.plan(shard, Set("model.embed_tokens.weight"), projection)
    val head = plan.tensors.find(_.source.name == "lm_head.weight").get
    assertEquals(head.result.shape, head.source.shape)
    assertEquals(head.copies.head.source, head.source.dataRange)
    assert(head.result.dataRange.start.longValue < head.source.dataRange.start.longValue)
    val unchanged = ShardProjection.plan(shard, Set.empty, projection)
    assertEquals(unchanged.changed, false)
    assertEquals(unchanged.header, shard.originalHeader)
    val broken = shard.tensors.head.copy(dataRange = ByteRange(ByteOffset(1), ByteCount(3)))
    intercept[DomainException](ShardProjection.plan(shard.copy(tensors = broken +: shard.tensors.tail), Set.empty, projection))
    intercept[DomainException](ShardProjection.plan(shard.copy(fileSize = ByteCount(shard.fileSize.longValue + 1)), Set.empty, projection))
  }

  test("Flash-Next plan rewrites nested and vision IDs, slow tokenizer files and records PLE token IDs") {
    val source = DomainFixtures.flashNext()
    val plan = ModelOperations.plan(source, DomainFixtures.policy)
    val projection = plan.projection
    def compact(content: String) = projection.requireCompact(TokenizerFixtures.id(source.tokenizer, content)).intValue
    val config = plan.json("config.json")
    assertEquals(plan.family, FlashNext)
    assertEquals(config.hcursor.downField("text_config").get[Int]("vocab_size"), Right(projection.compactSize))
    assert(!config.hcursor.downField("vocab_size").succeeded)
    assertEquals(config.hcursor.get[Int]("image_token_id"), Right(compact("<|image_pad|>")))
    assertEquals(config.hcursor.get[Vector[Int]]("eos_token_id"), Right(Vector(compact("<|im_end|>"), compact("<|endoftext|>"))))
    assertEquals(config.hcursor.downField("text_config").get[Int]("eos_token_id"), Right(compact("<|endoftext|>")))
    assertEquals(config.hcursor.downField("generation_config").get[Int]("pad_token_id"), Right(compact("<|endoftext|>")))
    assertEquals(config.hcursor.downField("generation_config").get[Int]("top_k"), Right(20))
    assertEquals(config.hcursor.downField("vision_config").focus, source.config.original.hcursor.downField("vision_config").focus)
    assertEquals(plan.shards.flatMap(_.tensors).count(t => t.result.shape.head == projection.compactSize.toLong && t.source.name != "body"), 6)
    assertEquals(plan.outputFiles.map(_.name).toSet, Set("config.json", "generation_config.json", "tokenizer.json", "tokenizer_config.json",
      "model.safetensors.index.json", "vocab.json", "merges.txt", PleTokenIds.FileName))
    val tokenizer = plan.json("tokenizer.json")
    assertEquals(plan.json("vocab.json"), tokenizer.hcursor.downField("model").downField("vocab").focus.get)
    val merges = tokenizer.hcursor.downField("model").get[Vector[Vector[String]]]("merges").toOption.get.map(_.mkString(" "))
    assertEquals(plan.file("merges.txt").content, merges.mkString("", "\n", "\n"))
    val table = PleTokenIds.parse(plan.json(PleTokenIds.FileName))
    assertEquals(table.originalVocabSize, source.config.vocabSize)
    assertEquals(table.originalTokenIds, projection.compactToOriginal.map(_.intValue))
  }

  test("re-pruning composes PLE token IDs with the earlier table instead of restarting from the current IDs") {
    val source = DomainFixtures.flashNext()
    val rows = source.config.vocabSize
    val earlier = PleTokenIds(rows * 2 + 10, Vector.range(0, rows).map(_ * 2 + 1))
    val plan = ModelOperations.plan(source.copy(familyFiles = Map(PleTokenIds.FileName -> earlier.json)), DomainFixtures.policy)
    val table = PleTokenIds.parse(plan.json(PleTokenIds.FileName))
    assertEquals(table.originalVocabSize, rows * 2 + 10)
    assertEquals(table.originalTokenIds, plan.projection.compactToOriginal.map(id => id.intValue * 2 + 1))
    intercept[DomainException](ModelOperations.plan(source.copy(familyFiles = Map(PleTokenIds.FileName -> PleTokenIds(rows, Vector(0, 1)).json)), DomainFixtures.policy))
  }

  test("token IDs outside the family's scopes, stale slow tokenizer files and unknown model types stop the plan") {
    val source = DomainFixtures.flashNext()
    def withConfig(f: Json => Json) = source.copy(config = source.config.copy(original = f(source.config.original)))
    val vision = withConfig(_.mapObject(fields => fields.add("vision_config", fields("vision_config").get.mapObject(_.add("patch_token_id", Json.fromInt(0))))))
    intercept[DomainException](ModelOperations.plan(vision, DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(withConfig(_.mapObject(_.add("audio_token_id", Json.fromInt(0)))), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(source.copy(tokenizerCompanions = source.tokenizerCompanions.updated("merges.txt", "a b\n")), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(source.copy(tokenizerCompanions = source.tokenizerCompanions.updated("vocab.json", "{}")), DomainFixtures.policy))
    val shared = withConfig(_.mapObject(fields => fields.add("text_config", fields("text_config").get.mapObject(_.add("tie_word_embeddings", Json.True)))))
    intercept[DomainException](ModelOperations.plan(shared, DomainFixtures.policy))
    intercept[DomainException](ModelFamily.of(Json.obj("model_type" -> Json.fromString("gemma4"))))
    assertEquals(ModelFamily.of(source.config.original), FlashNext)
  }

  test("dense vocabulary modules project their single weight without quantization settings") {
    val source = DomainFixtures.dense(DomainFixtures.dense(DomainFixtures.snapshot(), "model.embed_tokens"), "lm_head")
    val unquantized = source.copy(config = source.config.copy(quantization = None, original = source.config.original.mapObject(_.remove("quantization"))))
    val plan = ModelOperations.plan(unquantized, DomainFixtures.policy)
    assertEquals(plan.modules.map(_.encoding), Vector(RowEncoding.Dense, RowEncoding.Dense))
    assertEquals(plan.savedPayloadBytes.longValue, plan.removedRows * 2L * 16L)
    val mixed = DomainFixtures.dense(DomainFixtures.snapshot(), "lm_head")
    assertEquals(ModelOperations.plan(mixed, DomainFixtures.policy).modules.map(_.encoding),
      Vector(RowEncoding.AffineQuantized(QuantizationSpec(4, 4, "affine")), RowEncoding.Dense))
    val packed = DomainFixtures.snapshot()
    intercept[DomainException](ModelOperations.plan(packed.copy(config = packed.config.copy(quantization = None)), DomainFixtures.policy))
  }

  for (modelType, unquantized) <- Vector("qwen3_5" -> false, "qwen3_5_moe" -> false, "qwen3_5_moe" -> true) do
    test(s"Qwen3.5 $modelType dense=$unquantized projects vocabulary and rewrites every nested ID without PLE") {
      val source = DomainFixtures.qwen35(modelType, unquantized)
      val plan = ModelOperations.plan(source, DomainFixtures.policy)
      assertEquals(ModelFamily.of(source.config.original), Qwen35)
      assertEquals(plan.family, Qwen35)
      assertEquals(plan.removedRows, 4)
      assertEquals(plan.modules.map(_.encoding), if unquantized then Vector.fill(2)(RowEncoding.Dense)
        else Vector.fill(2)(RowEncoding.AffineQuantized(QuantizationSpec(8, 4, "affine"))))
      assertEquals(plan.savedPayloadBytes.longValue, plan.removedRows * 32L)
      assertEquals(plan.modules.flatMap(_.tensors).size, if unquantized then 2 else 6)
      val config = plan.json("config.json")
      assertEquals(config.hcursor.get[String]("model_type"), Right(modelType))
      assertEquals(config.hcursor.downField("text_config").get[Int]("vocab_size"), Right(plan.projection.compactSize))
      val before = TokenReferences.required(source.config.original, Qwen35.tokenIdScopes, Qwen35.tokenIdFields)
      val after = TokenReferences.required(config, Qwen35.tokenIdScopes, Qwen35.tokenIdFields)
      assertEquals(after, before.map((at, id) => at -> OriginalTokenId(plan.projection.requireCompact(id).intValue)))
      assertEquals(config.hcursor.downField("quantization").focus, source.config.original.hcursor.downField("quantization").focus)
      assertEquals(config.hcursor.downField("quantization_config").focus, source.config.original.hcursor.downField("quantization_config").focus)
      assert(!plan.outputFiles.exists(_.name == PleTokenIds.FileName))
      assertEquals(plan.json("vocab.json"), plan.json("tokenizer.json").hcursor.downField("model").downField("vocab").focus.get)
      assert(source.tokenizer.addedTokens.forall(t => plan.projection.compact(t.id).isDefined))
    }

  test("Qwen3.5 rejects shared embeddings, dedicated MTP embeddings and unscoped modality IDs") {
    val source = DomainFixtures.qwen35()
    for path <- Vector(JsonPath("tie_word_embeddings"), JsonPath("text_config", "tie_word_embeddings"),
      JsonPath("text_config", "mtp_use_dedicated_embeddings")) do
      val changed = source.copy(config = source.config.copy(original = path.modify(source.config.original)(_ => Json.True)))
      assert(intercept[DomainException](ModelOperations.plan(changed, DomainFixtures.policy)).getMessage.contains(path.text))
    val unscoped = source.config.original.mapObject(fields => fields.add("vision_config",
      fields("vision_config").get.mapObject(_.add("image_token_id", Json.fromInt(0)))))
    assert(intercept[DomainException](ModelOperations.plan(source.copy(config = source.config.copy(original = unscoped)),
      DomainFixtures.policy)).getMessage.contains("token-ID field outside"))
    intercept[DomainException](ModelFamily.of(Json.obj("model_type" -> Json.fromString("qwen3"))))
  }
