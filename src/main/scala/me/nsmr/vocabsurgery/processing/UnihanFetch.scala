package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.io.InputStream
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.file.{Files, Path}
import java.time.Duration
import java.util.UUID
import java.util.zip.ZipFile
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.control.NonFatal
import me.nsmr.vocabsurgery.io.{Diagnostics, FileIO, JsonIO}
import me.nsmr.vocabsurgery.unicode.{UnicodeData, UnihanLoader}

/** Fetch Unihan's variant data for one Unicode version into `<output root>/reference/unihan/<version>/`.
  * The download is staged, checked against a known or given SHA-256, and published without replacing anything.
  * The version defaults to ICU's, so the character data and Unihan describe the same Unicode release.
  */
object UnihanFetch:
  /** Zip digests checked before adoption. A version outside this table needs `--sha256`, or is recorded as unverified. */
  val KnownZipSha256: Map[String, String] = Map("17.0.0" -> "f7a48b2b545acfaa77b2d607ae28747404ce02baefee16396c5d2d7a8ef34b5e")
  type Opener = URI => InputStream

  def url(version: String): URI = URI.create(s"https://www.unicode.org/Public/$version/ucd/Unihan.zip")
  def directory(outputRoot: Path, version: String): Path = outputRoot.resolve("reference").resolve("unihan").resolve(version)

  val http: Opener = uri =>
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
    val response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    if response.statusCode() != 200 then
      response.body().close()
      throw java.io.IOException(s"$uri answered HTTP ${response.statusCode()}")
    response.body()

  /** @return a JSON description; an existing directory is described as it is and never replaced or re-downloaded. */
  def fetch(version: String = UnicodeData.unicodeVersion, sha256: Option[String] = None, options: ProcessingOptions = ProcessingOptions(),
      open: Opener = http): Json =
    Diagnostics.check(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+"), Path.of("unicode-version"), "version", s"expected X.Y.Z, found $version")
    val target = ProcessingIO.outputPath(options.outputRoot, directory(options.outputRoot, version))
    if Files.exists(target) then return JsonIO.read(target.resolve("source.json")).mapObject(_.add("alreadyPresent", Json.True))
    val expected = sha256.map(_.toLowerCase(java.util.Locale.ROOT)).orElse(KnownZipSha256.get(version))
    val stage = ProcessingIO.createDirectory(options.outputRoot, options.outputRoot.resolve(".staging").resolve(UUID.randomUUID().toString), Vector.empty)
    try
      val zip = stage.resolve("Unihan.zip")
      Using.resource(open(url(version)))(in => Files.copy(in, zip))
      val zipSha256 = FileIO.sha256(zip)
      expected.foreach(hash => Diagnostics.check(zipSha256 == hash, zip, "sha256", s"expected $hash, downloaded $zipSha256"))
      val variants = stage.resolve(UnihanLoader.FileName)
      Using.resource(ZipFile(zip.toFile)) { archive =>
        val entry = Option(archive.getEntry(UnihanLoader.FileName)).getOrElse(Diagnostics.invalid(zip, UnihanLoader.FileName, "missing from archive"))
        Using.resource(archive.getInputStream(entry))(in => Files.copy(in, variants))
      }
      Files.delete(zip)
      val loaded = UnihanLoader.load(variants)
      Diagnostics.check(loaded.unicodeVersion == version, variants, "header", s"archive holds Unicode ${loaded.unicodeVersion}, not $version")
      val source = Json.obj("unicodeVersion" -> Json.fromString(version), "url" -> Json.fromString(url(version).toString),
        "zipSha256" -> Json.fromString(zipSha256), "verified" -> Json.fromBoolean(expected.isDefined),
        "variantsSha256" -> Json.fromString(FileIO.sha256(variants)), "fetchedAt" -> Json.fromString(java.time.Instant.now().toString))
      ProcessingIO.write(stage.resolve("source.json"), source)
      Files.createDirectories(target.getParent)
      options.transfer.publishNew(stage, target)
      source.mapObject(_.add("directory", Json.fromString(target.toString)))
    catch
      case NonFatal(error) =>
        if Files.isDirectory(stage) then
          Using.resource(Files.walk(stage))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))
        throw error

