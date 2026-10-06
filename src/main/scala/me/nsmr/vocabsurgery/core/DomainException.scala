package me.nsmr.vocabsurgery.core

/** A pure operation failed an invariant; no filesystem or mutable model is involved. */
final case class DomainException(code: String, location: String, detail: String)
    extends IllegalArgumentException(s"$code [$location]: $detail")

private[vocabsurgery] object DomainChecks:
  def fail(code: String, location: String, detail: String): Nothing =
    throw DomainException(code, location, detail)

  def check(condition: Boolean, code: String, location: String, detail: => String): Unit =
    if !condition then fail(code, location, detail)
