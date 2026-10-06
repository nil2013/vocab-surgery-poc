package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit
import scala.util.Using
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.kanji.{JapaneseKanjiReference, KanjiLoader}
import me.nsmr.vocabsurgery.tokenizer.*
import me.nsmr.vocabsurgery.io.{FileIO, JsonIO}
import me.nsmr.vocabsurgery.unicode.UnicodeData

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
    val japanese = JapaneseKanjiReference.from(KanjiLoader.load(Path.of("kanji-data")))
    val plan = ModelOperations.plan(snapshot, PruningPolicy(japanese))
    assertEquals(plan.analysis.unregisteredIds.size, 267)
    assertEquals(plan.analysis.tokens.count(_.protection(ProtectionReason.BaseByte)), 256)
    assertEquals(plan.analysis.tokens.count(_.protection(ProtectionReason.PartialUtf8)), 1448)
    assert(plan.analysis.protectedConflicts.forall(token => plan.projection.compact(token.id).isDefined))
    assert(snapshot.tokenizer.addedTokens.forall(token => plan.projection.compact(token.id).isDefined))
    assertEquals(plan.savedPayloadBytes.longValue, 2304L * plan.removedRows)
    assertEquals(plan.shards.count(_.changed), 2)
    assertEquals(plan.config.hcursor.get[Int]("vocab_size"), Right(plan.projection.compactSize))
    val sourceEos = snapshot.config.original.hcursor.get[Vector[Int]]("eos_token_id").toOption.get
    assertEquals(plan.config.hcursor.get[Vector[Int]]("eos_token_id"), Right(sourceEos.map(id => plan.projection.requireCompact(OriginalTokenId(id)).intValue)))
    val targetsAfter = plan.shards.flatMap(_.tensors).filter(t => targets.exists(_.name == t.source.name))
    assertEquals(targetsAfter.size, 6)
    assert(targetsAfter.forall(_.result.shape.head == plan.projection.compactSize.toLong))
    val untouched = plan.shards.flatMap(_.tensors).filterNot(t => targets.exists(_.name == t.source.name))
    assert(untouched.forall(t => t.result.shape == t.source.shape && t.result.dtype == t.source.dtype && t.copies.map(_.source.length.longValue).sum == t.source.dataRange.length.longValue))
    assertEquals(TokenReferences.requiredRootIds(plan.config).map(_.intValue).forall(_ < plan.projection.compactSize), true)
    assertEquals(plan.config.hcursor.downField("quantization").focus, snapshot.config.original.hcursor.downField("quantization").focus)
    println(s"Planned K=${plan.projection.compactSize}, direct=${plan.analysis.cleanup.directDeleted.size}, indirect=${plan.analysis.cleanup.indirectDeleted.size}, saved=${plan.savedPayloadBytes.longValue} bytes without reading payloads")
    sys.props.get("vocabSurgery.tokenizersPython") match
      case Some(python) => checkReferenceTokenizer(python, snapshot, plan, japanese)
      case None => println("Reference tokenizers / NFC comparison not enabled; set vocabSurgery.tokenizersPython to opt in")
  }

  private def checkReferenceTokenizer(python: String, source: ModelSnapshot, plan: ModelUpdatePlan, japanese: JapaneseKanjiReference): Unit =
    val directory = Files.createTempDirectory("vocab-domain-oracle-")
    try
      val tokenizerPath = directory.resolve("tokenizer.json")
      JsonIO.writeNew(tokenizerPath, plan.tokenizer)
      val loaded = TokenizerLoader.load(tokenizerPath)
      assertEquals((loaded.vocabulary.map(_.id.intValue) ++ loaded.addedTokens.map(_.id.intValue)).toSet, (0 until plan.projection.compactSize).toSet)
      val nfc = Json.fromValues(japanese.sortedCharacters.map(cp => Json.arr(Json.fromString(cp.text), Json.fromString(UnicodeData.normalize(TokenSurface(cp.text)).text))))
      val nfcPath = directory.resolve("nfc.json")
      JsonIO.writeNew(nfcPath, nfc)
      val script = """
import json, sys, tokenizers
from tokenizers import Tokenizer
old = Tokenizer.from_file(sys.argv[1])
new = Tokenizer.from_file(sys.argv[2])
with open(sys.argv[3], encoding='utf-8') as f:
    pairs = json.load(f)
for raw, expected in pairs:
    assert old.normalizer.normalize_str(raw) == expected, (raw, expected)
    assert new.normalizer.normalize_str(raw) == expected, (raw, expected)
samples = ['日本語の説明を要約します。欄', 'def greet(x):\n    return x + 1\n',
    'val xs: List[Int] = List(1, 2)', 'const n: number = 42;',
    '<|fim_prefix|>x<|fim_suffix|>y<|fim_middle|>', '<tool_call>{"name":"f"}</tool_call>',
    '<|im_start|>user\nhello<|im_end|>', 'ع Ж 한 汉']
for text in samples:
    ids = new.encode(text, add_special_tokens=False).ids
    assert all(0 <= i < new.get_vocab_size() for i in ids)
    assert new.decode(ids, skip_special_tokens=False) == old.normalizer.normalize_str(text), text
assert new.get_vocab_size() == int(sys.argv[4])
print('tokenizers', tokenizers.__version__, ': NFC matches', len(pairs), 'reference characters; round trips', len(samples), 'samples')
"""
      val output = directory.resolve("oracle.log")
      val process = new ProcessBuilder(python, "-c", script, source.directory.resolve("tokenizer.json").toString,
        tokenizerPath.toString, nfcPath.toString, plan.projection.compactSize.toString).redirectErrorStream(true).redirectOutput(output.toFile).start()
      if !process.waitFor(30L, TimeUnit.SECONDS) then
        process.destroyForcibly()
        fail("reference tokenizer comparison exceeded 30 seconds")
      val log = FileIO.readUtf8(output)
      assertEquals(process.exitValue(), 0, log)
      println(log.trim)
    finally
      import scala.jdk.CollectionConverters.*
      Using.resource(Files.walk(directory)) { paths =>
        paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      }
