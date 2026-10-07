package me.nsmr.vocabsurgery.model

import java.nio.file.{Files, Path}
import me.nsmr.vocabsurgery.io.*

/** Load and cross-check source metadata, reading no tensor payload. Input symlinks are followed read-only. */
object SnapshotLoader:
  def load(directory: Path): ModelSnapshot = Diagnostics.guard(directory, "snapshot") {
    val configPath = directory.resolve("config.json")
    val config = ConfigLoader.loadModel(configPath)
    val family = config.family
    val generationPath = directory.resolve("generation_config.json")
    val generation = JsonIO.read(generationPath)
    JsonFields.obj(generation, generationPath, "generation config")
    val tokenizerPath = directory.resolve("tokenizer.json")
    val tokenizer = TokenizerLoader.load(tokenizerPath)
    val tokenizerConfigPath = directory.resolve("tokenizer_config.json")
    val tokenizerConfig = JsonIO.read(tokenizerConfigPath)
    JsonFields.obj(tokenizerConfig, tokenizerConfigPath, "tokenizer config")
    def present(names: Set[String]): Vector[Path] = names.toVector.sorted.map(directory.resolve).filter(Files.exists(_))
    val companionPaths = present(family.tokenizer.companionNames)
    val familyPaths = present(family.familyFileNames)
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
    val references = Vector(configPath -> Diagnostics.guard(configPath, "token IDs")(
        TokenReferences.required(config.original, family.tokenIdScopes, family.tokenIdFields)),
      generationPath -> Diagnostics.guard(generationPath, "token IDs")(TokenReferences.required(generation)))
    references.foreach { (source, found) =>
      found.foreach((location, id) => Diagnostics.check(registeredIds(id), source, location, "config references an unregistered token ID"))
    }
    val tensorsByName = shardTensors.map((name, _, tensor) => name -> tensor).toMap
    val modules = Diagnostics.guard(configPath, "vocabulary modules")(family.vocabularyModules(config.original))
    modules.foreach { prefix =>
      val name = s"$prefix.weight"
      val tensor = tensorsByName.getOrElse(name, Diagnostics.invalid(indexPath, name, "required surgery tensor missing"))
      Diagnostics.check(tensor.shape.size == 2 && tensor.shape.head == config.vocabSize.toLong,
        indexPath, name, "expected rank-2 tensor with config vocab_size rows")
    }

    val templatePath = directory.resolve("chat_template.jinja")
    val template = Option.when(Files.exists(templatePath))(TextAsset(templatePath, FileIO.readUtf8(templatePath)))
    val metadataPaths = Vector(configPath, generationPath, tokenizerPath, tokenizerConfigPath, indexPath) ++
      template.map(_.path) ++ companionPaths ++ familyPaths
    val fingerprints = metadataPaths.map(path => SourceFingerprint(path, FileIO.sha256(path)))
    ModelSnapshot(directory, config, generation, tokenizer, tokenizerConfig,
      companionPaths.map(path => path.getFileName.toString -> FileIO.readUtf8(path)).toMap,
      familyPaths.map(path => path.getFileName.toString -> JsonIO.read(path)).toMap,
      index, shards, template, fingerprints)
  }
