package me.nsmr.vocabsurgery.model

import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*
import scala.util.Using

class SafetensorsSuite extends munit.FunSuite:
  private def fixture[A](header: String, payload: Array[Byte])(f: Path => A): A =
    val path = Files.createTempFile("vocab-safetensors-test-", ".safetensors")
    try
      val bytes = header.getBytes(StandardCharsets.UTF_8)
      val buffer = ByteBuffer.allocate(8 + bytes.length + payload.length).order(ByteOrder.LITTLE_ENDIAN)
      buffer.putLong(bytes.length.toLong).put(bytes).put(payload)
      Files.write(path, buffer.array())
      f(path)
    finally Files.delete(path)

  test("header round trip preserves scalar, empty and quantized tensor metadata") {
    val tensors = Vector(
      TensorMetadata("weight", TensorDType.U32, Vector(2L, 1L), ByteRange(ByteOffset(0L), ByteCount(8L))),
      TensorMetadata("scale", TensorDType.BF16, Vector.empty, ByteRange(ByteOffset(8L), ByteCount(2L))),
      TensorMetadata("empty", TensorDType.I8, Vector(0L), ByteRange(ByteOffset(10L), ByteCount(0L)))
    )
    val path = Files.createTempFile("vocab-header-roundtrip-", ".safetensors")
    try
      val encoded = SafetensorsIO.encodeHeader(tensors, Map("format" -> "mlx"))
      Files.write(path, encoded ++ Array.fill[Byte](10)(0))
      val loaded = SafetensorsIO.read(path)
      assertEquals(loaded.tensors.sortBy(_.name), tensors.sortBy(_.name))
      assertEquals(loaded.metadata, Map("format" -> "mlx"))
      assertEquals(loaded.payloadStart.longValue, encoded.length.toLong)
      assertEquals(loaded.headerSha256, FileIO.sha256(path,
        Some(ByteRange(ByteOffset(0L), ByteCount(encoded.length.toLong)))))
    finally Files.delete(path)
  }

  test("invalid header, duplicate keys, shape / range mismatches and unsupported dtypes are rejected") {
    val invalid = Vector(
      """{"x":{"dtype":"U32","shape":[2],"data_offsets":[0,4]}}""",
      """{"x":{"dtype":"U32","shape":[-1],"data_offsets":[0,4]}}""",
      """{"x":{"dtype":"U32","shape":[1.0],"data_offsets":[0,4]}}""",
      """{"x":{"dtype":"U32","shape":[1],"data_offsets":[0,5]}}""",
      """{"x":{"dtype":"U32","shape":[1],"data_offsets":[0,4]},"x":{}}""",
      """{"__metadata__":{"number":1},"x":{"dtype":"U32","shape":[1],"data_offsets":[0,4]}}""",
      """{"x":{"dtype":"F8_E4M3","shape":[4],"data_offsets":[0,4]}}""",
      """ {"x":{"dtype":"U32","shape":[1],"data_offsets":[0,4]}}"""
    )
    invalid.foreach { header => fixture(header, Array.fill[Byte](4)(0)) { path =>
      val error = intercept[DataException](SafetensorsIO.read(path))
      assertEquals(error.source, path)
    }}
  }

  test("holes, overlaps and trailing unindexed bytes are rejected") {
    val headers = Vector(
      """{"x":{"dtype":"U8","shape":[2],"data_offsets":[1,3]}}""",
      """{"x":{"dtype":"U8","shape":[2],"data_offsets":[0,2]},"y":{"dtype":"U8","shape":[2],"data_offsets":[1,3]}}""",
      """{"x":{"dtype":"U8","shape":[2],"data_offsets":[0,2]}}"""
    )
    headers.foreach { header => fixture(header, Array.fill[Byte](3)(0)) { path =>
      intercept[DataException](SafetensorsIO.read(path))
    }}
  }

  test("oversized unsigned length prefixes and truncated headers fail before allocation") {
    val path = Files.createTempFile("vocab-bad-prefix-", ".safetensors")
    try
      Vector(-1L, Long.MaxValue, 100L, 0L).foreach { length =>
        Files.write(path, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(length).array())
        intercept[DataException](SafetensorsIO.read(path))
      }
    finally Files.delete(path)
  }

  test("a 40 GiB sparse checkpoint can be inspected within a 512 MiB test heap") {
    val payloadBytes = 40L * 1024 * 1024 * 1024
    val tensor = TensorMetadata("huge", TensorDType.U8, Vector(payloadBytes),
      ByteRange(ByteOffset(0L), ByteCount(payloadBytes)))
    val header = SafetensorsIO.encodeHeader(Vector(tensor))
    val path = Files.createTempFile("vocab-sparse-checkpoint-", ".safetensors")
    try
      Using.resource(FileChannel.open(path, StandardOpenOption.WRITE)) { channel =>
        channel.write(ByteBuffer.wrap(header))
        channel.write(ByteBuffer.wrap(Array[Byte](0)), header.length.toLong + payloadBytes - 1)
      }
      val file = SafetensorsIO.read(path)
      assertEquals(file.tensors.head.dataRange.length.longValue, payloadBytes)
      assertEquals(file.fileSize.longValue, header.length.toLong + payloadBytes)
    finally Files.delete(path)
  }
