package me.nsmr.vocabsurgery.model

import java.nio.file.Path
import me.nsmr.vocabsurgery.io.*

/** Load and cross-check source metadata, reading no tensor payload. Input symlinks are followed read-only. */
object SnapshotLoader:
  def load(directory: Path): ModelSnapshot = Diagnostics.guard(directory, "snapshot") {
    val configPath = directory.resolve("config.json")
    val config = ConfigLoader.loadModel(configPath)
    val generationPath = directory.resolve("generation_config.json")
    val generation = ConfigLoader.loadGeneration(generationPath)
    val tokenizerPath = directory.resolve("tokenizer.json")
    val tokenizer = TokenizerLoader.load(tokenizerPath)
    val tokenizerConfig = JsonIO.read(directory.resolve("tokenizer_config.json"))
    JsonFields.obj(tokenizerConfig, directory.resolve("tokenizer_config.json"), "tokenizer config")
    val indexPath = directory.resolve("model.safetensors.index.json")
    val index = ShardIndexLoader.load(indexPath)
    val shards = index.weightMap.values.toVector.distinct.sorted.map(name => SafetensorsIO.read(directory.resolve(name)))
    val shardTensors = shards.flatMap(shard => shard.tensors.map(tensor => (tensor.name, shard.path.getFileName.toString, tensor)))
    Diagnostics.check(shardTensors.map(_._1).distinct.size == shardTensors.size, indexPath, "weight_map", "tensor appears in multiple shards")
    val actualMap = shardTensors.map((name, filename, _) => name -> filename).toMap
    Diagnostics.check(actualMap == index.weightMap, indexPath, "weight_map", "index and shard headers disagree")
    val totalBytes = shards.foldLeft(0L) { (sum, shard) =>
      Math.addExact(sum, shard.fileSize.longValue - shard.payloadStart.longValue)
    }
    Diagnostics.check(index.totalSize.forall(_.longValue == totalBytes), indexPath, "metadata.total_size", "index payload byte count disagrees with headers")

    val registered = tokenizer.vocabulary.map(_.id) ++ tokenizer.addedTokens.map(_.id)
    Diagnostics.check(registered.forall(_.intValue < config.vocabSize), tokenizerPath, "token IDs", "token ID exceeds config vocab_size")
    val registeredIds = registered.toSet
    Vector(configPath -> config.tokenIds, generationPath -> generation.tokenIds).foreach { (source, references) =>
      references.foreach { (name, value) =>
        val ids = value match
          case TokenIdSetting.Single(id) => Vector(id)
          case TokenIdSetting.Multiple(ids) => ids
        Diagnostics.check(ids.forall(registeredIds.contains), source, name, "config references an unregistered token ID")
      }
    }
    val tensorsByName = shardTensors.map((name, _, tensor) => name -> tensor).toMap
    val prefixes = if config.tieWordEmbeddings then Vector("model.embed_tokens") else Vector("model.embed_tokens", "lm_head")
    prefixes.foreach { prefix => Vector("weight", "scales", "biases").foreach { suffix =>
      val name = s"$prefix.$suffix"
      val tensor = tensorsByName.getOrElse(name, Diagnostics.invalid(indexPath, name, "required surgery tensor missing"))
      Diagnostics.check(tensor.shape.size == 2 && tensor.shape.head == config.vocabSize.toLong,
        indexPath, name, "expected rank-2 tensor with config vocab_size rows")
    }}

    val templatePath = directory.resolve("chat_template.jinja")
    val template = TextAsset(templatePath, FileIO.readUtf8(templatePath))
    val metadataPaths = Vector(configPath, directory.resolve("generation_config.json"), tokenizerPath,
      directory.resolve("tokenizer_config.json"), indexPath, templatePath)
    val fingerprints = metadataPaths.map(path => SourceFingerprint(path, FileIO.sha256(path)))
    ModelSnapshot(directory, config, generation, tokenizer, tokenizerConfig, index, shards, template, fingerprints)
  }
