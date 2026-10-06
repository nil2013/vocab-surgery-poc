package me.nsmr.vocabsurgery.model

import java.nio.file.Path

/** Opt-in local integration test; does not download or read tensor payloads. */
class LocalSnapshotSuite extends munit.FunSuite:
  test("local Qwen3-Coder-Next 4bit snapshot matches the recorded source metadata") {
    val source = sys.props.get("vocabSurgery.snapshot")
    assume(source.isDefined, "set -DvocabSurgery.snapshot=<snapshot> to run the local integration test")
    val snapshot = SnapshotLoader.load(Path.of(source.get))
    assertEquals(snapshot.config.modelType, "qwen3_next")
    assertEquals(snapshot.config.vocabSize, 151936)
    assertEquals(snapshot.config.hiddenSize, 2048)
    assertEquals(snapshot.config.quantization.default, QuantizationSpec(4, 64, "affine"))
    assertEquals(snapshot.tokenizer.vocabulary.size, 151643)
    assertEquals(snapshot.tokenizer.addedTokens.size, 26)
    assertEquals(snapshot.tokenizer.merges.size, 151387)
    assertEquals(snapshot.shards.size, 9)
    assertEquals(snapshot.shards.map(_.tensors.size).sum, 1891)
    assertEquals(snapshot.index.totalSize.map(_.longValue), Some(44844060160L))
    val registered = (snapshot.tokenizer.vocabulary.map(_.id) ++ snapshot.tokenizer.addedTokens.map(_.id)).toSet
    assertEquals(snapshot.config.vocabSize - registered.size, 267)
    val targets = snapshot.shards.flatMap(_.tensors).filter { tensor =>
      tensor.name.startsWith("model.embed_tokens.") || tensor.name.startsWith("lm_head.")
    }
    assertEquals(targets.size, 6)
    assertEquals(targets.map(_.dataRange.length.longValue).sum, 350060544L)
    val tool = snapshot.tokenizer.addedTokens.find(_.content == "<tool_call>").get
    assertEquals(tool.special, false)
    println(s"Inspected ${snapshot.shards.size} shards / ${snapshot.shards.map(_.tensors.size).sum} tensors without loading payloads")
  }
