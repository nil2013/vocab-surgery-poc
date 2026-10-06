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
    assertEquals(plan.config.hcursor.get[Int]("vocab_size"), Right(plan.projection.compactSize))
    assertEquals(plan.config.hcursor.downField("quantization").focus, source.config.original.hcursor.downField("quantization").focus)
    assertEquals(plan.config.hcursor.get[Int]("architecture_extra"), Right(123))
    val projected = plan.shards.flatMap(_.tensors).filter(_.source.name != "body")
    assertEquals(projected.size, 6)
    assert(projected.forall(_.result.shape.head == plan.projection.compactSize.toLong))
    assert(projected.forall(_.copies.map(_.source.length.longValue).sum == plan.projection.compactSize * 4L))
    val body = plan.shards.head.tensors.find(_.source.name == "body").get
    assertEquals(body.result, body.source)
    assertEquals(body.copies, Vector(CopySpan(body.source.dataRange, body.source.dataRange)))
    assertEquals(plan.shards.head.header.hcursor.downField("__metadata__").focus, source.shards.head.originalHeader.hcursor.downField("__metadata__").focus)
    assertEquals(plan.shards.head.header.hcursor.downField("lm_head.weight").get[String]("future"), Right("preserve"))
    assertEquals(plan.index.hcursor.get[Int]("extra"), Right(99))
    assertEquals(plan.index.hcursor.downField("metadata").get[Int]("future"), Right(77))
    assertEquals(plan.index.hcursor.downField("metadata").get[Long]("total_size"), Right(plan.payloadBytes.longValue))
    assertEquals(plan.index.hcursor.downField("weight_map").focus, source.index.original.hcursor.downField("weight_map").focus)
    assert(plan.index.hcursor.downField("metadata").downField("total_parameters").focus.isEmpty)
    assert(plan.notices.exists(_.location == "index.metadata.total_parameters"))
    assert(plan.notices.exists(_.location == "generation.suppress_tokens"))
    assertEquals(plan.generation.hcursor.get[Vector[Int]]("suppress_tokens"), Right(Vector.empty))
    assertEquals(source.shards.head.tensors.head.dataRange.length.longValue, 3L)
    assertEquals(ModelOperations.plan(source, DomainFixtures.policy), plan)
  }

  test("known logical parameter counts update exact 64-bit values only under explicit policy") {
    val source = DomainFixtures.snapshot()
    val plan = ModelOperations.plan(source, DomainFixtures.policy, ParameterCountPolicy.VerifiedLogicalWeights)
    assertEquals(plan.index.hcursor.downField("metadata").get[Long]("total_parameters"), Right(9007199254740993L - 4L * 16L))
    val lowIndex = source.index.original.mapObject(fields => fields.add("metadata", fields("metadata").get.mapObject(_.add("total_parameters", Json.fromInt(1)))))
    intercept[DomainException](ModelOperations.plan(source.copy(index = source.index.copy(original = lowIndex)), DomainFixtures.policy, ParameterCountPolicy.VerifiedLogicalWeights))
  }

  test("required metadata and tokenizer references automatically protect forbidden lexical tokens") {
    val source = DomainFixtures.snapshot()
    val foreign = TokenizerFixtures.id(source.tokenizer, DomainFixtures.foreign)
    val generation = source.generation.copy(original = source.generation.original.mapObject(_.add("forced_eos_token_id", Json.fromInt(foreign.intValue))))
    val plan = ModelOperations.plan(source.copy(generation = generation), DomainFixtures.policy)
    assertEquals(plan.removedRows, 3)
    assert(plan.analysis.protectedConflicts.exists(_.id == foreign))
    assertEquals(plan.generation.hcursor.get[Int]("forced_eos_token_id"), Right(plan.projection.requireCompact(foreign).intValue))
    val tokenizerJson = source.tokenizer.original.mapObject(_.add("padding", Json.obj("pad_id" -> Json.fromInt(foreign.intValue))))
    assert(ModelOperations.plan(source.copy(tokenizer = source.tokenizer.copy(original = tokenizerJson)), DomainFixtures.policy).projection.compact(foreign).isDefined)
  }

  test("missing protocol in a template and stale or unsupported references fail before planning payloads") {
    val source = DomainFixtures.snapshot()
    intercept[DomainException](ModelOperations.plan(source.copy(chatTemplate = source.chatTemplate.copy(text = "<|missing_token|>")), DomainFixtures.policy))
    val config = source.config.copy(original = source.config.original.mapObject(_.add("bos_token_id", Json.fromInt(source.config.vocabSize - 1))))
    intercept[DomainException](ModelOperations.plan(source.copy(config = config), DomainFixtures.policy))
    val generation = source.generation.copy(original = source.generation.original.mapObject(_.add("future_token_ids", Json.arr(Json.fromInt(0)))))
    intercept[DomainException](ModelOperations.plan(source.copy(generation = generation), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(source.copy(tokenizerConfig = Json.obj("pad_token" -> Json.fromString("missing"))), DomainFixtures.policy))
  }

  test("model, tokenizer, companion, index and source header inconsistencies fail with domain diagnostics") {
    val source = DomainFixtures.snapshot()
    intercept[DomainException](ModelOperations.plan(replaceConfig(source)(_.copy(modelType = "other")), DomainFixtures.policy))
    intercept[DomainException](ModelOperations.plan(replaceConfig(source)(_.copy(tieWordEmbeddings = true)), DomainFixtures.policy))
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
