package me.nsmr.vocabsurgery.io

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Path, StandardOpenOption}
import scala.util.Using
import me.nsmr.vocabsurgery.core.*

class InfrastructureSuite extends munit.FunSuite:
  private def temporary[A](f: Path => A): A =
    val dir = Files.createTempDirectory("vocab-surgery-test-")
    try f(dir)
    finally
      Using.resource(Files.walk(dir)) { paths =>
        import scala.jdk.CollectionConverters.*
        paths.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete)
      }

  test("opaque scalar and range constructors reject invalid values") {
    assertEquals(CodePoint.fromString("𠀋").map(_.intValue), Right(0x2000b))
    assert(CodePoint.fromString("漢字").isLeft)
    assert(CodePoint.fromString("\ud800").isLeft)
    intercept[IllegalArgumentException](CodePoint(0xdfff))
    intercept[IllegalArgumentException](OriginalTokenId(-1))
    intercept[IllegalArgumentException](ByteOffset(-1L))
    intercept[ArithmeticException](ByteRange(ByteOffset(Long.MaxValue), ByteCount(1L)))
  }

  test("JSON retains large integers, unknown fields and rejects ambiguous documents") {
    temporary { dir =>
      val path = dir.resolve("metadata.json")
      val json = JsonIO.parse("""{"offset":9007199254740993,"unknown":{"x":true}}""", path)
      JsonIO.writeNew(path, json)
      assertEquals(JsonIO.read(path), json)
      assertEquals(json.hcursor.get[Long]("offset"), Right(9007199254740993L))
      intercept[DataException](JsonIO.parse("""{"x":1,"x":2}""", path))
      intercept[DataException](JsonIO.parse("{} {}", path))
      intercept[DataException](JsonIO.read(path, 1L))
      intercept[DataException](JsonIO.writeNew(path, json))
      assertEquals(JsonIO.read(path), json)
      val error = intercept[DataException](JsonIO.read(dir.resolve("missing.json")))
      assertEquals(error.kind, ErrorKind.MissingFile)
    }
  }

  test("CSV supports BOM, Japanese headers, quotes and embedded newlines") {
    temporary { dir =>
      val path = dir.resolve("quoted.csv")
      FileIO.writeNewUtf8(path, "\ufeff漢字,注記\r\n𠀋,\"one,two\nthree\"\r\n")
      val csv = CsvIO.read(path, Set("漢字", "注記"))
      assertEquals(csv.rows.head.values("漢字"), "𠀋")
      assertEquals(csv.rows.head.values("注記"), "one,two\nthree")
      intercept[DataException](CsvIO.read(path, Set("missing")))
      val bad = dir.resolve("bad.csv")
      FileIO.writeNewUtf8(bad, "漢字,注記\n字\n")
      intercept[DataException](CsvIO.read(bad, Set("漢字")))
      val duplicate = dir.resolve("duplicate.csv")
      FileIO.writeNewUtf8(duplicate, "漢字,漢字\n字,字\n")
      intercept[DataException](CsvIO.read(duplicate, Set("漢字")))
    }
  }

  test("malformed UTF-8 is rejected without replacement characters") {
    temporary { dir =>
      val path = dir.resolve("bad.txt")
      Files.write(path, Array(0xc3.toByte, 0x28.toByte))
      intercept[DataException](FileIO.readUtf8(path))
    }
  }

  test("range copies use exact bytes, bounded buffers and offsets beyond 2 GiB") {
    temporary { dir =>
      val source = dir.resolve("sparse.bin")
      val offset = 3L * 1024 * 1024 * 1024
      val data = Array.tabulate[Byte](257)(i => (i & 0xff).toByte)
      Using.resource(FileChannel.open(source, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { channel =>
        channel.write(ByteBuffer.wrap(data), offset)
      }
      val range = ByteRange(ByteOffset(offset), ByteCount(data.length.toLong))
      val destination = dir.resolve("range.bin")
      FileIO.copyRange(source, destination, range, bufferBytes = 7)
      assertEquals(Files.readAllBytes(destination).toVector, data.toVector)
      assertEquals(FileIO.sha256(source, Some(range)), FileIO.sha256(destination))
      intercept[DataException](FileIO.copyRange(source, destination, range))
      val outside = ByteRange(ByteOffset(offset), ByteCount(data.length.toLong + 1))
      val absent = dir.resolve("absent.bin")
      intercept[DataException](FileIO.copyRange(source, absent, outside))
      assert(!Files.exists(absent))
    }
  }
