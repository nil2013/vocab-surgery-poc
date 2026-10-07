package me.nsmr.vocabsurgery.runtime

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.{DataException, FileIO}
import me.nsmr.vocabsurgery.processing.ProcessingOptions

class DraftVocabularySuite extends munit.FunSuite:
  private def withDirectory[A](body: Path => A): A =
    val directory = Files.createTempDirectory("vocab-draft-test-")
    try body(directory)
    finally Using.resource(Files.walk(directory))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))

  /** Ids 0..7 are lexical ("Ġthe" is 2), 8 is <|im_end|>. Texts tokenize to their characters' ids; "Ġthe" counts as 2. */
  private val tokenizerJson =
    """{"model":{"type":"BPE","vocab":{"a":0,"b":1,"Ġthe":2,"c":3,"d":4,"e":5,"f":6,"g":7},"merges":[]},
      |"added_tokens":[{"id":8,"content":"<|im_end|>","special":true,"single_word":false,"lstrip":false,"rstrip":false,"normalized":false}]}""".stripMargin

  private object Characters extends ReferenceTokenization:
    private val ids = Map("a" -> 0, "b" -> 1, "c" -> 3, "d" -> 4, "e" -> 5, "f" -> 6, "g" -> 7)
    var encoded = Vector.empty[String]
    def counts(texts: Vector[String]): Map[Int, Long] = texts.flatMap(_.map(c => ids(c.toString))).groupBy(identity).view.mapValues(_.size.toLong).toMap
    def encode(texts: Vector[String]): Vector[Vector[Int]] =
      encoded ++= texts
      texts.map(text => if text == " x" then Vector(6) else text.map(c => ids(c.toString)).toVector)

  test("frequent tokens replace never-generated base rows from the highest id, keeping the row count") {
    val counts = Map(0 -> 5L, 3 -> 4L, 4 -> 4L, 5 -> 1L, 7 -> 2L)
    val result = DraftVocabulary.swap(9, counts, Vector(0, 1, 2, 6, 8), _ => true, Set(8), 2)
    // 3, 4 (count 4, by id) and 7 enter; unused 6, 2 and 1 leave, highest first; protected 8 stays.
    assertEquals(result, DraftSwap(Vector(0, 3, 4, 7, 8), Vector(3, 4, 7), Vector(1, 2, 6)))
    val limited = DraftVocabulary.swap(9, counts, Vector(0, 1, 6, 8), _ => true, Set(8), 2)
    assertEquals(limited, DraftSwap(Vector(0, 3, 4, 8), Vector(3, 4), Vector(1, 6)))
  }

  test("forced additions enter first, forced removals free rows and only kept tokens count") {
    val counts = Map(0 -> 5L, 3 -> 4L, 4 -> 3L)
    val forced = DraftVocabulary.swap(9, counts, Vector(0, 1, 6, 8), _ => true, Set(8), 2, add = Set(5), remove = Set(0))
    // 0's row is freed, so three enter (the forced 5 first) while two unused rows leave.
    assertEquals(forced, DraftSwap(Vector(3, 4, 5, 8), Vector(3, 4, 5), Vector(1, 6)))
    val kept = DraftVocabulary.swap(9, counts, Vector(0, 1, 6, 8), id => id != 3 && id != 1, Set(8), 2)
    assertEquals(kept, DraftSwap(Vector(0, 4, 8), Vector(4), Vector(6)))
    intercept[IllegalArgumentException](DraftVocabulary.swap(9, counts, Vector(0, 8), _ => true, Set(8), 2, remove = Set(8)))
    intercept[IllegalArgumentException](DraftVocabulary.swap(9, counts, Vector(0, 8), _ => true, Set(8), 2, add = Set(5)))
  }

  test("profiles take defaults, names and reject unknown keys or rules") {
    withDirectory { root =>
      val path = root.resolve("p.json")
      Files.writeString(path, """{"force": {"add": [" x", 3], "remove": ["Ġthe"]}, "protect": {"addedTokens": false}}""")
      assertEquals(DraftVocabularyProfile.load(path), DraftVocabularyProfile(None, 2, false, Vector.empty,
        Vector(TokenName.Text(" x"), TokenName.Id(3)), Vector(TokenName.Text("Ġthe"))))
      Files.writeString(root.resolve("bad.json"), """{"select": {"minCount": 2, "maxCount": 9}}""")
      intercept[DataException](DraftVocabularyProfile.load(root.resolve("bad.json")))
      Files.writeString(root.resolve("order.json"), """{"replace": {"order": "lowest-id"}}""")
      intercept[DataException](DraftVocabularyProfile.load(root.resolve("order.json")))
    }
  }

  test("a rebuild reads the neutral corpus, resolves names and writes a new sorted list") {
    withDirectory { root =>
      val source = Files.createDirectories(root.resolve("source"))
      Files.writeString(source.resolve("tokenizer.json"), tokenizerJson)
      val builtin = root.resolve("builtin.txt")
      Files.writeString(builtin, "8\n0\n1\n2\n6\n0\n")
      val runtime = new DraftList { def builtinDraftList: Path = builtin }
      val corpus = root.resolve("corpus.jsonl")
      Files.writeString(corpus, "{\"text\": \"cdcda\"}\n\n{\"text\": \"cdcda\"}\n{\"text\": \"aag\"}\n{\"text\": \"  \"}\n")
      val profile = DraftVocabularyProfile(None, 2, true, Vector(TokenName.Text("Ġthe")), Vector(TokenName.Text(" x")), Vector.empty)
      val options = ProcessingOptions(outputRoot = root.resolve("outputs"))
      val report = DraftVocabulary.rebuild(profile, root.resolve("p.json"), Vector(corpus), source, runtime, None, Characters,
        root.resolve("outputs/draft.txt"), options).hcursor
      // 6 is forced but already listed; Ġthe (2) and <|im_end|> (8) are protected, so only 1 can leave for c.
      assertEquals(FileIO.readUtf8(root.resolve("outputs/draft.txt")), "0\n2\n3\n6\n8\n")
      assertEquals(Characters.encoded, Vector(" x"))
      assertEquals(report.get[Int]("texts"), Right(2))
      assertEquals(report.get[Int]("rows"), Right(5))
      assertEquals(report.get[Vector[String]]("added"), Right(Vector("c")))
      assertEquals(report.get[Vector[Int]]("droppedHighestIds"), Right(Vector(1)))
      assertEquals(report.get[Int]("protectedInBase"), Right(2))
      intercept[DataException](DraftVocabulary.rebuild(profile, root.resolve("p.json"), Vector(corpus), source, runtime, None, Characters,
        root.resolve("outputs/draft.txt"), options))
      Files.writeString(root.resolve("bad.jsonl"), "{\"prompt\": \"a\"}\n")
      intercept[DataException](DraftVocabulary.corpus(Vector(root.resolve("bad.jsonl"))))
    }
  }

  test("the clone's built-in list is its qwen4_exp CUDA draft list") {
    assertEquals(TensorFoldClone(Path.of("clone")).builtinDraftList,
      Path.of("clone").toAbsolutePath.normalize.resolve("src/tensorfold/families/qwen4_exp/cuda/draft_vocab.txt"))
  }
