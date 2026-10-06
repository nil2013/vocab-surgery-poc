package me.nsmr.vocabsurgery.cli

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.file.{Files, Path}
import me.nsmr.vocabsurgery.processing.{CheckpointProcessor, ProcessingFixtures, ProcessingOptions, PolicySettings}

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
      val plan = CheckpointProcessor.plan(input, Path.of("kanji-data"), PolicySettings(), run, options)
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
