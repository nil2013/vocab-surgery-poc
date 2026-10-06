package me.nsmr.vocabsurgery.lexicon

import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import javax.xml.stream.{XMLInputFactory, XMLStreamConstants}
import scala.util.Using
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.Diagnostics

/** One JMdict kanji written form (`keb`). `priority` marks common words (`ke_pri`); `info` holds `ke_inf` codes. */
final case class JapaneseHeadword(text: String, priority: Boolean, info: Set[String]):
  /** Irregular, outdated, rarely used or search-only forms do not show that a character is ordinary Japanese. */
  def standard: Boolean = !info.exists(JapaneseHeadword.NonStandard)

object JapaneseHeadword:
  val NonStandard: Set[String] = Set("iK", "oK", "rK", "sK")

/** How a character appears in a lexicon's headwords. Examples prefer common, then standard, then short words. */
final case class CharacterUsage(headwords: Int, standard: Int, priority: Int, examples: Vector[String])

/** Kanji headwords of one JMdict build, identified by its creation date. */
final case class JapaneseLexicon(created: String, headwords: Vector[JapaneseHeadword]):
  lazy val characterUsage: Map[CodePoint, CharacterUsage] =
    headwords.flatMap(word => CodePoint.sequence(word.text).distinct.map(_ -> word)).groupMap(_._1)(_._2).view.mapValues { words =>
      val examples = words.sortBy(w => (!w.priority, !w.standard, w.text.length, w.text)).map(_.text).distinct.take(5)
      CharacterUsage(words.size, words.count(_.standard), words.count(_.priority), examples)
    }.toMap
  def usage(cp: CodePoint): CharacterUsage = characterUsage.getOrElse(cp, CharacterUsage(0, 0, 0, Vector.empty))

/** Stream `JMdict_e.gz` with StAX. Entity references (part-of-speech and `ke_inf` codes) are kept as names, never expanded,
  * and external entities are refused. The creation date comes from the `JMdict created:` comment.
  */
object JMdictLoader:
  val FileName = "JMdict_e.gz"
  private val Created = ".*JMdict created: ([0-9]{4}-[0-9]{2}-[0-9]{2}).*".r

  def load(path: Path): JapaneseLexicon = Diagnostics.guard(path, "JMdict") {
    val factory = XMLInputFactory.newInstance()
    factory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
    Using.resource(GZIPInputStream(Files.newInputStream(path), 1 << 16)) { input =>
      val reader = factory.createXMLStreamReader(input, "UTF-8")
      try
        var created: Option[String] = None
        val words = Vector.newBuilder[JapaneseHeadword]
        var text: Option[String] = None
        var priority = false
        var info = Set.empty[String]
        var field = ""
        val buffer = new StringBuilder
        while reader.hasNext do
          reader.next() match
            case XMLStreamConstants.COMMENT => reader.getText match
              case Created(date) if created.isEmpty => created = Some(date)
              case _ => ()
            case XMLStreamConstants.START_ELEMENT =>
              field = reader.getLocalName
              buffer.clear()
              if field == "k_ele" then { text = None; priority = false; info = Set.empty }
            case XMLStreamConstants.CHARACTERS => if field == "keb" then buffer.append(reader.getText)
            case XMLStreamConstants.ENTITY_REFERENCE => if field == "ke_inf" then info += reader.getLocalName
            case XMLStreamConstants.END_ELEMENT =>
              reader.getLocalName match
                case "keb" => text = Some(buffer.toString)
                case "ke_pri" => priority = true
                case "k_ele" =>
                  words += JapaneseHeadword(text.getOrElse(Diagnostics.invalid(path, "k_ele", "missing keb")), priority, info)
                case _ => ()
              field = ""
            case _ => ()
        val result = words.result()
        Diagnostics.check(result.nonEmpty, path, "keb", "no kanji headwords")
        JapaneseLexicon(created.getOrElse(Diagnostics.invalid(path, "header", "missing 'JMdict created:' comment")), result)
      finally reader.close()
    }
  }
