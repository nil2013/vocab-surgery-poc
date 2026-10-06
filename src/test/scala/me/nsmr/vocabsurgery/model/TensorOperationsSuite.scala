package me.nsmr.vocabsurgery.model

import org.scalacheck.{Gen, Prop}
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.tokenizer.VocabularyProjection

class TensorOperationsSuite extends munit.ScalaCheckSuite:
  test("row extraction coalesces consecutive rows and copies exact packed bytes") {
    val source = TensorMetadata("weight", TensorDType.U32, Vector(8L, 2L), ByteRange(ByteOffset(5L), ByteCount(64L)))
    val projection = VocabularyProjection.fromSurvivors(8, Vector(1, 2, 5, 7).map(OriginalTokenId.apply))
    val plan = TensorOperations.projectRows(source, projection, ByteOffset(3L))
    assertEquals(plan.result.shape, Vector(4L, 2L))
    assertEquals(plan.copies.map(_.source.length.longValue), Vector(16L, 8L, 8L))
    val input = (0 until 69).map(_.toByte).toArray
    val output = Array.fill[Byte](35)(0)
    plan.copies.foreach(span => Array.copy(input, span.source.start.longValue.toInt, output, span.destination.start.longValue.toInt, span.source.length.longValue.toInt))
    val expected = Vector(1, 2, 5, 7).flatMap(i => input.slice(5 + i * 8, 5 + (i + 1) * 8))
    assertEquals(output.drop(3).toVector, expected)
  }

  test("projection supports multidimensional / empty rows and large payload-relative offsets") {
    val projection = VocabularyProjection.fromSurvivors(3, Vector(OriginalTokenId(2)))
    val large = TensorMetadata("large", TensorDType.U8, Vector(3L, 2L, 4L), ByteRange(ByteOffset(4L * 1024 * 1024 * 1024), ByteCount(24L)))
    assertEquals(TensorOperations.projectRows(large, projection, ByteOffset(0)).copies.head.source.start.longValue, 4294967312L)
    val empty = TensorMetadata("empty", TensorDType.U8, Vector(3L, 0L), ByteRange(ByteOffset(0), ByteCount(0)))
    assert(TensorOperations.projectRows(empty, projection, ByteOffset(0)).copies.isEmpty)
    val hugeEmpty = empty.copy(shape = Vector(3L, Long.MaxValue, Long.MaxValue, 0L))
    assert(TensorOperations.projectRows(hugeEmpty, projection, ByteOffset(0)).copies.isEmpty)
    intercept[DomainException](TensorOperations.projectRows(large.copy(shape = Vector(2L, 12L)), projection, ByteOffset(0)))
    intercept[DomainException](TensorOperations.projectRows(large.copy(dataRange = ByteRange(ByteOffset(0), ByteCount(23))), projection, ByteOffset(0)))
    intercept[ArithmeticException](TensorOperations.projectRows(large, projection, ByteOffset(Long.MaxValue)))
  }

  test("quantized module validation rejects missing companions and unsupported dimensions, dtype or mode") {
    val snapshot = DomainFixtures.snapshot()
    val tensors = snapshot.shards.head.tensors.map(t => t.name -> t).toMap
    val module = "model.embed_tokens"
    assertEquals(QuantizedVocabularyModule.validate(module, snapshot.config, tensors).bytesPerRow, 12L)
    intercept[DomainException](QuantizedVocabularyModule.validate(module, snapshot.config, tensors - s"$module.biases"))
    Vector(QuantizationSpec(3, 4, "affine"), QuantizationSpec(4, 0, "affine"), QuantizationSpec(4, 4, "mxfp4")).foreach { spec =>
      intercept[DomainException](QuantizedVocabularyModule.validate(module, snapshot.config.copy(quantization = QuantizationConfig(spec, Map.empty)), tensors))
    }
    intercept[DomainException](QuantizedVocabularyModule.validate(module, snapshot.config.copy(hiddenSize = 7), tensors))
    val weight = tensors(s"$module.weight")
    intercept[DomainException](QuantizedVocabularyModule.validate(module, snapshot.config, tensors.updated(weight.name, weight.copy(dtype = TensorDType.I32))))
    val scales = tensors(s"$module.scales")
    intercept[DomainException](QuantizedVocabularyModule.validate(module, snapshot.config, tensors.updated(scales.name, scales.copy(shape = Vector(snapshot.config.vocabSize.toLong, 1L)))))
    intercept[DomainException](QuantizedVocabularyModule.validate(module, snapshot.config, tensors.updated(scales.name, scales.copy(dtype = TensorDType.I16))))
  }

  property("copy spans implement exactly the projected row order for arbitrary survivor sets") {
    val cases = Gen.choose(1, 100).flatMap(rows => Gen.nonEmptyListOf(Gen.choose(0, rows - 1)).map(ids => (rows, ids.distinct.sorted)))
    Prop.forAll(cases) { (rows, kept) =>
      val source = TensorMetadata("matrix", TensorDType.U8, Vector(rows.toLong, 3L), ByteRange(ByteOffset(0), ByteCount(rows * 3L)))
      val plan = TensorOperations.projectRows(source, VocabularyProjection.fromSurvivors(rows, kept.map(OriginalTokenId.apply)), ByteOffset(0))
      val sourceBytes = (0 until rows * 3).map(_.toByte).toArray
      val result = new Array[Byte](kept.size * 3)
      plan.copies.foreach(span => Array.copy(sourceBytes, span.source.start.longValue.toInt, result, span.destination.start.longValue.toInt, span.source.length.longValue.toInt))
      result.toVector == kept.flatMap(i => sourceBytes.slice(i * 3, (i + 1) * 3)).toVector &&
        plan.copies.map(_.destination.length.longValue).sum == plan.result.expectedBytes.longValue
    }
  }
