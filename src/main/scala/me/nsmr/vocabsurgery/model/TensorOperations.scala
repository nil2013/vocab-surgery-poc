package me.nsmr.vocabsurgery.model

import io.circe.Json
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.tokenizer.VocabularyProjection

/** Both ranges are payload-relative, in the source and destination shard respectively. */
final case class CopySpan(source: ByteRange, destination: ByteRange):
  require(source.length == destination.length, "copy lengths must match")

final case class TensorProjection(source: TensorMetadata, result: TensorMetadata, copies: Vector[CopySpan])

object TensorOperations:
  /** Coalesce consecutive source rows. This describes copies; it does not read payload bytes. */
  def projectRows(source: TensorMetadata, projection: VocabularyProjection, destination: ByteOffset): TensorProjection =
    DomainChecks.check(source.shape.nonEmpty && source.shape.head == projection.originalSize.toLong,
      "row-count-mismatch", source.name, "axis 0 must match the original vocabulary")
    DomainChecks.check(source.expectedBytes == source.dataRange.length, "tensor-size-mismatch", source.name, "shape and range disagree")
    val rowBytes = if source.shape.tail.contains(0L) then 0L
      else source.shape.tail.foldLeft(source.dtype.byteWidth.toLong)(Math.multiplyExact)
    val length = Math.multiplyExact(rowBytes, projection.compactSize.toLong)
    val result = TensorMetadata(source.name, source.dtype, projection.compactSize.toLong +: source.shape.tail,
      ByteRange(destination, ByteCount(length)))
    val copies = Vector.newBuilder[CopySpan]
    val rows = projection.compactToOriginal.map(_.intValue)
    var first = 0
    while first < rows.size do
      var end = first + 1
      while end < rows.size && rows(end).toLong == rows(end - 1).toLong + 1L do end += 1
      val count = ByteCount(Math.multiplyExact(rowBytes, (end - first).toLong))
      val from = ByteOffset(Math.addExact(source.dataRange.start.longValue, Math.multiplyExact(rows(first).toLong, rowBytes)))
      val to = ByteOffset(Math.addExact(destination.longValue, Math.multiplyExact(first.toLong, rowBytes)))
      if count.longValue > 0 then copies += CopySpan(ByteRange(from, count), ByteRange(to, count))
      first = end
    TensorProjection(source, result, copies.result())

final case class QuantizedVocabularyModule(name: String, quantization: QuantizationSpec,
    weight: TensorMetadata, scales: TensorMetadata, biases: TensorMetadata):
  def tensors: Vector[TensorMetadata] = Vector(weight, scales, biases)
  def bytesPerRow: Long = tensors.map(t => t.shape.tail.foldLeft(t.dtype.byteWidth.toLong)(Math.multiplyExact)).foldLeft(0L)(Math.addExact)

object QuantizedVocabularyModule:
  def validate(name: String, config: ModelConfig, tensors: Map[String, TensorMetadata]): QuantizedVocabularyModule =
    val quant = config.quantization.overrides.getOrElse(name, config.quantization.default)
    DomainChecks.check(quant.mode == "affine" && Set(4, 8)(quant.bits) && quant.groupSize > 0,
      "unsupported-quantization", name, "expected affine 4/8-bit uint32 packing with a positive group size")
    val perWord = 32 / quant.bits
    DomainChecks.check(config.hiddenSize > 0 && config.hiddenSize % perWord == 0 && config.hiddenSize % quant.groupSize == 0,
      "invalid-quantized-dimensions", name, "hidden size must be divisible by packing and group sizes")
    def tensor(suffix: String, columns: Int): TensorMetadata =
      val full = s"$name.$suffix"
      val value = tensors.getOrElse(full, DomainChecks.fail("missing-companion-tensor", full, "required tensor absent"))
      DomainChecks.check(value.shape == Vector(config.vocabSize.toLong, columns.toLong), "unsupported-tensor-layout", full,
        "expected row-major rank-2 vocabulary tensor")
      DomainChecks.check(value.expectedBytes == value.dataRange.length, "tensor-size-mismatch", full, "shape and byte range disagree")
      value
    val weight = tensor("weight", config.hiddenSize / perWord)
    val scales = tensor("scales", config.hiddenSize / quant.groupSize)
    val biases = tensor("biases", config.hiddenSize / quant.groupSize)
    DomainChecks.check(weight.dtype == TensorDType.U32, "unsupported-packed-dtype", weight.name, "packed weight must use U32")
    DomainChecks.check(Set(TensorDType.F16, TensorDType.BF16, TensorDType.F32)(scales.dtype) && biases.dtype == scales.dtype,
      "unsupported-companion-dtype", name, "scales and biases must share a supported floating dtype")
    QuantizedVocabularyModule(name, quant, weight, scales, biases)

