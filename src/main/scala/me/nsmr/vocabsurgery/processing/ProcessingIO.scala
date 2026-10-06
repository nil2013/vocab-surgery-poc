package me.nsmr.vocabsurgery.processing

import io.circe.{Json, Printer}
import java.nio.file.{Files, LinkOption, Path, StandardOpenOption}
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import scala.util.Using
import me.nsmr.vocabsurgery.io.*

private[processing] object ProcessingIO:
  val ToolVersion = "0.1.0-SNAPSHOT"
  val printer: Printer = Printer.noSpaces.copy(sortKeys = true)
  def canonical(json: Json): String = printer.print(json)
  def signature(json: Json): String = MessageDigest.getInstance("SHA-256").digest(canonical(json).getBytes(StandardCharsets.UTF_8)).map(b => f"${b & 255}%02x").mkString
  def write(path: Path, json: Json): Unit = FileIO.writeNewUtf8(path, canonical(json) + "\n")
  def lines(path: Path, values: Iterator[Json]): Unit = Diagnostics.guard(path, "JSONL write") {
    Using.resource(Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { writer =>
      values.foreach { json => writer.write(canonical(json)); writer.newLine() }
    }
  }

  def relative(root: Path, name: String): Path =
    val candidate = root.resolve(name).normalize()
    Diagnostics.check(!Path.of(name).isAbsolute && name.nonEmpty && candidate.startsWith(root.normalize()) && candidate != root.normalize(), root, "relative path", name)
    candidate

  /** Write paths stay under an explicit output root; symlink components are never followed. */
  def outputPath(root: Path, path: Path, protectedRoots: Vector[Path] = Vector.empty): Path =
    val base = root.toAbsolutePath.normalize()
    val target = path.toAbsolutePath.normalize()
    Diagnostics.check(!Files.isSymbolicLink(base), base, "output boundary", "symlink output root is not allowed")
    var ancestor = base.getParent
    while !Files.exists(ancestor) do ancestor = ancestor.getParent
    val physicalBase = ancestor.toRealPath().resolve(ancestor.relativize(base))
    val physicalTarget = if target.startsWith(base) then physicalBase.resolve(base.relativize(target)) else target
    Diagnostics.check(physicalTarget.startsWith(physicalBase) && physicalTarget != physicalBase, path, "output boundary", "output must be beneath the output root")
    var current: Path = physicalTarget
    while current != null && current.startsWith(physicalBase) do
      Diagnostics.check(!Files.isSymbolicLink(current), current, "output boundary", "symlink output component is not allowed")
      current = current.getParent
    protectedRoots.foreach { input =>
      val real = input.toRealPath()
      Diagnostics.check(!physicalTarget.startsWith(real) && !real.startsWith(physicalTarget), path, "output boundary", "output overlaps an input directory")
    }
    physicalTarget

  def createDirectory(root: Path, path: Path, protectedRoots: Vector[Path]): Path =
    val target = outputPath(root, path, protectedRoots)
    Diagnostics.check(!Files.exists(target, LinkOption.NOFOLLOW_LINKS), target, "create", "output already exists")
    Files.createDirectories(target.getParent)
    Files.createDirectory(target)
    target

  def revision(directory: Path): Option[String] =
    val process = new ProcessBuilder("git", "-C", directory.toString, "rev-parse", "HEAD").redirectError(ProcessBuilder.Redirect.DISCARD).start()
    if !process.waitFor(5L, java.util.concurrent.TimeUnit.SECONDS) then
      process.destroyForcibly()
      None
    else
      val text = new String(process.getInputStream.readAllBytes(), StandardCharsets.UTF_8).trim
      Option.when(process.exitValue() == 0 && text.matches("[0-9a-f]{40,64}"))(text)
