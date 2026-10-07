package me.nsmr.vocabsurgery.processing

import io.circe.jawn.JawnParser
import java.nio.file.Path
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO}

/** A token deletion list: one JSON string literal per line (`" yang"`, `"什么"`, `"\n\n"`), so leading spaces and
  * control characters stay unambiguous. Blank lines and lines starting with `#` are skipped; duplicates are rejected,
  * within one list and across the lists a policy combines.
  */
object DeletionList:
  private val parser = JawnParser(allowDuplicateKeys = false)

  def read(path: Path): Vector[String] = Diagnostics.guard(path, "deletion list") {
    val entries = FileIO.readUtf8(path).split("\n", -1).toVector.zipWithIndex.flatMap { (raw, index) =>
      val line = raw.stripSuffix("\r").trim
      Option.when(line.nonEmpty && !line.startsWith("#")) {
        val value = parser.parse(line).toOption.flatMap(_.asString)
          .getOrElse(Diagnostics.invalid(path, s"line ${index + 1}", "expected one JSON string literal"))
        Diagnostics.check(value.nonEmpty, path, s"line ${index + 1}", "empty token")
        value -> (index + 1)
      }
    }
    entries.groupBy(_._1).collectFirst { case (value, lines) if lines.size > 1 =>
      Diagnostics.invalid(path, s"lines ${lines.map(_._2).mkString(", ")}", s"duplicate entry ${io.circe.Json.fromString(value).noSpaces}")
    }
    entries.map(_._1)
  }

  def readAll(paths: Vector[Path]): Vector[String] =
    val lists = paths.map(path => path -> read(path))
    lists.flatMap((path, entries) => entries.map(_ -> path)).groupBy(_._1).collectFirst { case (value, found) if found.size > 1 =>
      Diagnostics.invalid(found(1)._2, "entries", s"${io.circe.Json.fromString(value).noSpaces} is also listed in ${found.head._2}")
    }
    lists.flatMap(_._2)
