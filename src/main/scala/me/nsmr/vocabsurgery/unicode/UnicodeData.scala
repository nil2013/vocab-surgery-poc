package me.nsmr.vocabsurgery.unicode

import com.ibm.icu.lang.{UCharacter, UCharacterEnums, UProperty, UScript}
import com.ibm.icu.text.Normalizer2
import com.ibm.icu.util.VersionInfo
import me.nsmr.vocabsurgery.core.*

opaque type UnicodeScript = Int
object UnicodeScript:
  def fromName(name: String): UnicodeScript =
    val value = UScript.getCodeFromName(name)
    DomainChecks.check(value >= 0, "unknown-script", "script", s"unknown Unicode Script: $name")
    value
  val Arabic: UnicodeScript = UScript.ARABIC
  val Cyrillic: UnicodeScript = UScript.CYRILLIC
  val Hangul: UnicodeScript = UScript.HANGUL
  val Han: UnicodeScript = UScript.HAN
  extension (value: UnicodeScript) def name: String = UScript.getName(value)
  private[unicode] def fromCode(value: Int): UnicodeScript = value

final case class UnicodeProvenance(provider: String, providerVersion: String, unicodeVersion: String)

/** Unicode 17 properties and NFC from pinned ICU data, independent of the host JDK. */
object UnicodeData:
  private val version = UCharacter.getUnicodeVersion
  DomainChecks.check(version == VersionInfo.getInstance(17, 0, 0, 0), "unicode-version", "ICU4J",
    s"expected Unicode 17.0.0, found $version")
  val provenance: UnicodeProvenance = UnicodeProvenance("ICU4J", VersionInfo.ICU_VERSION.toString, "17.0.0")
  private val nfc = Normalizer2.getNFCInstance()

  def script(codePoint: CodePoint): UnicodeScript = UnicodeScript.fromCode(UScript.getScript(codePoint.intValue))
  /** Unicode Math binary property; this does not include every character used in mathematical notation. */
  def isMath(codePoint: CodePoint): Boolean = UCharacter.hasBinaryProperty(codePoint.intValue, UProperty.MATH)
  def isHanKanji(codePoint: CodePoint): Boolean =
    script(codePoint) == UnicodeScript.Han && UCharacter.getType(codePoint.intValue) == UCharacterEnums.ECharacterCategory.OTHER_LETTER
  def normalize(surface: TokenSurface): TokenSurface = TokenSurface(nfc.normalize(surface.text))
  def normalizedCodePoints(codePoint: CodePoint): Set[CodePoint] =
    normalize(TokenSurface(codePoint.text)).codePoints.toSet
