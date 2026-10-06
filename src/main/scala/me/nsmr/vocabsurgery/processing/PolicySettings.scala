package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.{JsonIO, JsonFields, Diagnostics}
import me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference
import me.nsmr.vocabsurgery.model.ParameterCountPolicy
import me.nsmr.vocabsurgery.tokenizer.PruningPolicy
import me.nsmr.vocabsurgery.unicode.*

/** Explicit policy settings. Reference contents are loaded and fingerprinted separately. */
final case class PolicySettings(
    forbiddenScripts: Set[UnicodeScript] = Set(UnicodeScript.Arabic, UnicodeScript.Cyrillic, UnicodeScript.Hangul),
    pruneHanOutsideReference: Boolean = true,
    japaneseExtra: Set[CodePoint] = Set.empty,
    explicitForbidden: Set[CodePoint] = Set.empty,
    protectedIds: Set[OriginalTokenId] = Set.empty,
    protectedContents: Set[String] = Set.empty,
    parameterCount: ParameterCountPolicy = ParameterCountPolicy.OmitUnverified,
    protectMath: Boolean = true
):
  def domain(reference: JapaneseKanjiReference): PruningPolicy =
    PruningPolicy(reference, forbiddenScripts, pruneHanOutsideReference, explicitForbidden, protectedIds, protectedContents, protectMath)
  def json: Json = Json.obj(
    "unicodeVersion" -> Json.fromString(UnicodeData.provenance.unicodeVersion),
    "forbiddenScripts" -> Json.fromValues(forbiddenScripts.toVector.map(_.name).sorted.map(Json.fromString)),
    "pruneHanOutsideReference" -> Json.fromBoolean(pruneHanOutsideReference),
    "japaneseExtra" -> Json.fromValues(japaneseExtra.toVector.sortBy(_.intValue).map(cp => Json.fromInt(cp.intValue))),
    "explicitForbidden" -> Json.fromValues(explicitForbidden.toVector.sortBy(_.intValue).map(cp => Json.fromInt(cp.intValue))),
    "protectedIds" -> Json.fromValues(protectedIds.toVector.sortBy(_.intValue).map(id => Json.fromInt(id.intValue))),
    "protectedContents" -> Json.fromValues(protectedContents.toVector.sorted.map(Json.fromString)),
    "unregisteredRows" -> Json.fromString("drop"),
    "parameterCount" -> Json.fromString(if parameterCount == ParameterCountPolicy.OmitUnverified then "omit-unverified" else "verified-logical-weights"),
    "protectMath" -> Json.fromBoolean(protectMath))

object PolicySettings:
  def load(path: Path): PolicySettings = parse(JsonIO.read(path), path)

  def parse(json: Json, path: Path): PolicySettings = Diagnostics.guard(path, "policy") {
    val fields = JsonFields.obj(json, path, "policy")
    val names = Set("unicodeVersion", "forbiddenScripts", "pruneHanOutsideReference", "japaneseExtra",
      "explicitForbidden", "protectedIds", "protectedContents", "unregisteredRows", "parameterCount", "protectMath")
    Diagnostics.check(fields.keys.forall(names), path, "policy", "unknown policy field")
    fields("unicodeVersion").foreach(v => Diagnostics.check(JsonFields.text(v, path, "unicodeVersion") == UnicodeData.provenance.unicodeVersion, path, "unicodeVersion", "Unicode version does not match pinned provider"))
    fields("unregisteredRows").foreach(v => Diagnostics.check(JsonFields.text(v, path, "unregisteredRows") == "drop", path, "unregisteredRows", "only drop is supported"))
    def array(name: String): Option[Vector[Json]] = fields(name).map(JsonFields.array(_, path, name))
    def points(name: String): Set[CodePoint] = array(name).getOrElse(Vector.empty).map(value => CodePoint(JsonFields.int(value, path, name))).toSet
    val scripts = array("forbiddenScripts").map(_.map(v => UnicodeScript.fromName(JsonFields.text(v, path, "forbiddenScripts"))).toSet)
      .getOrElse(PolicySettings().forbiddenScripts)
    val parameters = fields("parameterCount").map(JsonFields.text(_, path, "parameterCount")).getOrElse("omit-unverified") match
      case "omit-unverified" => ParameterCountPolicy.OmitUnverified
      case "verified-logical-weights" => ParameterCountPolicy.VerifiedLogicalWeights
      case _ => Diagnostics.invalid(path, "parameterCount", "unsupported parameter-count policy")
    PolicySettings(scripts, fields("pruneHanOutsideReference").map(JsonFields.bool(_, path, "pruneHanOutsideReference")).getOrElse(true),
      points("japaneseExtra"), points("explicitForbidden"), array("protectedIds").getOrElse(Vector.empty).map(v => OriginalTokenId(JsonFields.int(v, path, "protectedIds"))).toSet,
      array("protectedContents").getOrElse(Vector.empty).map(v => JsonFields.text(v, path, "protectedContents")).toSet, parameters,
      fields("protectMath").map(JsonFields.bool(_, path, "protectMath")).getOrElse(true))
  }
