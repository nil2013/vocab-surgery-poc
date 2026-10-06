package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.{Files, Path}
import java.nio.file.attribute.BasicFileAttributes
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.model.ModelSnapshot

/** Weight hashes cover headers at plan time; payloads are fingerprinted during apply / verify. */
final case class InputFile(name: String, bytes: Long, modified: String, identity: String, sha256: String, headerOnly: Boolean):
  def json: Json = Json.obj("name" -> Json.fromString(name), "bytes" -> Json.fromLong(bytes), "modified" -> Json.fromString(modified),
    "identity" -> Json.fromString(identity), "sha256" -> Json.fromString(sha256), "headerOnly" -> Json.fromBoolean(headerOnly))

object InputInventory:
  def files(directory: Path): Vector[Path] = Using.resource(Files.walk(directory)) { stream =>
    val paths = stream.iterator().asScala.filter(_ != directory).toVector
    paths.foreach(path => Diagnostics.check(Files.isDirectory(path) && !Files.isSymbolicLink(path) || Files.isRegularFile(path), path, "input inventory", "unsupported input entry"))
    paths.filter(Files.isRegularFile(_)).sortBy(path => directory.relativize(path).toString)
  }

  def capture(snapshot: ModelSnapshot): Vector[InputFile] =
    val shards = snapshot.shards.map(s => s.path.toAbsolutePath.normalize() -> s).toMap
    files(snapshot.directory).map { path =>
      val attributes = Files.readAttributes(path, classOf[BasicFileAttributes])
      val shard = shards.get(path.toAbsolutePath.normalize())
      Diagnostics.check(!path.getFileName.toString.endsWith(".safetensors") || shard.isDefined,
        path, "input inventory", "unindexed checkpoint file")
      InputFile(snapshot.directory.relativize(path).toString, attributes.size(), attributes.lastModifiedTime().toInstant.toString,
        Option(attributes.fileKey()).fold("")(_.toString), shard.fold(FileIO.sha256(path))(_.headerSha256), shard.isDefined)
    }

  def parse(json: Json, path: Path): Vector[InputFile] = JsonFields.array(json, path, "inputs").map { entry =>
    def field(name: String) = JsonFields.field(entry, name, path, "inputs")
    InputFile(JsonFields.text(field("name"), path, "name"), JsonFields.long(field("bytes"), path, "bytes"), JsonFields.text(field("modified"), path, "modified"),
      JsonFields.text(field("identity"), path, "identity"), JsonFields.text(field("sha256"), path, "sha256"), JsonFields.bool(field("headerOnly"), path, "headerOnly"))
  }
