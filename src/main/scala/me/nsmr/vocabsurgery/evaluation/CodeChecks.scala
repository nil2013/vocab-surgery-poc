package me.nsmr.vocabsurgery.evaluation

import io.circe.Json
import java.io.File
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.Diagnostics
import me.nsmr.vocabsurgery.sandbox.{ConfinedResult, Confinement, Sandbox}

/** Whether generated code passed its fixed tests, the phase that decided it, and the tail of its output. */
final case class CodeVerdict(passed: Boolean, phase: String, detail: String):
  def json: Json = Json.obj("passed" -> Json.fromBoolean(passed), "phase" -> Json.fromString(phase), "detail" -> Json.fromString(detail))

/** The interpreters and compilers that run generated code: Python, the Scala 3 compiler jars with a JDK, and Node with
  * TypeScript's compiler (from `fetch-reference node` and `fetch-reference typescript`). Every one is mounted read-only
  * into the sandbox.
  */
final case class Toolchains(python: Path, scalaCompiler: Path, java: Path, node: Option[Path] = None, tsc: Option[Path] = None):
  def javaHome: Path = java.toRealPath().getParent.getParent
  /** Every path made absolute (a bare name stays, to be found on PATH): commands run in their own directories. */
  def absolute: Toolchains =
    def abs(path: Path) = if path.getNameCount == 1 && !path.isAbsolute then path else path.toAbsolutePath.normalize
    Toolchains(abs(python), abs(scalaCompiler), abs(java), node.map(abs), tsc.map(abs))

object Toolchains:
  /** The JDK running this JVM, the staged compiler jars, and Node and tsc fetched under the output root, when present. */
  def detect(python: Path, outputRoot: Path, scalaCompiler: Path = Path.of("target/cli/compiler")): Toolchains =
    import me.nsmr.vocabsurgery.processing.{NodeSource, ReferenceFetch, TypeScriptSource}
    val java = Path.of(System.getProperty("java.home")).resolve("bin/java")
    val node = Some(NodeSource.executable(ReferenceFetch.directory(outputRoot, NodeSource, NodeSource.defaultVersion), NodeSource.defaultVersion)).filter(Files.isExecutable(_))
    val tsc = Some(TypeScriptSource.compiler(ReferenceFetch.directory(outputRoot, TypeScriptSource, TypeScriptSource.defaultVersion))).filter(Files.isRegularFile(_))
    Toolchains(python, scalaCompiler, java, node, tsc)

