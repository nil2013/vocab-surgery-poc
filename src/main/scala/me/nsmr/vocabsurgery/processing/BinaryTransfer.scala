package me.nsmr.vocabsurgery.processing

import com.sun.jna.{NativeLibrary, Platform}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.security.MessageDigest
import scala.util.Using
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.model.*

trait FileCloner:
  /** True only when a new independent copy-on-write file was created. False permits fallback. */
  def cloneNew(source: Path, destination: Path): Boolean

object FileCloner:
  val disabled: FileCloner = (_, _) => false
  val platform: FileCloner = (source, destination) =>
    if !Platform.isMac then false
    else
      Diagnostics.check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), destination, "clone", "destination already exists")
      val result = NativeLibrary.getInstance("System").getFunction("clonefile")
        .invokeInt(Array[AnyRef](source.toRealPath().toString, destination.toString, Int.box(0)))
      if result == 0 then true
      else
        Diagnostics.check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), destination, "clone", "clone failed after creating destination")
        false

final case class TensorVerification(name: String, comparedBytes: Long, sourceSha256: String, outputSha256: String):
  def json: io.circe.Json = io.circe.Json.obj("name" -> io.circe.Json.fromString(name), "bytes" -> io.circe.Json.fromLong(comparedBytes),
    "sourceSha256" -> io.circe.Json.fromString(sourceSha256), "outputSha256" -> io.circe.Json.fromString(outputSha256))

/** Bounded copying and comparison. No tensor payload is materialized as an array. */
object BinaryTransfer:
  val DefaultBufferBytes = 1024 * 1024

  def copyFile(source: Path, destination: Path, cloner: FileCloner = FileCloner.platform,
      bufferBytes: Int = DefaultBufferBytes): String =
    Diagnostics.check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), destination, "copy", "output already exists")
    val method = if cloner.cloneNew(source, destination) then "apfs-clone" else
      FileIO.copyRange(source, destination, ByteRange(ByteOffset(0), ByteCount(Files.size(source))), bufferBytes)
      "buffered-copy"
    Diagnostics.check(!Files.isSameFile(source, destination) && !Files.isSymbolicLink(destination) && Files.size(source) == Files.size(destination),
      destination, "copy", "output must be an independent regular file of equal size")
    method

  def rewrite(shard: ShardProjection, destination: Path, bufferBytes: Int = DefaultBufferBytes): Unit = Diagnostics.guard(destination, "shard rewrite") {
    require(bufferBytes > 0 && bufferBytes <= FileIO.MaxTransferBuffer, "invalid buffer size")
    val prefix = SafetensorsIO.encodeHeaderJson(shard.header)
    Using.resource(FileChannel.open(shard.source.path, StandardOpenOption.READ)) { input =>
      Using.resource(FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { output =>
        writeFully(output, ByteBuffer.wrap(prefix), 0L)
        val buffer = ByteBuffer.allocate(bufferBytes)
        shard.copies.foreach { span =>
          transfer(input, output, Math.addExact(shard.source.payloadStart.longValue, span.source.start.longValue),
            Math.addExact(prefix.length.toLong, span.destination.start.longValue), span.source.length.longValue, buffer, shard.source.path)
        }
        Diagnostics.check(output.size() == Math.addExact(prefix.length.toLong, shard.payloadBytes.longValue), destination, "size", "rewritten shard size differs from plan")
        output.force(true)
      }
    }
  }

  private def writeFully(channel: FileChannel, buffer: ByteBuffer, position: Long): Unit =
    var at = position
    while buffer.hasRemaining do at = Math.addExact(at, channel.write(buffer, at).toLong)

  private def transfer(input: FileChannel, output: FileChannel, source: Long, destination: Long, bytes: Long,
      buffer: ByteBuffer, path: Path): Unit =
    var done = 0L
    while done < bytes do
      buffer.clear()
      buffer.limit(math.min(bytes - done, buffer.capacity().toLong).toInt)
      FileIO.readFully(input, buffer, Math.addExact(source, done), path)
      val count = buffer.position()
      buffer.flip()
      writeFully(output, buffer, Math.addExact(destination, done))
      done = Math.addExact(done, count.toLong)

  /** Compare every surviving byte, and hash each source projection and output tensor independently. */
  def verify(shard: ShardProjection, outputPath: Path, bufferBytes: Int = DefaultBufferBytes): Vector[TensorVerification] =
    require(bufferBytes > 0 && bufferBytes <= FileIO.MaxTransferBuffer, "invalid buffer size")
    val output = SafetensorsIO.read(outputPath)
    Diagnostics.check(output.originalHeader == shard.header, outputPath, "header", "output header differs from plan")
    Using.resource(FileChannel.open(shard.source.path, StandardOpenOption.READ)) { inputChannel =>
      Using.resource(FileChannel.open(outputPath, StandardOpenOption.READ)) { outputChannel =>
        val left = ByteBuffer.allocate(bufferBytes)
        val right = ByteBuffer.allocate(bufferBytes)
        shard.tensors.map { tensor =>
          val inputDigest = MessageDigest.getInstance("SHA-256")
          val outputDigest = MessageDigest.getInstance("SHA-256")
          var total = 0L
          tensor.copies.foreach { span =>
            var done = 0L
            while done < span.source.length.longValue do
              val count = math.min(span.source.length.longValue - done, bufferBytes.toLong).toInt
              left.clear().limit(count)
              right.clear().limit(count)
              FileIO.readFully(inputChannel, left, Math.addExact(shard.source.payloadStart.longValue, Math.addExact(span.source.start.longValue, done)), shard.source.path)
              FileIO.readFully(outputChannel, right, Math.addExact(output.payloadStart.longValue, Math.addExact(span.destination.start.longValue, done)), outputPath)
              left.flip()
              right.flip()
              Diagnostics.check(left == right, outputPath, tensor.source.name, s"payload differs at destination byte ${span.destination.start.longValue + done}")
              inputDigest.update(left)
              outputDigest.update(right)
              done = Math.addExact(done, count.toLong)
              total = Math.addExact(total, count.toLong)
          }
          Diagnostics.check(total == tensor.result.expectedBytes.longValue, outputPath, tensor.source.name, "comparison did not cover the whole output tensor")
          def hex(digest: MessageDigest): String = digest.digest().map(b => f"${b & 255}%02x").mkString
          TensorVerification(tensor.source.name, total, hex(inputDigest), hex(outputDigest))
        }
      }
    }
