package me.nsmr.vocabsurgery.evaluation

import com.sun.net.httpserver.HttpServer
import io.circe.{Json, JsonObject}
import java.net.{InetSocketAddress, URI}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import me.nsmr.vocabsurgery.io.DataException
import me.nsmr.vocabsurgery.runtime.DraftRecord

class EvaluationSuite extends munit.FunSuite:
  private def event(json: String) = s"data: $json\n\n"

  /** A server answering every POST with `body` (status `status`), remembering the last request body. */
  private def withServer[A](body: String, status: Int = 200)(test: (URI, () => Json) => A): A =
    val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    @volatile var last = Json.Null
    server.createContext("/", exchange =>
      last = io.circe.jawn.parse(String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8)).getOrElse(Json.Null)
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
      exchange.sendResponseHeaders(status, bytes.length.toLong)
      exchange.getResponseBody.write(bytes)
      exchange.close())
    server.start()
    try test(URI.create(s"http://127.0.0.1:${server.getAddress.getPort}/v1"), () => last)
    finally server.stop(0)

  private val stream =
    event("""{"choices":[{"delta":{"content":"日本"}}]}""") +
      event("""{"choices":[{"delta":{"content":"語","tool_calls":[{"index":0,"id":"c1","function":{"name":"rea","arguments":"{\"p"}}]}}]}""") +
      event("""{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"name":"d","arguments":"\":1}"}}]},"finish_reason":"tool_calls"}]}""") +
      event("""{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":3}}""") + "data: [DONE]\n\n"

  test("the chat client streams text, reassembles tool calls and sends the requested fields") {
    withServer(stream) { (base, last) =>
      val request = ChatRequest("m", ChatInput.Messages(Vector(Json.obj("role" -> Json.fromString("user"), "content" -> Json.fromString("hi"))),
        Some(Json.arr(Json.obj("type" -> Json.fromString("function"))))), 32, Some(0.7), Some(1L), Some(0.8), Some(Json.fromString("none")),
        JsonObject("draft" -> Json.False))
      val response = ChatClient(base).stream(request)
      assertEquals(response.text, "日本語")
      assertEquals(response.finishReason, "tool_calls")
      assertEquals(response.toolCalls.map(_.hcursor.downField("function").get[String]("name")), Vector(Right("read")))
      assertEquals(response.toolCalls.head.hcursor.downField("function").get[String]("arguments"), Right("{\"p\":1}"))
      assertEquals(response.completionTokens, Some(3))
      val sent = last().hcursor
      assertEquals((sent.get[Double]("temperature"), sent.get[Long]("seed"), sent.get[Double]("top_p"), sent.get[Boolean]("draft")),
        (Right(0.7), Right(1L), Right(0.8), Right(false)))
      assertEquals(sent.downField("tools").as[Vector[Json]].map(_.size), Right(1))
      assertEquals(sent.downField("stream_options").get[Boolean]("include_usage"), Right(true))
      ChatClient(base).stream(ChatRequest("m", ChatInput.Completion("def f("), 8, temperature = None))
      assertEquals(last().hcursor.get[String]("prompt"), Right("def f("))
      assert(last().hcursor.downField("temperature").failed)
    }
  }

  test("the chat client rejects incomplete streams, HTTP errors and endpoints with credentials") {
    withServer(event("""{"choices":[{"delta":{"content":"x"}}]}""")) { (base, _) =>
      intercept[ChatError](ChatClient(base).stream(ChatRequest("m", ChatInput.Completion("x"), 4)))
    }
    withServer("no", status = 500) { (base, _) =>
      intercept[ChatError](ChatClient(base).stream(ChatRequest("m", ChatInput.Completion("x"), 4)))
    }
    intercept[IllegalArgumentException](ChatClient(URI.create("http://user:pw@localhost/v1")))
    intercept[IllegalArgumentException](ChatRequest("m", ChatInput.Completion("x"), 4, topP = Some(1.5)))
  }

  test("draft statistics are matched to the requests sent and totalled per group") {
    def record(tokens: Int, rounds: Int, accepted: Int, proposed: Int) = DraftRecord(9, tokens, 50.0, rounds, accepted, proposed)
    val log = Path.of("server.log")
    val sent = Vector(("ja/mtp", "ja/mtp#0", Some(5)), ("ja/serial", "ja/serial#0", Some(5)), ("leak", "leak#0", Some(6)), ("leak", "leak#1", Some(8)))
    val records = Vector(record(5, 2, 3, 3), record(5, 5, 0, 0), record(6, 4, 2, 6), record(8, 4, 4, 6))
    val totals = DraftAttribution.totals(DraftAttribution.attribute(records, sent, log)).hcursor
    assertEquals((totals.downField("leak").get[Int]("accepted"), totals.downField("leak").get[Int]("proposed")), (Right(6), Right(12)))
    assertEquals(totals.downField("ja/mtp").get[Double]("tokensPerRound"), Right(2.5))
    assert(totals.downField("ja/serial").failed)
    intercept[DataException](DraftAttribution.attribute(records.init, sent, log))
    intercept[DataException](DraftAttribution.attribute(records.updated(0, record(9, 2, 3, 3)), sent, log))
  }

  test("speed probes load from the fixed prompt file and summaries leave out warm-ups") {
    val probes = SpeedProbes.load(Path.of("evaluation/speed-prompts.json"))
    assertEquals(probes.prompts.map(_._1), Vector("ja", "en", "code"))
    assertEquals((probes.trials, probes.warmups, probes.maxTokens), (4, 1, 400))
    assertEquals(probes.extra("chat_template_kwargs").flatMap(_.hcursor.get[Boolean]("enable_thinking").toOption), Some(false))
    def row(trial: Int, decodeSeconds: Double) = SpeedRow("ja", Some(true), trial, trial == 0,
      ChatResponse("x" * 10, "", Vector.empty, "stop", Json.obj("completion_tokens" -> Json.fromInt(10)), 0.1 + decodeSeconds, 0.1, 1))
    val summary = SpeedMeasurement.summary(Vector(row(0, 0.01), row(1, 1.0), row(2, 0.5), row(3, 0.25))).hcursor.downField("ja/mtp")
    assertEquals(summary.get[Double]("decodeTokensPerSecondMedian").map(math.round), Right(20L))
    assertEquals(summary.downField("completionTokens").as[Vector[Int]].map(_.size), Right(3))
  }
