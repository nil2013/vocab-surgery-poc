package me.nsmr.vocabsurgery.io

import io.circe.{Json, JsonObject}
import io.circe.jawn.JawnParser
import java.nio.file.Path

/** JSON parsing preserves integer precision and rejects duplicate keys / trailing input. */
object JsonIO:
  private val parser = new JawnParser(maxValueSize = Some(16 * 1024 * 1024), allowDuplicateKeys = false)

  def read(path: Path, maxBytes: Long = FileIO.DefaultMetadataLimit): Json =
    parse(FileIO.readUtf8(path, maxBytes), path)

  def parse(text: String, source: Path): Json =
    Diagnostics.guard(source, "JSON") {
      parser.parse(text).fold(
        error => Diagnostics.invalid(source, "JSON", error.message),
        identity
      )
    }

  def writeNew(path: Path, value: Json): Unit =
    FileIO.writeNewUtf8(path, value.spaces2 + "\n")

private[vocabsurgery] object JsonFields:
  def obj(node: Json, path: Path, location: String): JsonObject =
    node.asObject.getOrElse(Diagnostics.invalid(path, location, "expected JSON object"))

  def field(node: Json, name: String, path: Path, location: String): Json =
    obj(node, path, location)(name).filterNot(_.isNull)
      .getOrElse(Diagnostics.invalid(path, s"$location.$name", "required field is missing or null"))

  def text(node: Json, path: Path, location: String): String =
    node.asString.getOrElse(Diagnostics.invalid(path, location, "expected string"))

  def bool(node: Json, path: Path, location: String): Boolean =
    node.asBoolean.getOrElse(Diagnostics.invalid(path, location, "expected boolean"))

  def long(node: Json, path: Path, location: String): Long =
    val number = node.asNumber.getOrElse(Diagnostics.invalid(path, location, "expected integer"))
    Diagnostics.check(number.toString.matches("-?(0|[1-9][0-9]*)"), path, location, "expected integer literal")
    number.toLong.getOrElse(Diagnostics.invalid(path, location, "integer exceeds signed 64-bit range"))

  def int(node: Json, path: Path, location: String): Int =
    val result = long(node, path, location)
    Diagnostics.check(result >= Int.MinValue && result <= Int.MaxValue, path, location, "integer exceeds 32-bit range")
    result.toInt

  def array(node: Json, path: Path, location: String): Vector[Json] =
    node.asArray.getOrElse(Diagnostics.invalid(path, location, "expected array"))

  def entries(node: Json, path: Path, location: String): Vector[(String, Json)] =
    obj(node, path, location).toVector
