package me.nsmr.vocabsurgery.sandbox

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using

class SandboxSuite extends munit.FunSuite:
  private final class Fake(val name: String, reason: Option[String]) extends Sandbox:
    var probed = false
    def unavailable: Option[String] = { probed = true; reason }
    def run(command: Vector[String], confinement: Confinement): ConfinedResult = fail("not run")

  private def withDirectory[A](body: Path => A): A =
    val directory = Files.createTempDirectory("vocab-sandbox-test-")
    try body(directory)
    finally Using.resource(Files.walk(directory))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))

  private lazy val available: Option[Sandbox] =
    try Some(Sandbox.platform(SandboxChoice.Auto)) catch case _: SandboxUnavailable => None

  test("the platform choice takes the first available implementation and never runs unconfined") {
    val docker = Fake("docker", None)
    val exec = Fake("sandbox-exec", Some("missing"))
    val all = Map("docker" -> docker, "sandbox-exec" -> exec)
    assertEquals(Sandbox.select(SandboxChoice.Auto, all, Vector("sandbox-exec", "docker")).name, "docker")
    assert(exec.probed)
    intercept[SandboxUnavailable](Sandbox.select(SandboxChoice.SandboxExec, all, Vector("docker")))
    intercept[SandboxUnavailable](Sandbox.select(SandboxChoice.Auto, Map("docker" -> Fake("docker", Some("no server"))), Vector("docker")))
    intercept[SandboxUnavailable](Sandbox.select(SandboxChoice.Auto, all, Vector.empty))
    val untouched = Fake("docker", None)
    Sandbox.select(SandboxChoice.Auto, Map("sandbox-exec" -> Fake("sandbox-exec", None), "docker" -> untouched), Vector("sandbox-exec", "docker"))
    assert(!untouched.probed, "later implementations are not probed once one is available")
    intercept[IllegalArgumentException](SandboxChoice.parse("none"))
  }

  test("the docker sandbox mounts only the work directory writable, drops the network and keeps the command last") {
    withDirectory { directory =>
      val confinement = Confinement(directory, Vector(directory.resolve("missing"), Path.of("/usr")), Map("A" -> "b"))
      val argv = Sandbox.Docker().command("n", Vector("python3", "-c", "1"), confinement)
      val joined = argv.mkString(" ")
      Vector("--network none", "--read-only", "--cap-drop ALL", "--security-opt no-new-privileges").foreach(flag => assert(joined.contains(flag), flag))
      val writable = argv.filter(arg => arg.startsWith("type=bind") && !arg.endsWith(",readonly"))
      assertEquals(writable, Vector(s"type=bind,src=${directory.toRealPath()},dst=${directory.toAbsolutePath.normalize}"))
      assert(!joined.contains("missing"))
      assert(argv.contains(s"HOME=${directory.toAbsolutePath.normalize}"))
      assert(argv.contains("A=b"))
      assertEquals(argv.takeRight(4), Vector(Sandbox.DockerImage, "python3", "-c", "1"))
    }
  }

  test("interpreter trees keep every directory a symlinked interpreter passes through") {
    withDirectory { directory =>
      val real = Files.createDirectories(directory.resolve("cpython-3.13.13/bin"))
      Files.writeString(real.resolve("python3.13"), "")
      Files.createSymbolicLink(directory.resolve("cpython-3.13"), directory.resolve("cpython-3.13.13"))
      val venv = Files.createDirectories(directory.resolve("venv/bin"))
      Files.createSymbolicLink(venv.resolve("python"), directory.resolve("cpython-3.13/bin/python3.13"))
      val trees = Sandbox.interpreterTrees(venv.resolve("python"))
      assertEquals(trees.toSet, Set(directory.resolve("venv"), directory.resolve("cpython-3.13"), directory.toRealPath().resolve("cpython-3.13.13")))
    }
  }

  test("the environment puts home, scratch and XDG directories in the work directory") {
    val environment = Sandbox.confinedEnvironment(Path.of("/w"), Map("PATH" -> "/x"))
    assertEquals(environment("HOME"), "/w")
    assertEquals(environment("XDG_CACHE_HOME"), "/w/.cache")
    assertEquals(environment("PATH"), "/x")
  }

  test("a confined command has no network and writes to the host only in its work directory") {
    assume(available.isDefined, "no sandbox on this host")
    withDirectory { parent =>
      val directory = Files.createDirectory(parent.resolve("work"))
      val outside = parent.resolve("outside.txt")
      val script = directory.resolve("probe.py")
      Files.writeString(script, "import socket, pathlib\n" +
        "try:\n    socket.create_connection(('1.1.1.1', 80), timeout=3); print('NETWORK')\nexcept OSError:\n    print('no-network')\n" +
        s"try:\n    pathlib.Path(${io.circe.Json.fromString(outside.toString).noSpaces}).write_text('x'); print('WROTE')\nexcept OSError:\n    print('no-write')\n" +
        "pathlib.Path('inside.txt').write_text('ok'); print('inside')\n")
      val result = available.get.run(Vector("python3", "-I", script.toString), Confinement(directory, Vector(parent), timeout = 60.seconds))
      assertEquals(result.stdout.split("\\s+").toVector, Vector("no-network", "no-write", "inside"), result.stderr)
      assert(result.succeeded)
      assert(!Files.exists(outside))
      assertEquals(Files.readString(directory.resolve("inside.txt")), "ok")
    }
  }

  test("a confined command is stopped at its deadline") {
    assume(available.isDefined, "no sandbox on this host")
    withDirectory { directory =>
      val result = available.get.run(Vector("sleep", "30"), Confinement(directory, timeout = 3.seconds))
      assert(result.isInstanceOf[ConfinedResult.TimedOut], result.toString)
    }
  }
