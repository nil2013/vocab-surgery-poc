package vocabsurgery.io

import java.io.IOException
import java.nio.file.{NoSuchFileException, Path}
import java.nio.charset.CharacterCodingException
import scala.util.control.NonFatal

enum ErrorKind:
  case MissingFile, InvalidData, UnsupportedFormat, IoFailure

/** An infrastructure failure with its input path and field / record context. */
final case class DataException(
    kind: ErrorKind,
    source: Path,
    location: String,
    detail: String,
    underlying: Throwable = null
) extends IOException(s"$source [$location]: $detail", underlying)

private[vocabsurgery] object Diagnostics:
  def invalid(path: Path, location: String, detail: String): Nothing =
    throw DataException(ErrorKind.InvalidData, path, location, detail)

  def check(condition: Boolean, path: Path, location: String, detail: => String): Unit =
    if !condition then invalid(path, location, detail)

  def guard[A](path: Path, location: String)(body: => A): A =
    try body
    catch
      case e: DataException => throw e
      case e: NoSuchFileException =>
        throw DataException(ErrorKind.MissingFile, path, location, "file does not exist", e)
      case e: CharacterCodingException =>
        throw DataException(ErrorKind.InvalidData, path, location, "invalid UTF-8 text", e)
      case e: IOException =>
        throw DataException(ErrorKind.IoFailure, path, location, e.getMessage, e)
      case NonFatal(e) =>
        throw DataException(ErrorKind.InvalidData, path, location, e.getMessage, e)
