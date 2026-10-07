package me.nsmr.vocabsurgery.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import me.nsmr.vocabsurgery.processing.{CheckpointProcessor, ProcessingFixtures, ProcessingOptions, PolicyFile}

class CliSuite extends munit.FunSuite:
  private def invoke(args: Vector[String], options: ProcessingOptions = ProcessingOptions(), helpers: Path = Path.of("scripts")): (Int, String, String) =
    val stdout = new ByteArrayOutputStream()
    val stderr = new ByteArrayOutputStream()
    val code = Main.execute(args, options, helpers, out = new PrintStream(stdout), err = new PrintStream(stderr))
    (code, stdout.toString("UTF-8"), stderr.toString("UTF-8"))

  test("top-level and command help return success without running operations") {
    Vector(Vector.empty, Vector("--help"), Vector("help"), Vector("help", "apply"), Vector("evaluate", "--help")).foreach { args =>
      val (code, text, _) = invoke(args)
      assertEquals(code, 0)
      assert(text.contains("Usage: vocab-surgery"))
    }
  }

  test("unknown commands, missing options and invalid counts fail with argument exit code") {
    Vector(Vector("oops"), Vector("plan"), Vector("apply", "--plan", "x", "--model-out", "y"), Vector("inspect", "--wat"),
      Vector("evaluate", "--manifest", "x", "--python", "p", "--suite", "s", "--omlx-url", "http://localhost", "--baseline-model", "b",
        "--pruned-model", "p", "--evaluation-out", "o", "--trials", "0")).foreach { args =>
      assertEquals(invoke(args)._1, 2)
    }
  }

  test("inspect and plan dispatch to real operations; processing and verification failures have distinct codes") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      val run = output.resolve("runs/cli")
      assertEquals(invoke(Vector("inspect", "--source", input.toString, "--run-dir", run.toString), options)._1, 0)
      assert(Files.isRegularFile(run.resolve("inspection.json")))
      assertEquals(invoke(Vector("inspect", "--source", input.toString, "--run-dir", run.toString), options)._1, 1)
      assertEquals(invoke(Vector("plan", "--source", input.toString, "--run-dir", run.toString), options)._1, 0)
      assert(Files.isRegularFile(run.resolve("plan.json")))
      assertEquals(invoke(Vector("plan", "--source", input.toString, "--run-dir", run.toString), options)._1, 1)
      val planned = output.resolve("runs/planned")
      assertEquals(invoke(Vector("plan", "--source", input.toString, "--run-dir", planned.toString), options)._1, 0)
      assert(Files.isRegularFile(planned.resolve("plan.json")))
      assertEquals(invoke(Vector("verify", "--manifest", "missing.json", "--python", "python3"), options)._1, 3)
    }
  }

  test("missing helper and child-process failures remain visible") {
    intercept[java.io.IOException](ExternalTools.run(Path.of("python3"), Path.of("missing-script.py"), Vector.empty, Path.of("unused.log"), 1))
    ProcessingFixtures.withModel { (directory, _, _) =>
      val helper = directory.resolve("fail.py")
      Files.writeString(helper, "import sys\nsys.stderr.write('expected child failure')\nsys.exit(7)\n")
      val failure = intercept[java.io.IOException](ExternalTools.run(Path.of("python3"), helper, Vector.empty, directory.resolve("failure.log"), 5))
      assert(failure.getMessage.contains("exit 7"))
      Files.writeString(helper, "import time\ntime.sleep(60)\n")
      val timeout = intercept[java.io.IOException](ExternalTools.run(Path.of("python3"), helper, Vector.empty, directory.resolve("timeout.log"), 1))
      assert(timeout.getMessage.contains("exceeded 1"))
    }
  }

  test("evaluate preserves a failed numeric gate as exit 4 and refuses an existing evaluation directory") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      val run = output.resolve("runs/evaluate")
      val plan = CheckpointProcessor.plan(input, PolicyFile.default, run, options)
      CheckpointProcessor.applyPlan(plan, output.resolve("models/evaluate"), ProcessingFixtures.reference, options)
      val helpers = directory.resolve("helpers")
      Files.createDirectory(helpers)
      Files.writeString(helpers.resolve("check_tokenizer.py"), "import json,sys\nfrom pathlib import Path\nPath(sys.argv[sys.argv.index('--report')+1]).write_text(json.dumps({'passed':True}))\n")
      Files.writeString(helpers.resolve("evaluate.py"), "import json,sys\nfrom pathlib import Path\n(Path(sys.argv[sys.argv.index('--output')+1])/'metrics.json').write_text(json.dumps({'logits':{'passed':False}}))\n")
      val suite = directory.resolve("suite.jsonl")
      Files.writeString(suite, "{}\n")
      val args = Vector("evaluate", "--manifest", run.resolve("manifest.json").toString, "--python", "python3", "--suite", suite.toString,
        "--omlx-url", "http://127.0.0.1:8302", "--baseline-model", "baseline", "--pruned-model", "pruned",
        "--evaluation-out", run.resolve("evaluation").toString)
      val (code, text, _) = invoke(args, options, helpers)
      assertEquals(code, 4)
      assert(text.contains("\"passed\":false"))
      assertEquals(invoke(args, options, helpers)._1, 1)
      assert(Files.isRegularFile(run.resolve("evaluation/forbidden-codepoints.json")))
    }
  }

  test("translate-ids writes kept source IDs as published IDs and reports the pruned ones") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      val run = output.resolve("runs/translate")
      val plan = CheckpointProcessor.plan(input, PolicyFile.default, run, options)
      CheckpointProcessor.applyPlan(plan, output.resolve("models/translate"), ProcessingFixtures.reference, options)
      val projection = me.nsmr.vocabsurgery.processing.PlanStore.loadVerified(plan).domain.projection
      val deleted = (0 until projection.originalSize).find(i => projection.compact(me.nsmr.vocabsurgery.core.OriginalTokenId(i)).isEmpty).get
      val kept = projection.compactToOriginal.takeRight(2).map(_.intValue)
      val ids = directory.resolve("ids.txt")
      Files.writeString(ids, s"${kept(1)}\n$deleted ${kept(0)}\n${kept(1)}\n")
      val args = Vector("translate-ids", "--manifest", run.resolve("manifest.json").toString, "--ids", ids.toString, "--out", output.resolve("draft/ids.txt").toString)
      val (code, text, _) = invoke(args, options)
      assertEquals(code, 0)
      assert(text.contains(s"\"dropped\":[$deleted]"))
      assertEquals(Files.readString(output.resolve("draft/ids.txt")), s"${projection.compactSize - 2}\n${projection.compactSize - 1}\n")
      assertEquals(invoke(args, options)._1, 1)
      Files.writeString(ids, s"${projection.originalSize}\n")
      assertEquals(invoke(args.updated(6, output.resolve("draft/other.txt").toString), options)._1, 1)
    }
  }

  test("sandbox-check reports every implementation; sandbox-run confines a command or refuses without a sandbox") {
    val (code, text, _) = invoke(Vector("sandbox-check"))
    assertEquals(code, 0)
    val report = io.circe.jawn.parse(text).toOption.get
    assertEquals(report.hcursor.downField("implementations").as[Vector[io.circe.Json]].map(_.size), Right(2))
    assertEquals(invoke(Vector("sandbox-run", "--work-dir", ".", "--sandbox", "none", "true"))._1, 2)
    assertEquals(invoke(Vector("sandbox-run", "--work-dir", "."))._1, 2)
    val directory = Files.createTempDirectory("vocab-cli-sandbox-")
    try
      val chosen = report.hcursor.get[String]("chosen").toOption
      val run = Vector("sandbox-run", "--work-dir", directory.toString, "--env", "GREETING=hi", "--", "sh", "-c", "echo $GREETING > out.txt; exit 3")
      val (exit, output, _) = invoke(run)
      chosen match
        case Some(name) =>
          assertEquals(exit, 5)
          val result = io.circe.jawn.parse(output).toOption.get.hcursor
          assertEquals((result.get[String]("sandbox"), result.get[Int]("exitCode")), (Right(name), Right(3)))
          assertEquals(Files.readString(directory.resolve("out.txt")).trim, "hi")
        case None => assertEquals(exit, 1)
    finally
      Files.deleteIfExists(directory.resolve("out.txt"))
      Files.deleteIfExists(directory)
  }

  test("serve runs a command against the endpoint once ready, stops the server and passes on a failure") {
    val root = Files.createTempDirectory("vocab-cli-serve-")
    try
      val clone = me.nsmr.vocabsurgery.runtime.RuntimeFixtures.fakeClone(root)
      val checkpoint = Files.createDirectories(root.resolve("checkpoint"))
      val options = ProcessingOptions(outputRoot = root.resolve("outputs"))
      def serve(name: String, script: String) = invoke(Vector("serve", "--runtime", "tensorfold", "--tensorfold-clone", clone.toString,
        "--model", s"fn=$checkpoint", "--server-dir", root.resolve(s"outputs/servers/$name").toString,
        "--port", me.nsmr.vocabsurgery.runtime.RuntimeFixtures.freePort().toString, "--", "sh", "-c", script), options)
      val (code, output, _) = serve("ok", s"echo \"$$VOCAB_ENDPOINT $$VOCAB_MODELS\" > ${root.resolve("seen.txt")}")
      assertEquals(code, 0)
      assert(Files.readString(root.resolve("seen.txt")).trim.matches("http://127\\.0\\.0\\.1:\\d+/v1 fn"))
      assertEquals(io.circe.jawn.parse(output).toOption.get.hcursor.get[Int]("exitCode"), Right(0))
      assertEquals(serve("failing", "exit 3")._1, 5)
      assertEquals(invoke(Vector("serve", "--runtime", "vllm", "--model", "a=b", "--server-dir", "x"))._1, 2)
    finally
      scala.util.Using.resource(Files.walk(root))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))
  }

  test("measure-speed serves, measures both draft modes and attributes the runtime's draft statistics; run-external records its run") {
    val root = Files.createTempDirectory("vocab-cli-measure-")
    try
      val clone = me.nsmr.vocabsurgery.runtime.RuntimeFixtures.fakeClone(root)
      val checkpoint = Files.createDirectories(root.resolve("checkpoint"))
      val probes = root.resolve("probes.json")
      Files.writeString(probes, """{"trials": 2, "warmups": 1, "maxTokens": 8, "temperature": 0, "prompts": [{"name": "a", "text": "hi"}]}""")
      val options = ProcessingOptions(outputRoot = root.resolve("outputs"))
      def serving(name: String) = Vector("--runtime", "tensorfold", "--tensorfold-clone", clone.toString, "--model", s"fn=$checkpoint",
        "--server-dir", root.resolve(s"outputs/servers/$name").toString, "--port", me.nsmr.vocabsurgery.runtime.RuntimeFixtures.freePort().toString)
      val (code, output, error) = invoke(Vector("measure-speed") ++ serving("speed") ++ Vector("--probes", probes.toString, "--out", root.resolve("outputs/speed.json").toString), options)
      assertEquals(code, 0, error)
      val result = me.nsmr.vocabsurgery.io.JsonIO.read(root.resolve("outputs/speed.json")).hcursor
      assertEquals(result.downField("rows").as[Vector[io.circe.Json]].map(_.size), Right(4))
      assertEquals(result.downField("drafts").downField("a/mtp").get[Int]("accepted"), Right(2))
      assert(result.downField("drafts").downField("a/serial").failed)
      assertEquals(result.downField("summary").downField("a/serial").downField("completionTokens").as[Vector[Int]], Right(Vector(2)))
      assert(io.circe.jawn.parse(output).toOption.get.hcursor.downField("rows").failed)

      val material = Files.createDirectories(root.resolve("material"))
      Files.writeString(material.resolve("README.md"), "material\n")
      val (externalCode, externalOutput, externalError) = invoke(Vector("run-external") ++ serving("external") ++ Vector("--material", material.toString,
        "--copy-to", root.resolve("outputs/runs/material").toString, "--", "sh", "-c", "echo $VOCAB_MODELS > seen.txt"), options)
      assertEquals(externalCode, 0, externalError)
      assertEquals(Files.readString(root.resolve("outputs/runs/material/seen.txt")).trim, "fn")
      assert(!Files.exists(material.resolve("seen.txt")))
      assert(Files.isRegularFile(root.resolve("outputs/servers/external/external-run.json")))
      assertEquals(io.circe.jawn.parse(externalOutput).toOption.get.hcursor.get[Int]("exitCode"), Right(0))
    finally
      scala.util.Using.resource(Files.walk(root))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))
  }
