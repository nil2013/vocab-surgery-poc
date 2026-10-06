package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.io.*

/** Load ByteLevel BPE structure without decoding surfaces or deciding pruning policy. */
object TokenizerLoader:
  def load(path: Path): TokenizerData = Diagnostics.guard(path, "tokenizer") {
    val root = JsonIO.read(path)
    val model = JsonFields.field(root, "model", path, "tokenizer")
    val modelType = JsonFields.text(JsonFields.field(model, "type", path, "model"), path, "model.type")
    if modelType != "BPE" then
      throw DataException(ErrorKind.UnsupportedFormat, path, "model.type", s"expected BPE, found $modelType")
    val vocabulary = JsonFields.entries(JsonFields.field(model, "vocab", path, "model"), path, "model.vocab")
      .map { (encoded, id) =>
        Diagnostics.check(encoded.nonEmpty, path, "model.vocab", "empty token key")
        LexicalToken(encoded, ConfigLoader.tokenId(id, path, s"model.vocab.$encoded"))
      }
    Diagnostics.check(vocabulary.nonEmpty, path, "model.vocab", "empty vocabulary")
    Diagnostics.check(vocabulary.map(_.id).distinct.size == vocabulary.size, path, "model.vocab", "duplicate lexical token IDs")
    val vocab = vocabulary.map(token => token.encoded -> token.id).toMap
    val byId = vocabulary.map(token => token.id -> token.encoded).toMap
    val merges = JsonFields.array(JsonFields.field(model, "merges", path, "model"), path, "model.merges")
      .zipWithIndex.map { (entry, index) =>
        val at = s"model.merges[$index]"
        val pair = entry.asString match
          case Some(text) => text.split(" ", -1).toVector
          case None => JsonFields.array(entry, path, at).map(JsonFields.text(_, path, at))
        Diagnostics.check(pair.size == 2 && pair.forall(_.nonEmpty), path, at, "expected two nonempty merge components")
        Diagnostics.check(vocab.contains(pair(0)) && vocab.contains(pair(1)) && vocab.contains(pair(0) + pair(1)),
          path, at, "merge components or result missing from vocabulary")
        BpeMerge(pair(0), pair(1))
      }
    Diagnostics.check(merges.distinct.size == merges.size, path, "model.merges", "duplicate merge rules")
    val added = JsonFields.obj(root, path, "tokenizer")("added_tokens").map { value =>
      JsonFields.array(value, path, "added_tokens").zipWithIndex.map { (entry, index) =>
        val at = s"added_tokens[$index]"
        val id = ConfigLoader.tokenId(JsonFields.field(entry, "id", path, at), path, s"$at.id")
        val content = JsonFields.text(JsonFields.field(entry, "content", path, at), path, s"$at.content")
        Diagnostics.check(content.nonEmpty, path, s"$at.content", "empty added token")
        Diagnostics.check(byId.get(id).forall(_ == content) && vocab.get(content).forall(_ == id),
          path, at, "added token conflicts with lexical vocabulary")
        def flag(name: String): Boolean = JsonFields.bool(JsonFields.field(entry, name, path, at), path, s"$at.$name")
        AddedToken(id, content, flag("special"), flag("single_word"), flag("lstrip"), flag("rstrip"), flag("normalized"), entry)
      }
    }.getOrElse(Vector.empty)
    Diagnostics.check(added.map(_.id).distinct.size == added.size && added.map(_.content).distinct.size == added.size,
      path, "added_tokens", "duplicate added token IDs or contents")
    TokenizerData(vocabulary, merges, added, root)
  }