/** Header and byte-copy description. Unchanged shards can be copied as complete files later. */
final case class ShardProjection(source: SafetensorsFile, tensors: Vector[TensorProjection],
    header: Json, payloadBytes: ByteCount, changed: Boolean):
  def copies: Vector[CopySpan] = tensors.flatMap(_.copies)

object ShardProjection:
  def plan(source: SafetensorsFile, targets: Set[String], projection: VocabularyProjection): ShardProjection =
    DomainChecks.check(source.tensors.map(_.name).distinct.size == source.tensors.size,
      "duplicate-tensor", source.path.toString, "tensor names must be unique")
    val fields = DomainJson.fields(source.originalHeader, "header")
    DomainChecks.check(fields.keys.toSet - "__metadata__" == source.tensors.map(_.name).toSet,
      "inconsistent-header", source.path.toString, "JSON and typed tensor names disagree")
    DomainChecks.check(source.payloadStart.longValue == Math.addExact(8L, source.headerSize.longValue) &&
      source.fileSize.longValue >= source.payloadStart.longValue, "invalid-source-layout", source.path.toString, "invalid header / file sizes")
    var oldCursor = 0L
    var newCursor = 0L
    val plans = source.tensors.sortBy(t => (t.dataRange.start.longValue, if t.dataRange.length.longValue == 0 then 0 else 1, t.name)).map { tensor =>
      val empty = tensor.dataRange.length.longValue == 0
      DomainChecks.check(tensor.expectedBytes == tensor.dataRange.length &&
        (if empty then tensor.dataRange.start.longValue <= source.fileSize.longValue - source.payloadStart.longValue else tensor.dataRange.start.longValue == oldCursor),
        "invalid-source-layout", tensor.name, "payload must be contiguous and match tensor shapes")
      if !empty then oldCursor = tensor.dataRange.endExclusive.longValue
      val plan = if targets(tensor.name) then TensorOperations.projectRows(tensor, projection, ByteOffset(newCursor))
      else
        val result = tensor.copy(dataRange = ByteRange(ByteOffset(newCursor), tensor.dataRange.length))
        val copies = if tensor.dataRange.length.longValue == 0 then Vector.empty else Vector(CopySpan(tensor.dataRange, result.dataRange))
        TensorProjection(tensor, result, copies)
      newCursor = plan.result.dataRange.endExclusive.longValue
      plan
    }
    DomainChecks.check(oldCursor == source.fileSize.longValue - source.payloadStart.longValue,
      "invalid-source-layout", source.path.toString, "payload size disagrees with tensor ranges")
    val header = plans.foldLeft(fields) { (fields, plan) =>
      val original = DomainJson.fields(fields(plan.source.name).get, plan.source.name)
      DomainChecks.check(original("dtype").contains(Json.fromString(plan.source.dtype.toString)) &&
        original("shape").contains(Json.fromValues(plan.source.shape.map(Json.fromLong))) &&
        original("data_offsets").contains(Json.arr(Json.fromLong(plan.source.dataRange.start.longValue), Json.fromLong(plan.source.dataRange.endExclusive.longValue))),
        "inconsistent-header", plan.source.name, "JSON and typed tensor metadata disagree")
      fields.add(plan.source.name, Json.fromJsonObject(original
        .add("shape", Json.fromValues(plan.result.shape.map(Json.fromLong)))
        .add("data_offsets", Json.arr(Json.fromLong(plan.result.dataRange.start.longValue), Json.fromLong(plan.result.dataRange.endExclusive.longValue)))))
    }
    ShardProjection(source, plans, Json.fromJsonObject(header), ByteCount(newCursor), plans.exists(p => p.source != p.result))
