package vocabsurgery.core

/** A Unicode scalar value. Surrogates are not valid code points in this type. */
opaque type CodePoint = Int
object CodePoint:
  def apply(value: Int): CodePoint =
    require(Character.isValidCodePoint(value) && !(value >= 0xd800 && value <= 0xdfff),
      f"not a Unicode scalar value: U+$value%04X")
    value

  def fromString(value: String): Either[String, CodePoint] =
    if value.codePointCount(0, value.length) != 1 then Left("expected one Unicode scalar value")
    else
      val cp = value.codePointAt(0)
      if cp >= 0xd800 && cp <= 0xdfff then Left("surrogate is not a Unicode scalar value")
      else Right(apply(cp))

  extension (value: CodePoint)
    def intValue: Int = value
    def text: String = new String(Character.toChars(value))
    def notation: String = f"U+$value%04X"

/** An ID in the source tokenizer / checkpoint, before any projection. */
opaque type OriginalTokenId = Int
object OriginalTokenId:
  def apply(value: Int): OriginalTokenId =
    require(value >= 0, "token ID must be nonnegative")
    value
  extension (value: OriginalTokenId) def intValue: Int = value

/** An absolute or relative byte offset; its origin is documented by its owner. */
opaque type ByteOffset = Long
object ByteOffset:
  def apply(value: Long): ByteOffset =
    require(value >= 0, "byte offset must be nonnegative")
    value
  extension (value: ByteOffset) def longValue: Long = value

/** A number of bytes, distinct from a file position. */
opaque type ByteCount = Long
object ByteCount:
  def apply(value: Long): ByteCount =
    require(value >= 0, "byte count must be nonnegative")
    value
  extension (value: ByteCount) def longValue: Long = value

/** A half-open byte range. Addition is checked when the range is constructed. */
final case class ByteRange(start: ByteOffset, length: ByteCount):
  val endExclusive: ByteOffset = ByteOffset(Math.addExact(start.longValue, length.longValue))
