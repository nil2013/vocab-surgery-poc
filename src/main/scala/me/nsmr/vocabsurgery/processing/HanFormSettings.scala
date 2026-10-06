package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.{Diagnostics, JsonFields}
import me.nsmr.vocabsurgery.kanji.*
import me.nsmr.vocabsurgery.unicode.UnicodeData

/** The `hanForms` block of a character policy: which forms to prune, how JMdict use protects, manual keeps, and where
  * Unihan and JMdict are. Omitted paths mean Unihan of ICU's version and the newest JMdict; plans record the resolved paths.
  */
final case class HanFormSettings(rule: HanFormRule, unihan: Option[Path] = None, jmdict: Option[Path] = None):
  def inputs: Vector[Path] = Vector(unihan.getOrElse(ReferenceFetch.directory(Path.of("outputs"), UnihanSource, UnicodeData.unicodeVersion))) ++
    jmdict.orElse(ReferenceFetch.latest(Path.of("outputs"), JMdictSource)).toVector
  def resolved: HanFormSettings =
    val (_, unihanPath, jmdictPath) = HanReferencePaths(Path.of("kanji-data"), unihan, jmdict).resolve(Path.of("outputs"))
    copy(unihan = Some(unihanPath), jmdict = Some(jmdictPath))
  def json: Json = Json.obj("prune" -> Json.fromValues(rule.prune.toVector.map(_.name).sorted.map(Json.fromString)),
    "keepInJMdict" -> Json.fromString(rule.keepInJMdict.name),
    "keep" -> Json.fromString(rule.keep.toVector.sortBy(_.intValue).map(_.text).mkString))
    .mapObject(fields => unihan.fold(fields)(p => fields.add("unihan", Json.fromString(p.toString))))
    .mapObject(fields => jmdict.fold(fields)(p => fields.add("jmdict", Json.fromString(p.toString))))

object HanFormSettings:
  def parse(json: Json, path: Path): HanFormSettings =
    val fields = JsonFields.obj(json, path, "hanForms")
    fields.keys.find(!Set("prune", "keepInJMdict", "keep", "unihan", "jmdict")(_)).foreach(name => Diagnostics.invalid(path, s"hanForms.$name", "unknown field"))
    val prune = JsonFields.array(JsonFields.field(json, "prune", path, "hanForms"), path, "hanForms.prune").map { value =>
      val name = JsonFields.text(value, path, "hanForms.prune")
      HanForm.values.find(_.name == name).getOrElse(Diagnostics.invalid(path, "hanForms.prune", s"unknown form $name"))
    }.toSet
    Diagnostics.check(prune.nonEmpty, path, "hanForms.prune", "name at least one form")
    val protection = fields("keepInJMdict").map(JsonFields.text(_, path, "hanForms.keepInJMdict")).map { name =>
      JMdictProtection.values.find(_.name == name).getOrElse(Diagnostics.invalid(path, "hanForms.keepInJMdict", s"unknown protection $name"))
    }.getOrElse(JMdictProtection.AnyHeadword)
    val keep = fields("keep").map(v => CodePoint.sequence(JsonFields.text(v, path, "hanForms.keep")).toSet).getOrElse(Set.empty)
    def location(name: String) = fields(name).map(v => Path.of(JsonFields.text(v, path, s"hanForms.$name")))
    HanFormSettings(HanFormRule(prune, protection, keep), location("unihan"), location("jmdict"))

/** The judged candidates of a policy's `hanForms` rule, with the reference inputs they came from. */
final case class HanFormSelection(rule: HanFormRule, candidates: Vector[(HanJudgment, HanFormDecision)], inputs: Json)
