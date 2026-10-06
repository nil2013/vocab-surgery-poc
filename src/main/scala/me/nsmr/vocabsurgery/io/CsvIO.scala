package me.nsmr.vocabsurgery.io

import java.io.StringReader
import java.nio.file.Path
import org.apache.commons.csv.{CSVFormat, DuplicateHeaderMode}
import scala.jdk.CollectionConverters.*
import scala.util.Using

final case class CsvRow(recordNumber: Long, values: Map[String, String])
final case class CsvTable(columns: Vector[String], rows: Vector[CsvRow])

/** Header-based CSV reader, including quoted delimiters, embedded newlines and UTF-8 BOM. */
object CsvIO:
  def read(path: Path, requiredColumns: Set[String]): CsvTable =
    Diagnostics.guard(path, "CSV") {
      val text = FileIO.readUtf8(path).stripPrefix("\ufeff")
      val format = CSVFormat.DEFAULT.builder()
        .setHeader()
        .setSkipHeaderRecord(true)
        .setIgnoreEmptyLines(false)
        .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
        .get()
      Using.resource(format.parse(new StringReader(text))) { parser =>
        val columns = parser.getHeaderNames.asScala.toVector
        Diagnostics.check(columns.nonEmpty, path, "header", "CSV header is empty")
        val missing = requiredColumns -- columns.toSet
        Diagnostics.check(missing.isEmpty, path, "header", s"missing columns: ${missing.toVector.sorted.mkString(", ")}")
        val rows = parser.iterator().asScala.map { record =>
          Diagnostics.check(record.isConsistent, path, s"record ${record.getRecordNumber}", "column count does not match header")
          CsvRow(record.getRecordNumber, columns.map(c => c -> record.get(c)).toMap)
        }.toVector
        CsvTable(columns, rows)
      }
    }
