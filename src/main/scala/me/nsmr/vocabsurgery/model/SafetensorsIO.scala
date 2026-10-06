package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.{Path, StandardOpenOption}
import scala.util.Using
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*

/** Read only the length prefix and bounded header. Validate payload ranges using file size. */
object SafetensorsIO:
  val DefaultHeaderLimit: Int = 16 * 1024 * 1024

  def read(path: Path, maxHeaderBytes: Int = DefaultHeaderLimit): SafetensorsFile =
    Diagnostics.guard(path, "safetensors header") {
      require(maxHeaderBytes > 0, "header limit must be positive")
      val (size, headerLength, payloadStart, header) =
        Using.resource(FileChannel.open(path, StandardOpenOption.READ)) { channel =>
          val size = channel.size()
          Diagnostics.check(size >= 8L, path, "length prefix", "file is shorter than 8 bytes")
          val prefix = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
          FileIO.readFully(channel, prefix, 0L, path)
          prefix.flip()
          val headerLength = prefix.getLong()
          Diagnostics.check(headerLength > 0 && headerLength <= maxHeaderBytes.toLong, path, "header length",
            s"header length must be in 1..$maxHeaderBytes and fit a signed 64-bit value")
          val payloadStart = Math.addExact(8L, headerLength)
          Diagnostics.check(payloadStart <= size, path, "header length", "header extends beyond file")
          val bytes = ByteBuffer.allocate(headerLength.toInt)
          FileIO.readFully(channel, bytes, 8L, path)
          Diagnostics.check(bytes.array()(0) == '{'.toByte, path, "header", "header must start with '{'")
          val json = JsonIO.parse(FileIO.decodeUtf8(bytes.array(), path, "header UTF-8"), path)
          (size, headerLength, payloadStart, json)
        }

      val fields = JsonFields.entries(header, path, "header")
      val metadata = fields.find(_._1 == "__metadata__").map { (_, value) =>
        JsonFields.entries(value, path, "__metadata__").map { (key, item) =>
          key -> JsonFields.text(item, path, s"__metadata__.$key")
        }.toMap
      }.getOrElse(Map.empty)
      val tensors = fields.filterNot(_._1 == "__metadata__").map { (name, value) =>
        Diagnostics.guard(path, s"tensor $name") {
          val dtypeName = JsonFields.text(JsonFields.field(value, "dtype", path, name), path, s"$name.dtype")
          val dtype = TensorDType.values.find(_.toString == dtypeName).getOrElse {
            throw DataException(ErrorKind.UnsupportedFormat, path, s"$name.dtype", s"unsupported dtype: $dtypeName")
          }
          val shape = JsonFields.array(JsonFields.field(value, "shape", path, name), path, s"$name.shape")
            .zipWithIndex.map { (dimension, index) => JsonFields.long(dimension, path, s"$name.shape[$index]") }
          val offsets = JsonFields.array(JsonFields.field(value, "data_offsets", path, name), path, s"$name.data_offsets")
          Diagnostics.check(offsets.size == 2, path, s"$name.data_offsets", "expected exactly two offsets")
          val begin = JsonFields.long(offsets(0), path, s"$name.data_offsets[0]")
          val end = JsonFields.long(offsets(1), path, s"$name.data_offsets[1]")
          Diagnostics.check(begin >= 0 && end >= begin && end <= size - payloadStart,
            path, s"$name.data_offsets", "invalid or out-of-file payload range")
          val tensor = TensorMetadata(name, dtype, shape, ByteRange(ByteOffset(begin), ByteCount(end - begin)))
          Diagnostics.check(tensor.expectedBytes == tensor.dataRange.length, path, name, "shape / dtype byte count does not match offsets")
          tensor
        }
      }
      var cursor = 0L
      tensors.filter(_.dataRange.length.longValue > 0).sortBy(_.dataRange.start.longValue).foreach { tensor =>
        Diagnostics.check(tensor.dataRange.start.longValue == cursor, path, tensor.name, "overlapping ranges or a hole in payload")
        cursor = tensor.dataRange.endExclusive.longValue
      }
      Diagnostics.check(cursor == size - payloadStart, path, "payload", "payload has trailing unindexed bytes")
      val headerRange = ByteRange(ByteOffset(0L), ByteCount(payloadStart))
      SafetensorsFile(path, ByteCount(size), ByteCount(headerLength), ByteOffset(payloadStart),
        tensors, metadata, header, FileIO.sha256(path, Some(headerRange)))
    }

  /** Encode an 8-byte prefix and deterministically ordered, space-padded header, without payload. */
  def encodeHeader(tensors: Vector[TensorMetadata], metadata: Map[String, String] = Map.empty): Array[Byte] =
    require(tensors.map(_.name).distinct.size == tensors.size, "duplicate tensor names")
    require(tensors.forall(_.name != "__metadata__"), "reserved tensor name")
    val entries = tensors.sortBy(_.name).map { tensor =>
      require(tensor.expectedBytes == tensor.dataRange.length, "shape / range mismatch")
      tensor.name -> Json.obj(
        "dtype" -> Json.fromString(tensor.dtype.toString),
        "shape" -> Json.fromValues(tensor.shape.map(Json.fromLong)),
        "data_offsets" -> Json.arr(Json.fromLong(tensor.dataRange.start.longValue),
          Json.fromLong(tensor.dataRange.endExclusive.longValue))
      )
    }
    val metadataEntry = if metadata.isEmpty then Vector.empty else Vector(
      "__metadata__" -> Json.obj(metadata.toVector.sortBy(_._1).map((key, value) => key -> Json.fromString(value))*))
    val jsonBytes = Json.obj((metadataEntry ++ entries)*).noSpaces.getBytes(StandardCharsets.UTF_8)
    val paddedLength = Math.addExact(jsonBytes.length, (8 - jsonBytes.length % 8) % 8)
    require(paddedLength <= DefaultHeaderLimit, "encoded header exceeds header limit")
    val result = ByteBuffer.allocate(Math.addExact(8, paddedLength)).order(ByteOrder.LITTLE_ENDIAN)
    result.putLong(paddedLength.toLong)
    result.put(jsonBytes)
    while result.hasRemaining do result.put(' '.toByte)
    result.array()
