package me.nsmr.vocabsurgery.tokenizer

import io.circe.Json
import io.circe.jawn.JawnParser
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.model.{DomainJson, OutputFile, TokenizerData}

/** How a BPE tokenizer's vocabulary keys stand for text. Classification and merge cleanup go through this,
  * so a tokenizer with another key encoding needs its own scheme rather than changes to them.
  */
trait TokenizerScheme:
  def name: String
  /** Reject tokenizer structures that this scheme's decoding and the merge cleanup do not model. */
  def validate(tokenizer: TokenizerData): Unit
  /** The bytes a vocabulary key stands for and, when they are complete UTF-8, their surface. */
  def decode(encoded: String): DecodedToken
  /** Keys any input can be split into. They root the merge graph and are never pruned. */
  def baseKeys: Vector[String]
  /** Files besides `tokenizer.json` that restate its vocabulary; the source's copies are read when present. */
  def companionNames: Set[String] = Set.empty
  /** Companions matching the rewritten tokenizer, after checking that the source copies match the source tokenizer. */
  def companions(source: TokenizerData, sourceFiles: Map[String, String], rewritten: Json): Vector[OutputFile] = Vector.empty

/** GPT-2 style ByteLevel BPE with plain concatenating merges and an NFC normalizer. */
object ByteLevelBpe extends TokenizerScheme:
  val name = "bytelevel-bpe"
  def decode(encoded: String): DecodedToken = ByteLevel.decode(encoded)
  def baseKeys: Vector[String] = ByteLevel.alphabet
  /** The slow Hugging Face tokenizer files; loaders may read them instead of `tokenizer.json`. */
  override val companionNames: Set[String] = Set("vocab.json", "merges.txt")

  override def companions(source: TokenizerData, sourceFiles: Map[String, String], rewritten: Json): Vector[OutputFile] =
    DomainChecks.check(sourceFiles.keySet.subsetOf(companionNames), "unknown-companion", "tokenizer", sourceFiles.keySet.mkString(", "))
    val model = DomainJson.required(rewritten, "model", "tokenizer")
    val vocab = sourceFiles.get("vocab.json").map { text =>
      val parsed = JawnParser(allowDuplicateKeys = false).parse(text).fold(e => DomainChecks.fail("invalid-companion", "vocab.json", e.message), identity)
      val entries = DomainJson.fields(parsed, "vocab.json").toVector.map((key, id) => key -> DomainJson.integer(id, s"vocab.json.$key")).toMap
      DomainChecks.check(entries == source.vocabulary.map(t => t.encoded -> t.id.intValue).toMap, "stale-companion", "vocab.json",
        "source vocab.json differs from tokenizer.json")
      OutputFile.JsonFile("vocab.json", DomainJson.required(model, "vocab", "model"))
    }
    val merges = sourceFiles.get("merges.txt").map { text =>
      val lines = text.split("\n", -1).toVector
      val (header, body) = lines.span(_.startsWith("#version"))
      val ending = if body.lastOption.contains("") then Vector("") else Vector.empty
      DomainChecks.check(body.dropRight(ending.size) == source.merges.map(m => s"${m.left} ${m.right}"), "stale-companion", "merges.txt",
        "source merges.txt differs from tokenizer.json")
      val kept = DomainJson.array(DomainJson.required(model, "merges", "model"), "merges").map { merge =>
        merge.asArray.map(_.map(DomainJson.text(_, "merge")).mkString(" ")).getOrElse(DomainJson.text(merge, "merge"))
      }
      OutputFile.TextFile("merges.txt", (header ++ kept ++ ending).mkString("\n"))
    }
    vocab.toVector ++ merges.toVector

  def validate(tokenizer: TokenizerData): Unit =
    val root = DomainJson.fields(tokenizer.original, "tokenizer")
    val model = DomainJson.fields(DomainJson.required(tokenizer.original, "model", "tokenizer"), "model")
    DomainChecks.check(model("type").contains(Json.fromString("BPE")), "unsupported-tokenizer", "model.type", "expected BPE")
    Vector("continuing_subword_prefix", "end_of_word_suffix").foreach { field =>
      DomainChecks.check(model(field).forall(v => v.isNull || v == Json.fromString("")), "unsupported-tokenizer", field, "affixed merges are unsupported")
    }
    Vector("byte_fallback", "ignore_merges").foreach { field =>
      DomainChecks.check(model(field).forall(_ == Json.False), "unsupported-tokenizer", field, "must be false")
    }
    val normalizer = root("normalizer").getOrElse(Json.Null)
    DomainChecks.check(normalizer.hcursor.get[String]("type").contains("NFC"), "unsupported-normalizer", "normalizer", "expected NFC")
    def containsByteLevel(value: Json): Boolean =
      val fields = DomainJson.fields(value, "pre_tokenizer")
      fields("type").flatMap(_.asString) match
        case Some("ByteLevel") => true
        case Some("Sequence") => DomainJson.array(DomainJson.required(value, "pretokenizers", "pre_tokenizer"), "pretokenizers").exists(containsByteLevel)
        case _ => false
    DomainChecks.check(root("pre_tokenizer").exists(containsByteLevel), "unsupported-tokenizer", "pre_tokenizer", "expected ByteLevel")
    root("decoder").foreach(value => DomainChecks.check(value.hcursor.get[String]("type").contains("ByteLevel"),
      "unsupported-tokenizer", "decoder", "expected ByteLevel"))