/** Fixed function tests for generated code, run in disposable directories through a sandbox. */
object CodeChecks:
  private val Fence = "(?s)```[^\\n]*\\n(.*?)```".r
  private val Pass = "VOCAB_TEST_PASS"

  /** The first fenced code block, or the whole text. */
  def extractCode(text: String): String = Fence.findFirstMatchIn(text).fold(text.trim)(_.group(1).trim)

  /** JSON as a Python literal with the same value. */
  def pythonValue(value: Json): String = value.fold("None", b => if b then "True" else "False", _.toString, s => Json.fromString(s).noSpaces,
    items => items.map(pythonValue).mkString("[", ", ", "]"), fields => fields.toVector.map((k, v) => s"${Json.fromString(k).noSpaces}: ${pythonValue(v)}").mkString("{", ", ", "}"))

  /** JSON as a Scala literal: lists are Vectors, null is None, text keeps ASCII escapes. */
  def scalaValue(value: Json): String =
    if value.isNull then "None"
    else value.asBoolean.map(_.toString).orElse(value.asArray.map(_.map(scalaValue).mkString("Vector(", ",", ")")))
      .getOrElse(asciiJson(value))

  private def asciiJson(value: Json): String =
    value.noSpaces.flatMap(c => if c < 128 then c.toString else f"\\u${c.toInt}%04x")

  def check(task: CodeTask, text: String, toolchains: Toolchains, sandbox: Sandbox): CodeVerdict =
    val code = (task.prefix, task.suffix) match
      case (Some(prefix), Some(suffix)) => prefix + text + suffix
      case _ => extractCode(text)
    val directory = Files.createTempDirectory("vocab-code-").toRealPath()
    try run(task, code, directory, toolchains.absolute, sandbox)
    catch case e: java.io.IOException => CodeVerdict(false, "execute", e.getMessage)
    finally Using.resource(Files.walk(directory))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))

  private def outcome(result: ConfinedResult): CodeVerdict = result match
    case ConfinedResult.Completed(_, code, out, err) => CodeVerdict(code == 0 && out.contains(Pass), "execute", (out + err).takeRight(4000))
    case ConfinedResult.TimedOut(_, out, err) => CodeVerdict(false, "execute", ("timed out\n" + out + err).takeRight(4000))

  private def compileFailure(result: ConfinedResult): Option[CodeVerdict] = result match
    case ConfinedResult.Completed(_, 0, _, _) => None
    case ConfinedResult.Completed(_, _, out, err) => Some(CodeVerdict(false, "compile", (out + err).takeRight(4000)))
    case ConfinedResult.TimedOut(_, out, err) => Some(CodeVerdict(false, "compile", ("timed out\n" + out + err).takeRight(4000)))

  private def run(task: CodeTask, code: String, directory: Path, tools: Toolchains, sandbox: Sandbox): CodeVerdict =
    def confined(command: Vector[String], readable: Vector[Path], timeout: FiniteDuration = 20.seconds) =
      sandbox.run(command, Confinement(directory, readable, timeout = timeout))
    task.language match
      case CodeLanguage.Python =>
        val harness = task.tests.map(t => s"assert ${task.function}(*${pythonValue(Json.fromValues(t.args))}) == ${pythonValue(t.expected)}").mkString("\n")
        val script = directory.resolve("case.py")
        Files.writeString(script, s"$code\n$harness\nprint(\"$Pass\")\n")
        outcome(confined(Vector(tools.python.toString, "-I", script.toString), Sandbox.interpreterTrees(tools.python)))
      case CodeLanguage.Scala =>
        val optional = task.scalaReturnType.exists(_.startsWith("Option["))
        val asserts = task.tests.map { t =>
          val expected = if optional && !t.expected.isNull then s"Some(${scalaValue(t.expected)})" else scalaValue(t.expected)
          s"assert(${task.function}(${t.args.map(scalaValue).mkString(",")}) == $expected)"
        }
        val script = directory.resolve("Runner.scala")
        Files.writeString(script, s"object Runner {\n$code\ndef main(args: Array[String]): Unit = {\n${asserts.mkString("\n")}\nprintln(\"$Pass\")\n}\n}\n")
        val compiler = tools.scalaCompiler.toRealPath()
        val classpath = compiler.resolve("*").toString
        val readable = Vector(compiler, tools.javaHome)
        compileFailure(confined(Vector(tools.java.toString, "-Xmx384m", s"-Djava.io.tmpdir=$directory", "-cp", classpath, "dotty.tools.dotc.Main",
          "-classpath", classpath, "-d", directory.toString, script.toString), readable, 30.seconds)).getOrElse(
          outcome(confined(Vector(tools.java.toString, "-Xmx128m", "-cp", directory.toString + File.pathSeparator + classpath, "Runner"), readable)))
      case CodeLanguage.TypeScript =>
        val node = tools.node.getOrElse(Diagnostics.invalid(Path.of("node"), "toolchain", "TypeScript checks need Node; run fetch-reference node"))
        val tsc = tools.tsc.getOrElse(Diagnostics.invalid(Path.of("typescript"), "toolchain", "TypeScript checks need tsc; run fetch-reference typescript"))
        val script = directory.resolve("case.ts")
        Files.writeString(script, code)
        val readable = Vector(node.getParent.getParent, tsc.getParent.getParent)
        compileFailure(confined(Vector(node.toString, tsc.toString, "--strict", "--target", "es2022", "--module", "nodenext", "--moduleResolution",
          "nodenext", "--outDir", directory.toString, script.toString), readable, 30.seconds)).getOrElse {
          val checks = task.tests.map { t =>
            val call = Json.fromString(s"JSON.stringify(${task.function}(...${Json.fromValues(t.args).noSpaces}))").noSpaces
            s"if(vm.runInContext($call,context,{timeout:1000})!==${Json.fromString(t.expected.noSpaces).noSpaces})throw new Error('test failed');"
          }
          Files.writeString(directory.resolve("runner.cjs"), "const vm=require('node:vm'), fs=require('node:fs');\n" +
            "const context=vm.createContext({exports:{}},{codeGeneration:{strings:false,wasm:false}});\n" +
            "vm.runInContext(fs.readFileSync('case.js','utf8'),context,{timeout:1000});\n" + checks.mkString("\n") + s"\nconsole.log(\"$Pass\");\n")
          outcome(confined(Vector(node.toString, "--max-old-space-size=128", "--permission", s"--allow-fs-read=$directory", directory.resolve("runner.cjs").toString),
            Vector(node.getParent.getParent)))
        }
