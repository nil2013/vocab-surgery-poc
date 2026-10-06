package me.nsmr.vocabsurgery.unicode

import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO}

/** Unihan's simplified / traditional variant fields of one Unicode version, keyed by the character they describe. */
final case class UnihanVariants(unicodeVersion: String, simplified: Map[CodePoint, Set[CodePoint]], traditional: Map[CodePoint, Set[CodePoint]]):
  /** Characters with a simplified variant other than themselves: traditional or old forms. */
  def traditionalForms: Set[CodePoint] = simplified.collect { case (cp, variants) if (variants - cp).nonEmpty => cp }.toSet
  /** Characters with a traditional variant other than themselves: simplified forms. */
  def simplifiedForms: Set[CodePoint] = traditional.collect { case (cp, variants) if (variants - cp).nonEmpty => cp }.toSet

/** Read `Unihan_Variants.txt`: a `# Unicode Version` header, then `U+XXXX<TAB>field<TAB>values` lines.
  * Only kSimplifiedVariant and kTraditionalVariant are kept; their values are `U+XXXX` lists.
  */
object UnihanLoader:
  val FileName = "Unihan_Variants.txt"
  private val CodePointText = "U\\+([0-9A-F]{4,6})".r

  def load(path: Path): UnihanVariants = Diagnostics.guard(path, "Unihan variants") {
    val lines = FileIO.readUtf8(path).split("\n", -1).toVector.map(_.stripSuffix("\r"))
    val version = lines.collectFirst { case line if line.startsWith("# Unicode Version ") => line.stripPrefix("# Unicode Version ").trim }
      .getOrElse(Diagnostics.invalid(path, "header", "missing '# Unicode Version' line"))
    Diagnostics.check(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"), path, "header", s"invalid Unicode version $version")
    def codePoint(text: String, at: String): CodePoint = text match
      case CodePointText(hex) => CodePoint(Integer.parseInt(hex, 16))
      case _ => Diagnostics.invalid(path, at, s"expected U+XXXX, found $text")
    val fields = Map("kSimplifiedVariant" -> Map.newBuilder[CodePoint, Set[CodePoint]], "kTraditionalVariant" -> Map.newBuilder[CodePoint, Set[CodePoint]])
    lines.zipWithIndex.foreach { (line, index) =>
      if line.nonEmpty && !line.startsWith("#") then
        val at = s"line ${index + 1}"
        line.split("\t", -1) match
          case Array(source, field, values) => fields.get(field).foreach { builder =>
            builder += codePoint(source, at) -> values.split(" ").filter(_.nonEmpty).map(v => codePoint(v.takeWhile(_ != '<'), at)).toSet
          }
          case _ => Diagnostics.invalid(path, at, "expected three tab-separated fields")
    }
    UnihanVariants(version, fields("kSimplifiedVariant").result(), fields("kTraditionalVariant").result())
  }
