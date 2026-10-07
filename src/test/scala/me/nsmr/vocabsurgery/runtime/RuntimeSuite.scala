package me.nsmr.vocabsurgery.runtime

import java.net.{InetAddress, ServerSocket}
import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.{DataException, JsonIO}
import me.nsmr.vocabsurgery.processing.ProcessingOptions

class RuntimeSuite extends munit.FunSuite:
  private def withDirectory[A](body: Path => A): A =
    val directory = Files.createTempDirectory("vocab-runtime-test-")
    try body(directory)
    finally Using.resource(Files.walk(directory))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))

  test("a runtime serves until closed, records what it started and refuses a busy port") {
    withDirectory { root =>
      val clone = RuntimeFixtures.fakeClone(root)
      val draft = root.resolve("draft.txt")
      Files.writeString(draft, "0\n1\n")
      val checkpoint = Files.createDirectories(root.resolve("checkpoint"))
      val port = RuntimeFixtures.freePort()
      val options = ProcessingOptions(outputRoot = root.resolve("outputs"))
      val runtime = TensorFoldClone(clone, draftVocabulary = Some(draft))
      val server = runtime.serve(ServeRequest(Vector(ServedModel("fn", checkpoint)), root.resolve("outputs/servers/a"), port, 60.seconds), options)
      try
        assert(ServerProcess.serving(server.endpoint))
        assertEquals(server.endpoint.base.toString, s"http://127.0.0.1:$port/v1")
        val metadata = JsonIO.read(root.resolve("outputs/servers/a/server.json")).hcursor
        assertEquals(metadata.get[String]("runtime"), Right("tensorfold"))
        assertEquals(metadata.downField("draftVocabulary").get[String]("sha256").map(_.length), Right(64))
        assertEquals(metadata.get[String]("cloneRevision").map(_.length), Right(40))
        intercept[DataException](runtime.serve(ServeRequest(Vector(ServedModel("fn", checkpoint)), root.resolve("outputs/servers/b"), port, 10.seconds), options))
      finally server.close()
      assert(!server.alive)
      assert(!ServerProcess.serving(server.endpoint))
    }
  }

  test("a runtime that exits before serving is reported with its log") {
    withDirectory { root =>
      val error = intercept[DataException](TensorFoldClone(RuntimeFixtures.fakeClone(root, fails = true)).serve(
        ServeRequest(Vector(ServedModel("fn", Files.createDirectories(root.resolve("checkpoint")))), root.resolve("outputs/servers/a"), RuntimeFixtures.freePort(), 30.seconds),
        ProcessingOptions(outputRoot = root.resolve("outputs"))))
      assert(error.getMessage.contains("CUDA out of memory"), error.getMessage)
    }
  }

  test("draft statistics come from the runtime's per-request log lines") {
    withDirectory { root =>
      val log = root.resolve("server.log")
      Files.writeString(log, "[tensorfold] loading\n" +
        "[tensorfold] done req-1 prompt=39 cached=0 thinking=False tokens=400 sha=0 finish=length tok/s=60.1 ttft=0.06s prefill=0.06s rounds=161 accepted=239/362\n" +
        "[tensorfold] done req-2 prompt=39 cached=0 thinking=False tokens=400 sha=0 finish=length tok/s=34.4 ttft=0.16s prefill=0.16s rounds=399 accepted=0/0\n")
      assertEquals(TensorFoldClone(root).draftRecords(log), Vector(DraftRecord(39, 400, 60.1, 161, 239, 362), DraftRecord(39, 400, 34.4, 399, 0, 0)))
      assertEquals(TensorFoldClone(root).draftField(false), "draft" -> io.circe.Json.False)
    }
  }

  test("runtime commands keep the launch options the comparisons were run with") {
    // The options scripts/serve-evaluation.py and scripts/flash-next/serve.sh used before this runtime replaced them
    assertEquals(OMlx(Path.of("/py")).command(Path.of("/s"), 8302), Vector("/py", "-m", "omlx.cli", "serve", "--model-dir", "/s/models",
      "--base-path", "/s/data", "--host", "127.0.0.1", "--port", "8302", "--no-cache", "--no-hf-cache", "--max-concurrent-requests", "1"))
    assertEquals(TensorFoldClone(Path.of("/c")).command(ServedModel("fn", Path.of("/m")), 18080), Vector("/c/.venv/bin/tensorfold", "serve", "/m",
      "--host", "127.0.0.1", "--port", "18080", "--name", "fn", "--ple-on-ssd", "--context", "65536"))
    assertEquals(TensorFoldClone(Path.of("/c"), draftVocabulary = Some(Path.of("/d.txt"))).command(ServedModel("fn", Path.of("/m")), 1).takeRight(2),
      Vector("--draft-vocab", "/d.txt"))
    assertEquals(TensorFoldClone(Path.of("/c"), promptCache = false).command(ServedModel("fn", Path.of("/m")), 1).takeRight(2), Vector("--prompt-cache-gib", "0"))
    assertEquals(TensorFoldClone(Path.of("/c"), cudaHome = Path.of("/cuda")).environment("TORCH_EXTENSIONS_DIR"), "/c/.torch-extensions")
    assert(TensorFoldClone(Path.of("/c"), cudaHome = Path.of("/cuda")).environment("PATH").startsWith("/cuda/bin:"))
    intercept[IllegalArgumentException](ServedModel("a b", Path.of("/m")))
    val relative = TensorFoldClone(Path.of("outputs/clone")).command(ServedModel("fn", Path.of("models/x")), 1)
    assertEquals(relative.take(3), Vector(Path.of("outputs/clone/.venv/bin/tensorfold").toAbsolutePath.toString, "serve", Path.of("models/x").toAbsolutePath.toString))
    assertEquals(OMlx(Path.of("python3")).command(Path.of("/s"), 1).head, "python3")
    assertEquals(OMlx(Path.of(".venv/bin/python")).command(Path.of("/s"), 1).head, Path.of(".venv/bin/python").toAbsolutePath.toString)
  }
