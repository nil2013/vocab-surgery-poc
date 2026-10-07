package me.nsmr.vocabsurgery.evaluation

import io.circe.{Json, JsonObject}
import io.circe.jawn.JawnParser
import java.io.{BufferedInputStream, ByteArrayOutputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration as JavaDuration
import scala.concurrent.duration.*

/** What a request asks the model: a chat with optional tools, or a raw completion (fill-in-the-middle). */
enum ChatInput:
  case Messages(messages: Vector[Json], tools: Option[Json] = None)
  case Completion(prompt: String)

/** One streamed request. `extra` holds fields the caller adds as they are (chat template switches, a runtime's draft
  * switch); sampling fields left out are left to the server.
  */
final case class ChatRequest(model: String, input: ChatInput, maxTokens: Int, temperature: Option[Double] = Some(0.0),
    seed: Option[Long] = None, topP: Option[Double] = None, toolChoice: Option[Json] = None, extra: JsonObject = JsonObject.empty):
  require(maxTokens > 0, "max tokens must be positive")
  topP.foreach(p => require(p > 0 && p <= 1 && !p.isNaN, "top_p must be in (0, 1]"))

  def body: Json =
    val fields = Vector("model" -> Json.fromString(model), "max_tokens" -> Json.fromInt(maxTokens), "stream" -> Json.True,
      "stream_options" -> Json.obj("include_usage" -> Json.True)) ++
      temperature.map(t => "temperature" -> Json.fromDoubleOrNull(t)) ++ seed.map(s => "seed" -> Json.fromLong(s)) ++
      topP.map(p => "top_p" -> Json.fromDoubleOrNull(p)) ++ toolChoice.map("tool_choice" -> _) ++
      (input match
        case ChatInput.Messages(messages, tools) => Vector("messages" -> Json.fromValues(messages)) ++ tools.map("tools" -> _)
        case ChatInput.Completion(prompt) => Vector("prompt" -> Json.fromString(prompt)))
    Json.fromJsonObject(JsonObject.fromIterable(fields).deepMerge(extra))

  def path: String = input match
    case ChatInput.Messages(_, _) => "/chat/completions"
    case ChatInput.Completion(_) => "/completions"

/** A completed stream: text, reasoning, reassembled tool calls, the finish reason and usage, with client timings. */
final case class ChatResponse(text: String, reasoning: String, toolCalls: Vector[Json], finishReason: String, usage: Json,
    seconds: Double, ttftSeconds: Double, events: Int):
  def completionTokens: Option[Int] = usage.hcursor.get[Int]("completion_tokens").toOption
  def promptTokens: Option[Int] = usage.hcursor.get[Int]("prompt_tokens").toOption
  /** Tokens after the first per second of decoding, as the client saw them. */
  def decodeTokensPerSecond: Option[Double] =
    val decode = seconds - ttftSeconds
    completionTokens.filter(_ > 0).collect { case tokens if decode > 0 => tokens / decode }

  def json: Json = Json.obj("text" -> Json.fromString(text), "reasoning" -> Json.fromString(reasoning), "toolCalls" -> Json.fromValues(toolCalls),
    "finishReason" -> Json.fromString(finishReason), "usage" -> usage, "seconds" -> Json.fromDoubleOrNull(seconds),
    "ttftSeconds" -> Json.fromDoubleOrNull(ttftSeconds), "events" -> Json.fromInt(events),
    "decodeTokensPerSecond" -> decodeTokensPerSecond.fold(Json.Null)(Json.fromDoubleOrNull))

/** An OpenAI-compatible streaming client. The base URI ends in /v1 and carries no credentials; an API key goes in a header. */
final class ChatClient(base: URI, apiKey: Option[String] = None, timeout: FiniteDuration = 30.minutes):
  require(Set("http", "https")(base.getScheme) && base.getHost != null && base.getUserInfo == null && base.getQuery == null && base.getFragment == null,
    "endpoint must be an HTTP(S) URI without credentials, query or fragment")
  private val root = base.toString.stripSuffix("/")
  private val http = HttpClient.newBuilder().connectTimeout(JavaDuration.ofSeconds(30)).build()
  private val parser = JawnParser(allowDuplicateKeys = false)
  private val MaxLine = 64 * 1024
  private val MaxBytes = 16L * 1024 * 1024

  def stream(request: ChatRequest): ChatResponse =
    val builder = HttpRequest.newBuilder(URI.create(root + request.path)).timeout(JavaDuration.ofMillis(timeout.toMillis))
      .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(request.body.noSpaces, StandardCharsets.UTF_8))
    apiKey.foreach(key => builder.header("Authorization", s"Bearer $key"))
    val started = System.nanoTime()
    val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
    if response.statusCode() != 200 then
      val detail = String(response.body().readNBytes(2000), StandardCharsets.UTF_8)
      response.body().close()
      throw ChatError(s"${request.path} answered HTTP ${response.statusCode()}: $detail")
    var first = Option.empty[Long]
    val text, reasoning = StringBuilder()
    val calls = scala.collection.mutable.TreeMap.empty[Int, (String, String, String)]
    var usage = Json.Null
    var finish = Option.empty[String]
    var done = false
    var events = 0
    var bytes = 0L
    val input = BufferedInputStream(response.body())
    try
      var line = readLine(input)
      while line.isDefined && !done do
        bytes += line.get.length + 1
        if bytes > MaxBytes then throw ChatError("response exceeds 16 MiB")
        val data = line.get.trim
        if data.startsWith("data:") then
          val payload = data.drop(5).trim
          if payload == "[DONE]" then done = true
          else
            val event = parser.parse(payload).fold(e => throw ChatError(s"invalid event JSON: ${e.message}"), identity)
            events += 1
            event.hcursor.downField("usage").focus.filter(!_.isNull).foreach(u => usage = u)
            event.hcursor.downField("choices").values.getOrElse(Vector.empty).foreach { choice =>
              val delta = choice.hcursor.downField("delta")
              val content = choice.hcursor.get[String]("text").toOption.orElse(delta.get[String]("content").toOption).getOrElse("")
              val thought = delta.get[String]("reasoning_content").toOption.getOrElse("")
              val parts = delta.downField("tool_calls").values.getOrElse(Vector.empty)
              if (content.nonEmpty || thought.nonEmpty || parts.nonEmpty) && first.isEmpty then first = Some(System.nanoTime())
              text ++= content
              reasoning ++= thought
              parts.foreach { part =>
                val index = part.hcursor.get[Int]("index").getOrElse(0)
                val (id, name, arguments) = calls.getOrElse(index, ("", "", ""))
                val function = part.hcursor.downField("function")
                calls(index) = (part.hcursor.get[String]("id").toOption.filter(_.nonEmpty).getOrElse(id),
                  name + function.get[String]("name").getOrElse(""), arguments + function.get[String]("arguments").getOrElse(""))
              }
              choice.hcursor.get[String]("finish_reason").toOption.foreach(reason => finish = Some(reason))
            }
        line = readLine(input)
    finally input.close()
    val elapsed = (System.nanoTime() - started) / 1e9
    if !done || finish.isEmpty || usage.isNull then throw ChatError("incomplete stream: no [DONE], finish reason or usage")
    val ttft = first.fold(elapsed)(t => (t - started) / 1e9)
    ChatResponse(text.result(), reasoning.result(), calls.values.toVector.map((id, name, arguments) =>
      Json.obj("id" -> Json.fromString(id), "type" -> Json.fromString("function"),
        "function" -> Json.obj("name" -> Json.fromString(name), "arguments" -> Json.fromString(arguments)))),
      finish.get, usage, elapsed, ttft, events)

  private def readLine(input: BufferedInputStream): Option[String] =
    val buffer = ByteArrayOutputStream()
    var byte = input.read()
    if byte < 0 then None
    else
      while byte >= 0 && byte != '\n' do
        if buffer.size() >= MaxLine then throw ChatError("oversized event line")
        buffer.write(byte)
        byte = input.read()
      Some(String(buffer.toByteArray, StandardCharsets.UTF_8))

final class ChatError(message: String) extends RuntimeException(message)
