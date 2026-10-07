package me.nsmr.vocabsurgery.sandbox

import com.sun.jna.Platform
import java.io.{ByteArrayOutputStream, InputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/** What a confined command may touch: its writes reach the host only in `workDirectory` (also its HOME, TMPDIR and
  * XDG directories; a container may add private scratch that is discarded), it reads only the system and `readable`,
  * has no network, and is stopped after `timeout`.
  */
final case class Confinement(workDirectory: Path, readable: Vector[Path] = Vector.empty,
    environment: Map[String, String] = Map.empty, timeout: FiniteDuration = 20.seconds):
  require(timeout > Duration.Zero, "timeout must be positive")

/** The outcome of a confined command, naming the sandbox that ran it. */
enum ConfinedResult:
  case Completed(sandbox: String, exitCode: Int, stdout: String, stderr: String)
  case TimedOut(sandbox: String, stdout: String, stderr: String)

  def sandbox: String
  def stdout: String
  def stderr: String
  def succeeded: Boolean = this match
    case Completed(_, code, _, _) => code == 0
    case TimedOut(_, _, _) => false

  def json: io.circe.Json =
    val outcome = this match
      case Completed(_, code, _, _) => Vector("exitCode" -> io.circe.Json.fromInt(code))
      case TimedOut(_, _, _) => Vector("timedOut" -> io.circe.Json.True)
    io.circe.Json.obj((Vector("sandbox" -> io.circe.Json.fromString(sandbox)) ++ outcome ++
      Vector("stdout" -> io.circe.Json.fromString(stdout), "stderr" -> io.circe.Json.fromString(stderr)))*)

/** Raised when no sandbox can run here; nothing is ever run unconfined instead. */
final class SandboxUnavailable(message: String) extends RuntimeException(message)

/** Confined execution of generated code, whose availability depends on the OS and its container runtime.
  * Each implementation states its name; none falls back to running a command unconfined.
  */
trait Sandbox:
  def name: String
  /** Why this implementation cannot run on this host, or None. */
  def unavailable: Option[String]
  def run(command: Vector[String], confinement: Confinement): ConfinedResult

/** A user's sandbox choice: `auto` picks the first available implementation for this OS. */
enum SandboxChoice(val name: String):
  case Auto extends SandboxChoice("auto")
  case SandboxExec extends SandboxChoice("sandbox-exec")
  case Docker extends SandboxChoice("docker")

object SandboxChoice:
  def parse(text: String): SandboxChoice =
    values.find(_.name == text).getOrElse(throw IllegalArgumentException(s"unknown sandbox $text; choose from ${values.map(_.name).mkString(", ")}"))
  /** VOCAB_SANDBOX, or auto. */
  def fromEnvironment: SandboxChoice = sys.env.get("VOCAB_SANDBOX").fold(Auto)(parse)

object Sandbox:
  val DockerImage = "vocab-surgery-sandbox:empty"
  private val MaxOutputBytes = 4 * 1024 * 1024

  /** Each implementation with the reason it cannot run here, if any. */
  def report(choice: SandboxChoice = SandboxChoice.fromEnvironment): io.circe.Json =
    val chosen = try Right(platform(choice).name) catch case e: SandboxUnavailable => Left(e.getMessage)
    io.circe.Json.obj("choice" -> io.circe.Json.fromString(choice.name),
      "chosen" -> chosen.fold(_ => io.circe.Json.Null, io.circe.Json.fromString),
      "error" -> chosen.fold(io.circe.Json.fromString, _ => io.circe.Json.Null),
      "implementations" -> io.circe.Json.fromValues(implementations.toVector.sortBy(_._1).map((name, sandbox) =>
        io.circe.Json.obj("name" -> io.circe.Json.fromString(name), "unavailable" -> sandbox.unavailable.fold(io.circe.Json.Null)(io.circe.Json.fromString)))))

  /** Every implementation, by name. */
  def implementations: Map[String, Sandbox] = Map(MacSandboxExec.name -> MacSandboxExec, "docker" -> Docker())

  /** The sandbox for this host: the one `choice` names, or for auto the first available of this OS's preference. */
  def platform(choice: SandboxChoice = SandboxChoice.fromEnvironment): Sandbox = select(choice, implementations, preference)

  private[sandbox] def preference: Vector[String] =
    if Platform.isMac then Vector(MacSandboxExec.name) else if Platform.isLinux then Vector("docker") else Vector.empty

  private[sandbox] def select(choice: SandboxChoice, available: Map[String, Sandbox], order: Vector[String]): Sandbox =
    @annotation.tailrec
    def first(names: List[String], reasons: Vector[String]): Sandbox = names match
      case Nil => throw SandboxUnavailable(s"no sandbox for generated code on this host; not running it unconfined (${
        if reasons.isEmpty then sys.props("os.name") else reasons.mkString("; ")})")
      case name :: rest =>
        val sandbox = available.getOrElse(name, throw SandboxUnavailable(s"no $name implementation"))
        sandbox.unavailable match
          case None => sandbox
          case Some(reason) => first(rest, reasons :+ s"$name: $reason")
    first(if choice == SandboxChoice.Auto then order.toList else List(choice.name), Vector.empty)

  /** The environment every implementation gives a command: home and scratch inside the work directory. */
  def confinedEnvironment(workDirectory: Path, extra: Map[String, String] = Map.empty): Map[String, String] =
    val work = workDirectory.toAbsolutePath.normalize.toString
    Map("PATH" -> "/bin:/usr/bin", "LANG" -> "en_US.UTF-8", "HOME" -> work, "TMPDIR" -> work,
      "XDG_CACHE_HOME" -> s"$work/.cache", "XDG_CONFIG_HOME" -> s"$work/.config", "XDG_DATA_HOME" -> s"$work/.local/share",
      "XDG_STATE_HOME" -> s"$work/.local/state", "XDG_RUNTIME_DIR" -> s"$work/.run") ++ extra

  /** The trees an interpreter needs inside a sandbox: its installation and every directory its symlinks pass through,
    * each kept at the path the interpreter uses (uv and virtual environments alias them).
    */
  def interpreterTrees(executable: Path, extra: Vector[Path] = Vector.empty): Vector[Path] =
    val start = executable.toAbsolutePath.normalize
    val hops = Iterator.iterate(Option(start)) {
      case Some(path) if Files.isSymbolicLink(path) => Some(path.getParent.resolve(Files.readSymbolicLink(path)).normalize)
      case _ => None
    }.takeWhile(_.isDefined).flatten.take(40).toVector
    (extra.map(_.toAbsolutePath.normalize) ++ hops.map(_.getParent.getParent) :+ start.toRealPath().getParent.getParent).distinct

  /** Run a process with a clean environment, bounded captured output and a deadline; `onTimeout` cleans up after a kill. */
  private[sandbox] def runProcess(argv: Vector[String], directory: Path, environment: Map[String, String], timeout: FiniteDuration,
      sandbox: String, onTimeout: () => Unit = () => ()): ConfinedResult =
    val builder = ProcessBuilder(argv.asJava).directory(directory.toFile)
    builder.environment().clear()
    builder.environment().putAll(environment.asJava)
    val process = builder.start()
    process.getOutputStream.close()
    val stdout = drain(process.getInputStream)
    val stderr = drain(process.getErrorStream)
    if process.waitFor(timeout.toMillis, TimeUnit.MILLISECONDS) then
      ConfinedResult.Completed(sandbox, process.exitValue(), stdout.text, stderr.text)
    else
      process.descendants().forEach(_.destroyForcibly())
      process.destroyForcibly()
      process.waitFor(5L, TimeUnit.SECONDS)
      onTimeout()
      ConfinedResult.TimedOut(sandbox, stdout.text, stderr.text)

  private final class Drained(thread: Thread, buffer: ByteArrayOutputStream):
    def text: String =
      thread.join(5000L)
      buffer.synchronized(String(buffer.toByteArray, StandardCharsets.UTF_8))

  private def drain(input: InputStream): Drained =
    val buffer = ByteArrayOutputStream()
    val thread = Thread(() =>
      val chunk = new Array[Byte](64 * 1024)
      var read = input.read(chunk)
      while read >= 0 do
        buffer.synchronized { if buffer.size() < MaxOutputBytes then buffer.write(chunk, 0, math.min(read, MaxOutputBytes - buffer.size())) }
        read = input.read(chunk)
    )
    thread.setDaemon(true)
    thread.start()
    Drained(thread, buffer)

  /** macOS sandbox-exec with a profile that denies the network, writes outside the work directory and reads outside
    * the system and listed trees.
    */
  private object MacSandboxExec extends Sandbox:
    val name = "sandbox-exec"
    private val executable = Path.of("/usr/bin/sandbox-exec")
    private val system = Vector("/System", "/usr", "/bin", "/sbin", "/dev", "/Library", "/opt/homebrew", "/private/preboot", "/private/var/db/dyld")

    def unavailable: Option[String] = Option.when(!Files.isRegularFile(executable))(s"$executable is missing (macOS only)")

    private[sandbox] def profile(work: Path, readable: Vector[Path]): String =
      def quoted(text: String) = io.circe.Json.fromString(text).noSpaces
      val roots = system ++ (work +: readable).map(_.toString)
      val parents = roots.flatMap(root => Iterator.iterate(Path.of(root).getParent)(_.getParent).takeWhile(_ != null).map(_.toString)).toSet ++
        Set("/var", "/etc", "/tmp", "/etc/localtime", "/private/etc/localtime")
      val allowed = roots.map(root => s"(subpath ${quoted(root)})").mkString(" ") + " " + parents.toVector.sorted.map(p => s"(literal ${quoted(p)})").mkString(" ")
      s"(version 1)(allow default)(deny network*)(deny file-read-data (require-not (require-any $allowed)))" +
        s"(deny file-write* (require-not (subpath ${quoted(work.toString)})))"

    def run(command: Vector[String], confinement: Confinement): ConfinedResult =
      val work = confinement.workDirectory.toAbsolutePath.normalize
      runProcess(Vector(executable.toString, "-p", profile(work, confinement.readable)) ++ command, work,
        confinedEnvironment(work, confinement.environment), confinement.timeout, name)

  /** A Linux container from an empty image: the host's system trees and listed trees mounted read-only, the work
    * directory read-write at the same path, no network, no capabilities, the caller's uid, bounded memory and pids.
    */
  private[sandbox] final case class Docker(docker: String = "docker", image: String = DockerImage, memory: String = "4g", pids: Int = 1024) extends Sandbox:
    val name = "docker"
    private val system = Vector("/usr", "/etc", "/bin", "/sbin", "/lib", "/lib32", "/lib64", "/libx32")

    def unavailable: Option[String] =
      if !Platform.isLinux then Some("mounts the host's system trees, so it needs a Linux host")
      else
        try
          val probe = runProcess(Vector(docker, "version", "--format", "{{.Server.Version}}"), Path.of("/"), sys.env, 30.seconds, name)
          Option.when(!probe.succeeded)(s"docker server unreachable: ${probe.stderr.trim.take(200)}")
        catch case e: java.io.IOException => Some(s"$docker cannot run: ${e.getMessage}")

    private def ensureImage(): Unit =
      val inspect = runProcess(Vector(docker, "image", "inspect", image), Path.of("/"), sys.env, 60.seconds, name)
      if !inspect.succeeded then
        val dockerfile = Files.createTempDirectory("vocab-sandbox-image-")
        try
          Files.writeString(dockerfile.resolve("Dockerfile"), "FROM scratch\n")
          val built = runProcess(Vector(docker, "build", "-q", "-t", image, dockerfile.toString), dockerfile, sys.env, 5.minutes, name)
          if !built.succeeded then throw SandboxUnavailable(s"cannot build $image: ${built.stderr.trim.take(300)}")
        finally
          Files.deleteIfExists(dockerfile.resolve("Dockerfile"))
          Files.deleteIfExists(dockerfile)

    /** (source, target, read-only) bind mounts: system and listed trees read-only, the work directory writable. */
    private[sandbox] def mounts(work: Path, readable: Vector[Path]): Vector[(Path, Path, Boolean)] =
      val trees = (system.map(Path.of(_)) ++ readable.map(_.toAbsolutePath.normalize)).distinct.filter(Files.exists(_))
      trees.map(path => (path.toRealPath(), path, true)) :+ (work.toRealPath(), work, false)

    private[sandbox] def command(container: String, command: Vector[String], confinement: Confinement): Vector[String] =
      val work = confinement.workDirectory.toAbsolutePath.normalize
      val user = com.sun.security.auth.module.UnixSystem()
      val options = Vector(docker, "run", "--rm", "--name", container, "--network", "none", "--read-only", "--cap-drop", "ALL",
        "--security-opt", "no-new-privileges", "--pids-limit", pids.toString, "--memory", memory,
        "--user", s"${user.getUid}:${user.getGid}", "--tmpfs", "/tmp:rw,exec,mode=1777,size=512m", "--workdir", work.toString)
      val binds = mounts(work, confinement.readable).flatMap((source, target, readOnly) =>
        Vector("--mount", s"type=bind,src=$source,dst=$target" + (if readOnly then ",readonly" else "")))
      val environment = confinedEnvironment(work, confinement.environment).toVector.sorted.flatMap((key, value) => Vector("--env", s"$key=$value"))
      options ++ binds ++ environment ++ (image +: command)

    def run(command: Vector[String], confinement: Confinement): ConfinedResult =
      ensureImage()
      val container = s"vocab-sandbox-${UUID.randomUUID().toString.take(12)}"
      runProcess(this.command(container, command, confinement), Path.of("/"), sys.env, confinement.timeout, name,
        () => runProcess(Vector(docker, "rm", "-f", container), Path.of("/"), sys.env, 60.seconds, name))
