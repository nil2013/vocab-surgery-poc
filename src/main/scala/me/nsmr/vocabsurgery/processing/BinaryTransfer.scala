package me.nsmr.vocabsurgery.processing

import com.sun.jna.{Native, NativeLibrary, Platform}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.security.MessageDigest
import scala.util.Using
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.model.*

/** File operations whose availability depends on the OS and filesystem. Each implementation states what it did. */
trait FileTransfer:
  /** Create `destination` as an independent copy-on-write clone of `source`, returning the method used,
    * or None when this filesystem cannot clone it. None leaves no destination behind.
    */
  def cloneNew(source: Path, destination: Path): Option[String]
  /** Rename a staged directory to `target`, failing instead of replacing anything created there meanwhile. */
  def publishNew(stage: Path, target: Path): Unit

object FileTransfer:
  /** The implementation for the running OS; other platforms have no exclusive rename and are refused. */
  lazy val platform: FileTransfer =
    if Platform.isMac then MacOs
    else if Platform.isLinux then Linux
    else throw UnsupportedOperationException(s"no exclusive directory publication for ${sys.props("os.name")}")

  /** Keep `base`'s publication but always fall back to copying. */
  def copying(base: FileTransfer): FileTransfer = new FileTransfer:
    def cloneNew(source: Path, destination: Path): Option[String] = None
    def publishNew(stage: Path, target: Path): Unit = base.publishNew(stage, target)

  private object MacOs extends FileTransfer:
    private lazy val system = NativeLibrary.getInstance("System")
    def cloneNew(source: Path, destination: Path): Option[String] =
      Diagnostics.check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), destination, "clone", "destination already exists")
      val result = system.getFunction("clonefile").invokeInt(Array[AnyRef](source.toRealPath().toString, destination.toString, Int.box(0)))
      if result == 0 then Some("clonefile")
      else
        Diagnostics.check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), destination, "clone", "clone failed after creating destination")
        None
    def publishNew(stage: Path, target: Path): Unit =
      val renameExclusive = 4
      val result = system.getFunction("renamex_np").invokeInt(Array[AnyRef](stage.toString, target.toString, Int.box(renameExclusive)))
      Diagnostics.check(result == 0, target, "publish", s"exclusive atomic rename failed (errno=${Native.getLastError()})")

  private object Linux extends FileTransfer:
    private lazy val libc = NativeLibrary.getInstance("c")
    private val ReadOnly = 0
    private val CreateNew = 0x1 | 0x40 | 0x80 | 0x80000 // O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC
    private val Ficlone = 0x40049409L
    private val AtCwd = -100
    private val RenameNoReplace = 1

    def cloneNew(source: Path, destination: Path): Option[String] =
      def call(name: String, args: AnyRef*): Int = libc.getFunction(name).invokeInt(args.toArray)
      val input = call("open", source.toRealPath().toString, Int.box(ReadOnly))
      Diagnostics.check(input >= 0, source, "clone", s"cannot open source (errno=${Native.getLastError()})")
      try
        val output = call("open", destination.toString, Int.box(CreateNew), Int.box(0x1a4))
        Diagnostics.check(output >= 0, destination, "clone", s"cannot create destination (errno=${Native.getLastError()})")
        val result = try call("ioctl", Int.box(output), Long.box(Ficlone), Int.box(input)) finally call("close", Int.box(output))
        if result == 0 then Some("ficlone")
        else
          Files.delete(destination)
          None
      finally call("close", Int.box(input))

    def publishNew(stage: Path, target: Path): Unit =
      val result = libc.getFunction("renameat2").invokeInt(Array[AnyRef](Int.box(AtCwd), stage.toString, Int.box(AtCwd), target.toString, Int.box(RenameNoReplace)))
      Diagnostics.check(result == 0, target, "publish", s"exclusive rename failed (errno=${Native.getLastError()})")

final case class TensorVerification(name: String, comparedBytes: Long, sourceSha256: String, outputSha256: String):
  def json: io.circe.Json = io.circe.Json.obj("name" -> io.circe.Json.fromString(name), "bytes" -> io.circe.Json.fromLong(comparedBytes),
    "sourceSha256" -> io.circe.Json.fromString(sourceSha256), "outputSha256" -> io.circe.Json.fromString(outputSha256))

/** Bounded copying and comparison. No tensor payload is materialized as an array. */
object BinaryTransfer:
  val DefaultBufferBytes = 1024 * 1024

  /** Create an independent clone or bounded full-file copy and return the recorded transfer method. */
  def copyFile(source: Path, destination: Path, transfer: FileTransfer = FileTransfer.platform,
      bufferBytes: Int = DefaultBufferBytes): String =
    Diagnostics.check(!Files.exists(destination, LinkOption.NOFOLLOW_LINKS), destination, "copy", "output already exists")
    val method = transfer.cloneNew(source, destination).getOrElse {
      FileIO.copyRange(source, destination, ByteRange(ByteOffset(0), ByteCount(Files.size(source))), bufferBytes)
      "buffered-copy"
    }
    Diagnostics.check(!Files.isSameFile(source, destination) && !Files.isSymbolicLink(destination) && Files.size(source) == Files.size(destination),
      destination, "copy", "output must be an independent regular file of equal size")
    method

  /** Create a shard from its preserved header JSON and positional copy spans; never dequantize rows. */
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
