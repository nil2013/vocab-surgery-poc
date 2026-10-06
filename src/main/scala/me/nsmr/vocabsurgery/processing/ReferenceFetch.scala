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
import me.nsmr.vocabsurgery.lexicon.JMdictLoader
import me.nsmr.vocabsurgery.unicode.{UnicodeData, UnihanLoader}

/** A reference data set kept under `<output root>/reference/<name>/<version>/`. */
trait ReferenceSource:
  def name: String
  /** The version a request names before downloading; None when only the downloaded data tells it. */
  def requestedVersion(requested: Option[String]): Option[String]
  def url(version: Option[String]): URI
  def knownSha256(version: String): Option[String] = None
  /** Turn the download in `stage` into the files to publish and return the version they describe. */
  def prepare(download: Path, stage: Path, requested: Option[String]): String

/** Unihan's variant data for one Unicode version, by default ICU's, from its versioned URL. */
object UnihanSource extends ReferenceSource:
  val name = "unihan"
  private val known = Map("17.0.0" -> "f7a48b2b545acfaa77b2d607ae28747404ce02baefee16396c5d2d7a8ef34b5e")
  def requestedVersion(requested: Option[String]): Option[String] = Some(requested.getOrElse(UnicodeData.unicodeVersion))
  def url(version: Option[String]): URI = URI.create(s"https://www.unicode.org/Public/${version.get}/ucd/Unihan.zip")
  override def knownSha256(version: String): Option[String] = known.get(version)
  def prepare(download: Path, stage: Path, requested: Option[String]): String =
    val variants = stage.resolve(UnihanLoader.FileName)
    Using.resource(ZipFile(download.toFile)) { archive =>
      val entry = Option(archive.getEntry(UnihanLoader.FileName)).getOrElse(Diagnostics.invalid(download, UnihanLoader.FileName, "missing from archive"))
      Using.resource(archive.getInputStream(entry))(in => Files.copy(in, variants))
    }
    Files.delete(download)
    UnihanLoader.load(variants).unicodeVersion

/** EDRDG's JMdict (English glosses). It is rebuilt daily without versioned URLs, so the version is its creation date. */
object JMdictSource extends ReferenceSource:
  val name = "jmdict"
  def requestedVersion(requested: Option[String]): Option[String] = None
  def url(version: Option[String]): URI = URI.create("http://ftp.edrdg.org/pub/Nihongo/JMdict_e.gz")
  def prepare(download: Path, stage: Path, requested: Option[String]): String =
    val file = stage.resolve(JMdictLoader.FileName)
    Files.move(download, file)
    val version = JMdictLoader.load(file).created
    requested.foreach(date => Diagnostics.check(date == version, file, "created", s"downloaded JMdict of $version, not $date"))
    version

/** Fetch into staging, check the SHA-256 (known, given, or recorded as unverified), and publish without replacement.
  * A version already present is described as it is and never replaced.
  */
object ReferenceFetch:
  type Opener = URI => InputStream
  val sources: Vector[ReferenceSource] = Vector(UnihanSource, JMdictSource)

  def directory(outputRoot: Path, source: ReferenceSource, version: String): Path =
    outputRoot.resolve("reference").resolve(source.name).resolve(version)

  /** The newest fetched version, by name order; versions are X.Y.Z or ISO dates. */
  def latest(outputRoot: Path, source: ReferenceSource): Option[Path] =
    val root = outputRoot.resolve("reference").resolve(source.name)
    if !Files.isDirectory(root) then None
    else Using.resource(Files.list(root))(_.iterator().asScala.filter(Files.isDirectory(_)).toVector)
      .sortBy(path => path.getFileName.toString.split("[.-]").map(_.toIntOption.getOrElse(0)).toVector)(Ordering.Implicits.seqOrdering).lastOption

  val http: Opener = uri =>
    val client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(30)).build()
    val response = client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
    if response.statusCode() != 200 then
      response.body().close()
      throw java.io.IOException(s"$uri answered HTTP ${response.statusCode()}")
    response.body()

  def fetch(source: ReferenceSource, requested: Option[String] = None, sha256: Option[String] = None,
      options: ProcessingOptions = ProcessingOptions(), open: Opener = http): Json =
    val wanted = source.requestedVersion(requested).orElse(requested)
    wanted.foreach(v => Diagnostics.check(v.matches("[0-9]+([.-][0-9]+)*"), Path.of(source.name), "version", s"invalid version $v"))
    wanted.flatMap(present(options, source, _)).getOrElse(download(source, wanted, sha256, options, open))

  private def present(options: ProcessingOptions, source: ReferenceSource, version: String): Option[Json] =
    val target = directory(options.outputRoot, source, version)
    Option.when(Files.exists(target))(JsonIO.read(target.resolve("source.json")).mapObject(_.add("alreadyPresent", Json.True)))

  private def download(source: ReferenceSource, wanted: Option[String], sha256: Option[String], options: ProcessingOptions, open: Opener): Json =
    val uri = source.url(wanted)
    val stage = ProcessingIO.createDirectory(options.outputRoot, options.outputRoot.resolve(".staging").resolve(UUID.randomUUID().toString), Vector.empty)
    def discard(): Unit = if Files.isDirectory(stage) then
      Using.resource(Files.walk(stage))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.deleteIfExists))
    try
      val download = stage.resolve("download")
      Using.resource(open(uri))(in => Files.copy(in, download))
      val downloadSha256 = FileIO.sha256(download)
      val expected = sha256.map(_.toLowerCase(java.util.Locale.ROOT)).orElse(wanted.flatMap(source.knownSha256))
      expected.foreach(hash => Diagnostics.check(downloadSha256 == hash, download, "sha256", s"expected $hash, downloaded $downloadSha256"))
      val version = source.prepare(download, stage, wanted)
      wanted.foreach(v => Diagnostics.check(version == v, stage, "version", s"download holds version $version, not $v"))
      present(options, source, version) match
        case Some(existing) =>
          discard()
          existing
        case None =>
          val files = Using.resource(Files.list(stage))(_.iterator().asScala.toVector.map(_.getFileName.toString).sorted)
          val record = Json.obj("source" -> Json.fromString(source.name), "version" -> Json.fromString(version), "url" -> Json.fromString(uri.toString),
            "downloadSha256" -> Json.fromString(downloadSha256), "verified" -> Json.fromBoolean(expected.isDefined),
            "files" -> Json.obj(files.map(name => name -> Json.fromString(FileIO.sha256(stage.resolve(name))))*),
            "fetchedAt" -> Json.fromString(java.time.Instant.now().toString))
          ProcessingIO.write(stage.resolve("source.json"), record)
          val target = ProcessingIO.outputPath(options.outputRoot, directory(options.outputRoot, source, version))
          Files.createDirectories(target.getParent)
          options.transfer.publishNew(stage, target)
          record.mapObject(_.add("directory", Json.fromString(target.toString)))
    catch
      case NonFatal(error) =>
        discard()
        throw error
