package me.nsmr.vocabsurgery.evaluation

import java.nio.file.{Files, Path}
import me.nsmr.vocabsurgery.io.DataException

class OwnSuiteSuite extends munit.FunSuite:
  test("the fixed suite loads with its categories and rejects malformed cases") {
    val suite = QualitySuite.load(Path.of("evaluation/default-suite.jsonl"))
    assertEquals(suite.size, 100)
    assertEquals(suite.groupBy(_.category).view.mapValues(_.size).toMap,
      Map(CaseCategory.Japanese -> 20, CaseCategory.Coding -> 30, CaseCategory.Fim -> 10, CaseCategory.Tool -> 10, CaseCategory.Contamination -> 30))
    assertEquals(suite.flatMap(_.code).groupBy(_.language).view.mapValues(_.size).toMap,
      Map(CodeLanguage.Python -> 20, CodeLanguage.Scala -> 10, CodeLanguage.TypeScript -> 10))
    assert(suite.filter(_.category == CaseCategory.Fim).forall(c => c.code.exists(_.prefix.isDefined)))
    assertEquals(suite.count(_.benchmark), 6)
    val file = Files.createTempFile("suite-", ".jsonl")
    try
      Vector("""{"id":"a","kind":"chat","category":"japanese","maxTokens":9000,"messages":[{"role":"user","content":"x"}]}""",
        """{"id":"a","kind":"chat","category":"other","maxTokens":9,"messages":[{"role":"user","content":"x"}]}""",
        """{"id":"a","kind":"chat","category":"coding","maxTokens":9,"messages":[{"role":"user","content":"x"}],"language":"go","tests":[{"args":[],"expected":1}],"function":"f"}""",
        """{"id":"a","kind":"chat","category":"coding","maxTokens":9,"messages":[{"role":"user","content":"x"}],"language":"python","tests":[{"args":[],"expected":1}],"function":"1f"}""",
        "{\"id\":\"a\",\"kind\":\"completion\",\"category\":\"fim\",\"maxTokens\":9}\n",
        """{"id":"a","kind":"chat","category":"japanese","maxTokens":9,"messages":[{"role":"user","content":"x"}]}""" + "\n" +
          """{"id":"a","kind":"chat","category":"japanese","maxTokens":9,"messages":[{"role":"user","content":"x"}]}""").foreach { text =>
        Files.writeString(file, text)
        intercept[DataException](QualitySuite.load(file))
      }
    finally Files.deleteIfExists(file)
  }

  test("literals for the Python and Scala harnesses keep the JSON values") {
    val value = io.circe.jawn.parse("""[null, true, 1.5, "日\"本", [1], {"k": false}]""").toOption.get
    assertEquals(CodeChecks.pythonValue(value), "[None, True, 1.5, \"日\\\"本\", [1], {\"k\": False}]")
    assertEquals(CodeChecks.scalaValue(io.circe.jawn.parse("""[null, true, "日", [1, 2]]""").toOption.get), "Vector(None,true,\"\\u65e5\",Vector(1,2))")
    assertEquals(CodeChecks.extractCode("text\n```python\ndef f():\n  return 1\n```\nmore"), "def f():\n  return 1")
    assertEquals(CodeChecks.extractCode("  plain  "), "plain")
  }

  private lazy val sandbox: Option[me.nsmr.vocabsurgery.sandbox.Sandbox] =
    try Some(me.nsmr.vocabsurgery.sandbox.Sandbox.platform()) catch case _: me.nsmr.vocabsurgery.sandbox.SandboxUnavailable => None

  test("Python and Scala answers pass or fail their fixed tests in the sandbox") {
    assume(sandbox.isDefined, "no sandbox on this host")
    assume(Files.isDirectory(Path.of("target/cli/compiler")), "Scala checks need sbt stageCli")
    val tools = Toolchains.detect(Path.of("/usr/bin/python3"), Path.of("outputs"))
    val suite = QualitySuite.load(Path.of("evaluation/default-suite.jsonl"))
    val python = suite.find(_.id == "python-01").get.code.get
    assert(CodeChecks.check(python, "```python\ndef sum_even(xs):\n    return sum(x for x in xs if x % 2 == 0)\n```", tools, sandbox.get).passed)
    assert(!CodeChecks.check(python, "def sum_even(xs):\n    return sum(xs)", tools, sandbox.get).passed)
    val fim = suite.find(_.id == "fim-01").get.code.get
    assert(CodeChecks.check(fim, "    return sum(x for x in xs if x % 2 == 0)", tools, sandbox.get).passed)
    val scala = suite.find(_.id == "scala-01").get.code.get
    val right = CodeChecks.check(scala, "def sum_even(xs: Vector[Int]): Int = xs.filter(_ % 2 == 0).sum", tools, sandbox.get)
    assert(right.passed, right.detail)
    assert(!CodeChecks.check(scala, "def sum_even(xs: Vector[Int]): Int = xs.sum", tools, sandbox.get).passed)
    assertEquals(CodeChecks.check(scala, "def sum_even(xs: Vector[Int]): Int = oops", tools, sandbox.get).phase, "compile")
  }

  test("TypeScript answers compile and pass or fail their fixed tests in the sandbox") {
    assume(sandbox.isDefined, "no sandbox on this host")
    val tools = Toolchains.detect(Path.of("/usr/bin/python3"), Path.of("outputs"))
    assume(tools.node.isDefined && tools.tsc.isDefined, "TypeScript checks need fetch-reference node and typescript")
    val task = QualitySuite.load(Path.of("evaluation/default-suite.jsonl")).find(_.id == "typescript-01").get.code.get
    val right = CodeChecks.check(task, s"```ts\nfunction ${task.function}(xs: number[]): number { return xs.filter(x => x % 2 === 0).reduce((a, b) => a + b, 0); }\n```", tools, sandbox.get)
    assert(right.passed, right.detail)
    assert(!CodeChecks.check(task, s"function ${task.function}(xs: number[]): number { return 0; }", tools, sandbox.get).passed)
    assertEquals(CodeChecks.check(task, s"function ${task.function}(xs: number[]): number { return 'x'; }", tools, sandbox.get).phase, "compile")
  }

