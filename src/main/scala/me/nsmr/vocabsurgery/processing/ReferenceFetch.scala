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

/** A digest the publisher states for a download: SHA-256 as hex, or an npm integrity string (`sha512-<base64>`). */
final case class PublishedDigest(algorithm: String, value: String):
  require(Set("SHA-256", "SHA-512")(algorithm), s"unsupported digest $algorithm")
  def matches(path: Path): Boolean = algorithm match
    case "SHA-256" => FileIO.sha256(path) == value.toLowerCase(java.util.Locale.ROOT)
    case _ =>
      val digest = java.security.MessageDigest.getInstance("SHA-512")
      Using.resource(Files.newInputStream(path)) { in =>
        val buffer = new Array[Byte](1024 * 1024)
        var read = in.read(buffer)
        while read >= 0 do { digest.update(buffer, 0, read); read = in.read(buffer) }
      }
      "sha512-" + java.util.Base64.getEncoder.encodeToString(digest.digest()) == value

/** A reference data set kept under `<output root>/reference/<name>/<version>/`. */
trait ReferenceSource:
  def name: String
  /** The version a request names before downloading; None when only the downloaded data tells it. */
  def requestedVersion(requested: Option[String]): Option[String]
  def url(version: Option[String]): URI
  def knownSha256(version: String): Option[String] = None
  /** The digest its publisher states for this version's download, fetched with `open`; None when there is none. */
  def publishedDigest(version: Option[String], open: ReferenceFetch.Opener): Option[PublishedDigest] = None
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

/** Unpack a .tar.gz with the system tar into `directory`; tar drops leading slashes and refuses `..` members. */
private[processing] def untar(archive: Path, directory: Path): Unit =
  val process = ProcessBuilder("tar", "-xzf", archive.toString, "-C", directory.toString, "--no-same-owner").redirectErrorStream(true).start()
  process.getOutputStream.close()
  val output = String(process.getInputStream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
  Diagnostics.check(process.waitFor() == 0, archive, "tar", output.take(500))

/** Node.js for the TypeScript code checks: the official build for this OS and CPU, checked against the release's
  * SHASUMS256.txt.
  */
object NodeSource extends ReferenceSource:
  val name = "node"
  /** Without the leading v of Node's release names, like every other reference version. */
  val defaultVersion = "24.21.0"
  private def platform: String =
    val system = if com.sun.jna.Platform.isMac then "darwin" else if com.sun.jna.Platform.isLinux then "linux"
      else throw UnsupportedOperationException(s"no Node build for ${sys.props("os.name")}")
    val cpu = sys.props("os.arch") match
      case "aarch64" | "arm64" => "arm64"
      case "amd64" | "x86_64" => "x64"
      case other => throw UnsupportedOperationException(s"no Node build for $other")
    s"$system-$cpu"
  def archive(version: String): String = s"node-v$version-$platform.tar.gz"
  def requestedVersion(requested: Option[String]): Option[String] = Some(requested.getOrElse(defaultVersion))
  def url(version: Option[String]): URI = URI.create(s"https://nodejs.org/dist/v${version.get}/${archive(version.get)}")
  override def publishedDigest(version: Option[String], open: ReferenceFetch.Opener): Option[PublishedDigest] =
    val sums = Using.resource(open(URI.create(s"https://nodejs.org/dist/v${version.get}/SHASUMS256.txt")))(in =>
      String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
    sums.linesIterator.map(_.trim.split("\\s+")).collectFirst { case Array(hash, file) if file == archive(version.get) => PublishedDigest("SHA-256", hash) }
      .orElse(throw java.io.IOException(s"SHASUMS256.txt of ${version.get} lists no ${archive(version.get)}"))
  def prepare(download: Path, stage: Path, requested: Option[String]): String =
    val version = requested.get
    untar(download, stage)
    Files.delete(download)
    Diagnostics.check(Files.isExecutable(stage.resolve(s"node-v$version-$platform/bin/node")), stage, "node", "archive has no bin/node")
    version
  /** The node binary of a fetched version. */
  def executable(directory: Path, version: String): Path = directory.resolve(s"node-v$version-$platform/bin/node")

/** TypeScript's compiler for the TypeScript code checks, checked against the npm registry's integrity. */
object TypeScriptSource extends ReferenceSource:
  val name = "typescript"
  val defaultVersion = "6.0.2"
  def requestedVersion(requested: Option[String]): Option[String] = Some(requested.getOrElse(defaultVersion))
  def url(version: Option[String]): URI = URI.create(s"https://registry.npmjs.org/typescript/-/typescript-${version.get}.tgz")
  override def publishedDigest(version: Option[String], open: ReferenceFetch.Opener): Option[PublishedDigest] =
    val metadata = Using.resource(open(URI.create(s"https://registry.npmjs.org/typescript/${version.get}")))(in =>
      io.circe.jawn.parse(String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)).fold(e => throw java.io.IOException(e.message), identity))
    val cursor = metadata.hcursor
    if !cursor.get[String]("version").contains(version.get) then throw java.io.IOException(s"registry metadata is not version ${version.get}")
    cursor.downField("dist").get[String]("integrity").toOption.map(PublishedDigest("SHA-512", _))
  def prepare(download: Path, stage: Path, requested: Option[String]): String =
    untar(download, stage)
    Files.delete(download)
    Diagnostics.check(Files.isRegularFile(stage.resolve("package/lib/tsc.js")), stage, "typescript", "package has no lib/tsc.js")
    requested.get
  /** tsc.js of a fetched version. */
  def compiler(directory: Path): Path = directory.resolve("package/lib/tsc.js")

/** Fetch into staging, check the SHA-256 (known, given, or recorded as unverified), and publish without replacement.
  * A version already present is described as it is and never replaced.
  */
object ReferenceFetch:
  type Opener = URI => InputStream
  val sources: Vector[ReferenceSource] = Vector(UnihanSource, JMdictSource, NodeSource, TypeScriptSource)

  /** A file's SHA-256, or for a directory the SHA-256 of its sorted `relative path<TAB>file SHA-256` lines (links by target). */
  private[processing] def digest(path: Path): String =
    if !Files.isDirectory(path, java.nio.file.LinkOption.NOFOLLOW_LINKS) then FileIO.sha256(path)
    else
      val lines = Using.resource(Files.walk(path))(_.iterator().asScala.filter(p => p != path && !Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toVector)
        .map(p => path.relativize(p).toString + "\t" + (if Files.isSymbolicLink(p) then "link:" + Files.readSymbolicLink(p) else FileIO.sha256(p))).sorted
      val sha = java.security.MessageDigest.getInstance("SHA-256").digest(lines.mkString("\n").getBytes(java.nio.charset.StandardCharsets.UTF_8))
      "tree:" + sha.map(b => f"${b & 0xff}%02x").mkString

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
      val expected = sha256.map(_.toLowerCase(java.util.Locale.ROOT)).orElse(wanted.flatMap(source.knownSha256)).map(PublishedDigest("SHA-256", _))
        .orElse(source.publishedDigest(wanted, open))
      expected.foreach(digest => Diagnostics.check(digest.matches(download), download, digest.algorithm,
        s"expected ${digest.value}, downloaded SHA-256 $downloadSha256"))
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
            "verifiedBy" -> expected.fold(Json.Null)(digest => Json.fromString(s"${digest.algorithm} ${digest.value}")),
            "files" -> Json.obj(files.map(name => name -> Json.fromString(digest(stage.resolve(name))))*),
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
