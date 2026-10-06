package me.nsmr.vocabsurgery.model

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*

/** Byte-addressable safetensors dtypes supported by the first infrastructure stage. */
enum TensorDType(val byteWidth: Int):
  case BOOL extends TensorDType(1)
  case I8 extends TensorDType(1)
  case U8 extends TensorDType(1)
  case I16 extends TensorDType(2)
  case U16 extends TensorDType(2)
  case F16 extends TensorDType(2)
  case BF16 extends TensorDType(2)
  case I32 extends TensorDType(4)
  case U32 extends TensorDType(4)
  case F32 extends TensorDType(4)
  case I64 extends TensorDType(8)
  case U64 extends TensorDType(8)
  case F64 extends TensorDType(8)

/** Offsets are relative to the payload start, never absolute positions in the file. */
final case class TensorMetadata(
    name: String,
    dtype: TensorDType,
    shape: Vector[Long],
    dataRange: ByteRange
):
  require(shape.forall(_ >= 0), "negative tensor dimension")
  val elementCount: Long =
    if shape.contains(0L) then 0L else shape.foldLeft(1L)(Math.multiplyExact)
  val expectedBytes: ByteCount = ByteCount(Math.multiplyExact(elementCount, dtype.byteWidth.toLong))

/** Metadata only: no payload bytes or open channels are retained. */
final case class SafetensorsFile(
    path: Path,
    fileSize: ByteCount,
    headerSize: ByteCount,
    payloadStart: ByteOffset,
    tensors: Vector[TensorMetadata],
    metadata: Map[String, String],
    originalHeader: Json,
    headerSha256: String
)
