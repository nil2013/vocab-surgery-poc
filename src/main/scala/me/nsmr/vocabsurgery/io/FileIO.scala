package me.nsmr.vocabsurgery.io

import java.io.ByteArrayOutputStream
import java.nio.{ByteBuffer, ByteOrder}
import java.nio.channels.FileChannel
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import java.nio.file.{Files, Path, StandardOpenOption}
import java.security.MessageDigest
import scala.util.Using
import me.nsmr.vocabsurgery.core.*

/** Bounded metadata reads and byte-range operations. These never load a checkpoint. */
object FileIO:
  val DefaultMetadataLimit: Long = 64L * 1024 * 1024
  val MaxTransferBuffer: Int = 16 * 1024 * 1024

  def readUtf8(path: Path, maxBytes: Long = DefaultMetadataLimit): String =
    Diagnostics.guard(path, "UTF-8") {
      require(maxBytes >= 0 && maxBytes <= Int.MaxValue, "invalid metadata limit")
      Using.resource(FileChannel.open(path, StandardOpenOption.READ)) { channel =>
        Diagnostics.check(channel.size() <= maxBytes, path, "size", s"metadata exceeds $maxBytes bytes")
        val out = new ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(8192)
        var total = 0L
        var read = channel.read(buffer)
        while read != -1 do
          total += read
          Diagnostics.check(total <= maxBytes, path, "size", s"metadata exceeds $maxBytes bytes")
          out.write(buffer.array(), 0, read)
          buffer.clear()
          read = channel.read(buffer)
        decodeUtf8(out.toByteArray, path, "UTF-8")
      }
    }

  private[vocabsurgery] def decodeUtf8(bytes: Array[Byte], path: Path, location: String): String =
    Diagnostics.guard(path, location) {
      StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString
    }

  /** Create a new file. Existing files are never replaced; parent directories must exist. */
  def writeNewUtf8(path: Path, text: String): Unit =
    Diagnostics.guard(path, "write") {
      val encoder = StandardCharsets.UTF_8.newEncoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      val bytes = encoder.encode(java.nio.CharBuffer.wrap(text))
      Using.resource(FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { channel =>
        while bytes.hasRemaining do channel.write(bytes)
      }
    }

  /** Read exactly a range using positional reads, without changing the channel position. */
  private[vocabsurgery] def readFully(
      channel: FileChannel, buffer: ByteBuffer, position: Long, path: Path
  ): Unit =
    require(position >= 0, "negative file position")
    var offset = position
    while buffer.hasRemaining do
      val count = channel.read(buffer, offset)
      Diagnostics.check(count >= 0, path, "payload", s"unexpected EOF at byte $offset")
      offset = Math.addExact(offset, count.toLong)

  /** Copy one input range to a new file with a bounded buffer and 64-bit positions. */
  def copyRange(
      source: Path, destination: Path, range: ByteRange, bufferBytes: Int = MaxTransferBuffer
  ): Unit =
    Diagnostics.guard(source, "copy range") {
      require(bufferBytes > 0 && bufferBytes <= MaxTransferBuffer, "invalid transfer buffer size")
      Using.resource(FileChannel.open(source, StandardOpenOption.READ)) { input =>
        Diagnostics.check(range.endExclusive.longValue <= input.size(), source, "range", "range exceeds file size")
        Diagnostics.guard(destination, "write") {
          Using.resource(FileChannel.open(destination, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { output =>
            val buffer = ByteBuffer.allocate(math.min(bufferBytes.toLong, math.max(1L, range.length.longValue)).toInt)
            var position = range.start.longValue
            var remaining = range.length.longValue
            while remaining > 0 do
              buffer.clear()
              buffer.limit(math.min(remaining, buffer.capacity().toLong).toInt)
              readFully(input, buffer, position, source)
              val count = buffer.position()
              buffer.flip()
              while buffer.hasRemaining do output.write(buffer)
              remaining -= count
              position = Math.addExact(position, count.toLong)
          }
        }
      }
    }

  /** SHA-256 of a whole file or a range, with bounded allocation. */
  def sha256(path: Path, range: Option[ByteRange] = None): String =
    Diagnostics.guard(path, "SHA-256") {
      Using.resource(FileChannel.open(path, StandardOpenOption.READ)) { channel =>
        val selected = range.getOrElse(ByteRange(ByteOffset(0L), ByteCount(channel.size())))
        Diagnostics.check(selected.endExclusive.longValue <= channel.size(), path, "range", "range exceeds file size")
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteBuffer.allocate(64 * 1024)
        var position = selected.start.longValue
        var remaining = selected.length.longValue
        while remaining > 0 do
          buffer.clear()
          buffer.limit(math.min(remaining, buffer.capacity().toLong).toInt)
          readFully(channel, buffer, position, path)
          val count = buffer.position()
          buffer.flip()
          digest.update(buffer)
          remaining -= count
          position = Math.addExact(position, count.toLong)
        digest.digest().map(b => f"${b & 0xff}%02x").mkString
      }
    }
