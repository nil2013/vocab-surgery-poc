package me.nsmr.vocabsurgery.evaluation

import io.circe.Json
import java.nio.file.{Files, Path}
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO}

/** What a case measures; the comparison gates and the contamination rate are computed per category. */
enum CaseCategory(val name: String):
  case Japanese extends CaseCategory("japanese")
  case Coding extends CaseCategory("coding")
  case Fim extends CaseCategory("fim")
  case Tool extends CaseCategory("tool")
  case Contamination extends CaseCategory("contamination")

enum CodeLanguage(val name: String):
  case Python extends CodeLanguage("python")
  case Scala extends CodeLanguage("scala")
  case TypeScript extends CodeLanguage("typescript")

/** One fixed function test: the arguments and the expected value, as JSON. */
final case class FunctionTest(args: Vector[Json], expected: Json)

/** A generated function checked by fixed tests. A fill-in-the-middle case wraps the generated middle in `prefix` and `suffix`. */
final case class CodeTask(language: CodeLanguage, function: String, tests: Vector[FunctionTest], scalaReturnType: Option[String],
    prefix: Option[String] = None, suffix: Option[String] = None)

/** The tool call a tool case expects, and the result returned to the model for its follow-up answer. */
final case class ExpectedTool(name: String, arguments: Json, result: Json)

/** One case of this project's fixed quality suite (evaluation/default-suite.jsonl). `raw` is the line as read. */
final case class SuiteCase(id: String, category: CaseCategory, input: ChatInput, maxTokens: Int, sampling: Boolean, benchmark: Boolean,
    benchmarkLength: String, code: Option[CodeTask], expectedTool: Option[ExpectedTool], raw: Json):
  def messages: Vector[Json] = input match
    case ChatInput.Messages(messages, _) => messages
    case ChatInput.Completion(_) => Vector.empty

object QualitySuite:
  private val MaxBytes = 16L * 1024 * 1024
  private val Identifier = "[A-Za-z_][A-Za-z_0-9]*".r

  /** Read and validate a suite: unique non-empty ids, known kinds and categories, 1..8192 tokens, chat messages or a
    * completion prompt, and for code cases a supported language, an identifier function and tests with arguments and
    * an expected value.
    */
  def load(path: Path): Vector[SuiteCase] = Diagnostics.guard(path, "suite") {
    Diagnostics.check(Files.size(path) <= MaxBytes, path, "suite", "suite exceeds 16 MiB")
    val parser = io.circe.jawn.JawnParser(allowDuplicateKeys = false)
    val cases = FileIO.readUtf8(path).linesIterator.zipWithIndex.filter(_._1.trim.nonEmpty).map { (line, index) =>
      val at = s"line ${index + 1}"
      parse(parser.parse(line).fold(e => Diagnostics.invalid(path, at, e.message), identity), path, at)
    }.toVector
    Diagnostics.check(cases.nonEmpty && cases.map(_.id).distinct.size == cases.size, path, "suite", "suite is empty or contains duplicate IDs")
    cases
  }

  private def parse(row: Json, path: Path, at: String): SuiteCase =
    val cursor = row.hcursor
    def fail(detail: String): Nothing = Diagnostics.invalid(path, at, detail)
    val id = cursor.get[String]("id").toOption.filter(_.nonEmpty).getOrElse(fail("case ID must be nonempty text"))
    val kind = cursor.get[String]("kind").toOption.getOrElse(fail("kind is required"))
    val category = cursor.get[String]("category").toOption.flatMap(name => CaseCategory.values.find(_.name == name))
    if !Set("chat", "completion")(kind) || category.isEmpty then fail("unsupported evaluation kind / category")
    val maxTokens = cursor.downField("maxTokens").focus.flatMap(_.asNumber).flatMap(_.toInt).filter(t => t >= 1 && t <= 8192)
      .getOrElse(fail("maxTokens must be in 1..8192"))
    val input = kind match
      case "chat" =>
        val messages = cursor.downField("messages").focus.flatMap(_.asArray).filter(_.nonEmpty).getOrElse(fail("chat messages are required"))
        ChatInput.Messages(messages, cursor.downField("tools").focus)
      case _ => ChatInput.Completion(cursor.get[String]("prompt").toOption.getOrElse(fail("completion prompt is required")))
    val code = cursor.downField("language").focus.map { value =>
      val language = value.asString.flatMap(name => CodeLanguage.values.find(_.name == name))
      val tests = cursor.downField("tests").focus.flatMap(_.asArray).filter(_.nonEmpty)
      if language.isEmpty || tests.isEmpty then fail("coding cases require a supported language and fixed tests")
      val function = cursor.get[String]("function").toOption.filter(Identifier.matches).getOrElse(fail("invalid function name or test arguments"))
      CodeTask(language.get, function, tests.get.map { test =>
        val args = test.hcursor.downField("args").focus.flatMap(_.asArray).getOrElse(fail("invalid function name or test arguments"))
        FunctionTest(args, test.hcursor.downField("expected").focus.getOrElse(fail("invalid function name or test arguments")))
      }, cursor.get[String]("scalaReturnType").toOption, cursor.get[String]("prefix").toOption, cursor.get[String]("suffix").toOption)
    }
    val tool = cursor.downField("expectedTool").focus.map { expected =>
      val e = expected.hcursor
      ExpectedTool(e.get[String]("name").toOption.getOrElse(fail("expectedTool needs a name")), e.downField("arguments").focus.getOrElse(Json.Null),
        e.downField("result").focus.getOrElse(Json.Null))
    }
    SuiteCase(id, category.get, input, maxTokens, cursor.get[Boolean]("sampling").getOrElse(false), cursor.get[Boolean]("benchmark").getOrElse(false),
      cursor.get[String]("benchmarkLength").getOrElse("unspecified"), code, tool, row)
