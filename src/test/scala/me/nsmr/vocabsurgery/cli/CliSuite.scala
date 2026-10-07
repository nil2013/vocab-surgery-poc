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
      Vector("evaluate", "--manifest", "x", "--python", "p", "--suite", "s", "--runtime", "tensorfold", "--evaluation-out", "o", "--trials", "0")).foreach { args =>
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

  test("evaluate serves both phases, keeps a failed logits gate as exit 4 and refuses an existing evaluation directory") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      val run = output.resolve("runs/evaluate")
      val plan = CheckpointProcessor.plan(input, PolicyFile.default, run, options)
      CheckpointProcessor.applyPlan(plan, output.resolve("models/evaluate"), ProcessingFixtures.reference, options)
      val helpers = directory.resolve("helpers")
      Files.createDirectory(helpers)
      Files.writeString(helpers.resolve("check_tokenizer.py"), "import json,sys\nfrom pathlib import Path\nPath(sys.argv[sys.argv.index('--report')+1]).write_text(json.dumps({'passed':True}))\n")
      Files.writeString(helpers.resolve("tokenization.py"), "import json,sys\nfrom pathlib import Path\nout=Path(sys.argv[sys.argv.index('--out')+1])\n" +
        "out.write_text(json.dumps({'suite':[{'id':'ja-1','category':'japanese','baselineTokens':4,'prunedTokens':4,'ratio':1.0,'identicalProjectedIds':True}],'versions':{},'locate':[]}[sys.argv[1]]))\n")
      Files.writeString(helpers.resolve("logits.py"), "import json,sys\nfrom pathlib import Path\nout=Path(sys.argv[sys.argv.index('--output')+1])\nout.mkdir()\n" +
        "(out/'logits-summary.json').write_text(json.dumps({'passed':False}))\nsys.exit(1)\n")
      val suite = directory.resolve("suite.jsonl")
      Files.writeString(suite, """{"id":"ja-1","kind":"chat","category":"japanese","maxTokens":8,"messages":[{"role":"user","content":"こんにちは"}]}""" + "\n" +
        """{"id":"mix-1","kind":"chat","category":"contamination","maxTokens":8,"sampling":true,"messages":[{"role":"user","content":"x"}]}""" + "\n")
      val clone = me.nsmr.vocabsurgery.runtime.RuntimeFixtures.fakeClone(directory)
      val args = Vector("evaluate", "--manifest", run.resolve("manifest.json").toString, "--python", "python3", "--suite", suite.toString,
        "--evaluation-out", run.resolve("evaluation").toString, "--trials", "2", "--logits", "--runtime", "tensorfold",
        "--tensorfold-clone", clone.toString, "--port", me.nsmr.vocabsurgery.runtime.RuntimeFixtures.freePort().toString)
      val (code, text, error) = invoke(args, options, helpers)
      assertEquals(code, 4, error)
      val metrics = io.circe.jawn.parse(text).toOption.get.hcursor
      assertEquals(metrics.downField("logits").get[Boolean]("passed"), Right(false))
      assertEquals(metrics.downField("phases").downField("pruned").get[Int]("contaminationOutputs"), Right(2))
      assertEquals(metrics.downField("tokenization").get[Double]("median"), Right(1.0))
      assertEquals(metrics.get[Boolean]("tokenizationGate"), Right(true))
      Vector("baseline.jsonl", "pruned.jsonl", "tokenizer.jsonl", "environment.json", "metrics.json", "ratings-template.json", "REPORT.md",
        "servers/baseline/server.json", "servers/pruned/server.json").foreach(name => assert(Files.isRegularFile(run.resolve("evaluation").resolve(name)), name))
      assert(Files.readString(run.resolve("evaluation/servers/pruned/server.json")).contains("--prompt-cache-gib"))
      assertEquals(invoke(args, options, helpers)._1, 1)
      val ratings = directory.resolve("ratings.json")
      Files.writeString(ratings, """[{"id":"ja-1","baseline":4,"pruned":4,"majorBreakdown":false}]""")
      val (reviewed, review, _) = invoke(Vector("review", "--evaluation", run.resolve("evaluation").toString, "--ratings", ratings.toString,
        "--out", output.resolve("runs/evaluate/reviewed.json").toString), options)
      assertEquals(reviewed, 0)
      assertEquals(io.circe.jawn.parse(review).toOption.get.hcursor.downField("japaneseQuality").get[String]("state"), Right("reviewed"))
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

  test("draft-vocab rebuilds the clone's built-in list with the reference tokenization helper and refuses an existing output") {
    val root = Files.createTempDirectory("vocab-cli-draft-")
    try
      val source = Files.createDirectories(root.resolve("source"))
      Files.writeString(source.resolve("tokenizer.json"), """{"model":{"type":"BPE","vocab":{"a":0,"b":1,"c":2,"d":3},"merges":[]},""" +
        """"added_tokens":[{"id":4,"content":"<|im_end|>","special":true,"single_word":false,"lstrip":false,"rstrip":false,"normalized":false}]}""")
      val builtin = root.resolve("clone/src/tensorfold/families/qwen4_exp/cuda/draft_vocab.txt")
      Files.createDirectories(builtin.getParent)
      Files.writeString(builtin, "0\n1\n4\n")
      val helpers = Files.createDirectories(root.resolve("helpers"))
      Files.writeString(helpers.resolve("tokenization.py"), "import json,sys\nfrom pathlib import Path\na=sys.argv\n" +
        "texts=json.loads(Path(a[a.index('--texts')+1]).read_text())\nids=[[ord(c)-97 for c in t] for t in texts]\n" +
        "flat=[i for t in ids for i in t]\nout=sorted([[i,flat.count(i)] for i in set(flat)]) if a[1]=='count' else ids\n" +
        "Path(a[a.index('--out')+1]).write_text(json.dumps(out))\n")
      val corpus = root.resolve("corpus.jsonl")
      Files.writeString(corpus, "{\"text\": \"cca\"}\n")
      val profile = root.resolve("profile.json")
      Files.writeString(profile, "{}")
      val options = ProcessingOptions(outputRoot = root.resolve("outputs"))
      val args = Vector("draft-vocab", "--profile", profile.toString, "--corpus", corpus.toString, "--source", source.toString,
        "--python", "python3", "--tensorfold-clone", root.resolve("clone").toString, "--out", root.resolve("outputs/draft.txt").toString)
      val (code, text, error) = invoke(args, options, helpers)
      assertEquals(code, 0, error)
      assertEquals(Files.readString(root.resolve("outputs/draft.txt")), "0\n2\n4\n")
      assertEquals(io.circe.jawn.parse(text).toOption.get.hcursor.get[Int]("swapped"), Right(1))
      assertEquals(invoke(args, options, helpers)._1, 1)
    finally
      scala.util.Using.resource(Files.walk(root))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))
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
