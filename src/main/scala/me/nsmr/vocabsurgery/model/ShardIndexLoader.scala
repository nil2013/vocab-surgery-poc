package me.nsmr.vocabsurgery.model

import java.nio.file.Path
import me.nsmr.vocabsurgery.core.*
import me.nsmr.vocabsurgery.io.*

object ShardIndexLoader:
  def load(path: Path): ShardIndex = Diagnostics.guard(path, "shard index") {
    val root = JsonIO.read(path)
    val weights = JsonFields.entries(JsonFields.field(root, "weight_map", path, "index"), path, "weight_map")
      .map { (name, value) =>
        Diagnostics.check(name.nonEmpty && name != "__metadata__", path, "weight_map", "invalid tensor name")
        val filename = JsonFields.text(value, path, s"weight_map.$name")
        val relative = Path.of(filename)
        Diagnostics.check(!relative.isAbsolute && relative.getNameCount == 1 && filename.endsWith(".safetensors") &&
          !filename.contains("\\") && filename != "." && filename != "..", path, s"weight_map.$name",
          "shard must be a filename in the snapshot directory")
        name -> filename
      }.toMap
    Diagnostics.check(weights.nonEmpty, path, "weight_map", "empty weight map")
    val metadata = JsonFields.obj(root, path, "index")("metadata").map(JsonFields.obj(_, path, "metadata"))
    val total = metadata.flatMap(_("total_size")).map { value =>
      val bytes = JsonFields.long(value, path, "metadata.total_size")
      Diagnostics.check(bytes >= 0, path, "metadata.total_size", "negative byte count")
      ByteCount(bytes)
    }
    val parameters = metadata.flatMap(_("total_parameters")).map { value =>
      val count = JsonFields.long(value, path, "metadata.total_parameters")
      Diagnostics.check(count >= 0, path, "metadata.total_parameters", "negative parameter count")
      count
    }
    ShardIndex(weights, total, parameters, root)
  }
