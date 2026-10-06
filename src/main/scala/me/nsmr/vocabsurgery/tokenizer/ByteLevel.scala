package me.nsmr.vocabsurgery.tokenizer

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import me.nsmr.vocabsurgery.core.*

final case class DecodedToken(bytes: Vector[Byte], surface: Option[TokenSurface])

/** The GPT-2 / Hugging Face 256-byte bijection; partial UTF-8 remains bytes, not replacement text. */
object ByteLevel:
  private val visible = (33 to 126).toVector ++ (161 to 172) ++ (174 to 255)
  private val hidden = (0 to 255).filterNot(visible.contains).toVector
  private val byteToScalar = (visible.map(b => b -> b) ++ hidden.zipWithIndex.map((b, i) => b -> (256 + i))).toMap
  private val scalarToByte = byteToScalar.map((b, cp) => cp -> b.toByte)
  val alphabet: Vector[String] = (0 to 255).map(b => CodePoint(byteToScalar(b)).text).toVector

  def encode(bytes: Iterable[Byte]): String = bytes.iterator.map(b => alphabet(b & 0xff)).mkString
  def encode(surface: TokenSurface): String = encode(surface.text.getBytes(StandardCharsets.UTF_8).toVector)

  def decode(encoded: String): DecodedToken =
    DomainChecks.check(encoded.nonEmpty, "empty-bytelevel-token", "vocabulary", "token key cannot be empty")
    val bytes = CodePoint.sequence(encoded).map { cp =>
      scalarToByte.getOrElse(cp.intValue, DomainChecks.fail("invalid-bytelevel-character", "vocabulary", cp.notation))
    }
    DecodedToken(bytes, decodeUtf8(bytes))

  def decodeUtf8(bytes: Vector[Byte]): Option[TokenSurface] =
    try
      val decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
      Some(TokenSurface(decoder.decode(ByteBuffer.wrap(bytes.toArray)).toString))
    catch case _: CharacterCodingException => None
