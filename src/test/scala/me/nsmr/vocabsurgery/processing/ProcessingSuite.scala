package me.nsmr.vocabsurgery.processing

import io.circe.Json
import java.nio.file.{Files, Path, StandardOpenOption}
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import scala.jdk.CollectionConverters.*
import scala.util.Using
import me.nsmr.vocabsurgery.io.*
import me.nsmr.vocabsurgery.model.*
import me.nsmr.vocabsurgery.core.*

class ProcessingSuite extends munit.FunSuite:
  private val kanji = Path.of("kanji-data")
  private val default = PolicyFile.default

  private def characters(json: Json): Json = json.mapObject(_.add("kind", Json.fromString("characters")))

  test("policy file round trip requires a known kind and rejects unknown fields, bad scalars and missing files") {
    val path = Path.of("synthetic-policy.json")
    val file = PolicyFile.default
    assertEquals(PolicyFile.parse(file.json, path), file)
    intercept[DataException](PolicyFile.parse(Json.obj(), path))
    intercept[DataException](PolicyFile.parse(Json.obj("kind" -> Json.fromString("frequency")), path))
    Vector(Json.obj("oops" -> Json.True), Json.obj("formatVersion" -> Json.fromInt(2)),
      Json.obj("unicodeVersion" -> Json.fromString("16.0.0")), Json.obj("japaneseExtra" -> Json.arr(Json.fromInt(0xd800))),
      Json.obj("explicitForbidden" -> Json.arr(Json.fromInt(-1))), Json.obj("protectedIds" -> Json.arr(Json.fromInt(-1))),
      Json.obj("forbiddenScripts" -> Json.arr(Json.fromString("nonsense"))), Json.obj("unregisteredRows" -> Json.fromString("keep")),
      Json.obj("parameterCount" -> Json.fromString("infer")), Json.obj("pruneHanOutsideReference" -> Json.Null),
      Json.obj("protectMath" -> Json.Null), Json.obj("protectMath" -> Json.fromString("true")), Json.obj("japaneseReference" -> Json.True))
      .foreach(json => intercept[DataException](PolicyFile.parse(characters(json), path)))
    intercept[DataException](PolicyFile.load(Path.of("nonexistent-policy.json")))
  }

  test("character policy protects Math by default and records the switch") {
    val path = Path.of("policy.json")
    val parsed = PolicyFile.parse(characters(Json.obj()), path).settings.asInstanceOf[CharacterPolicySettings]
    assert(parsed.protectMath)
    assertEquals(parsed.japaneseReference, Path.of("kanji-data"))
    val disabled = PolicyFile(CharacterPolicySettings(protectMath = false))
    assertEquals(PolicyFile.parse(disabled.json, path), disabled)
    assertEquals(disabled.json.hcursor.get[Boolean]("protectMath"), Right(false))
    val reference = me.nsmr.vocabsurgery.kanji.JapaneseKanjiReference.fromCharacters(Set(CodePoint(0x65e5)))
    assert(CharacterPolicySettings().domain(reference).protectMath)
    assert(!CharacterPolicySettings(protectMath = false).domain(reference).protectMath)
  }

  test("coding profile preserves Greek and Canadian Aboriginal and explicitly targets the five reviewed Han characters") {
    val settings = PolicyFile.load(Path.of("policies/ja-en-coding-v2.json")).settings.asInstanceOf[CharacterPolicySettings]
    assert(settings.protectMath)
    assertEquals(settings.explicitForbidden, CodePoint.sequence("个开关无网").toSet)
    assert(settings.forbiddenScripts(me.nsmr.vocabsurgery.unicode.UnicodeScript.fromName("Hebrew")))
    assert(settings.forbiddenScripts(me.nsmr.vocabsurgery.unicode.UnicodeScript.fromName("Thai")))
    Vector("Greek", "Canadian_Aboriginal", "Latin", "Han", "Hiragana", "Katakana", "Common", "Inherited")
      .foreach(name => assert(!settings.forbiddenScripts(me.nsmr.vocabsurgery.unicode.UnicodeScript.fromName(name))))
  }

  test("deletion lists read JSON string lines, skip comments and blanks, and reject duplicates within or across lists or bare text") {
    val directory = Files.createTempDirectory("vocab-deletion-list-")
    try
      val path = directory.resolve("list.txt")
      Files.writeString(path, "# Greek fragments\n\n\" yang\"\n\"\\n\\n\"\n  \"什么\"  \r\n")
      assertEquals(DeletionList.read(path), Vector(" yang", "\n\n", "什么"))
      Vector("yang\n", "\"a\"\n\"a\"\n", "\"\"\n", "1\n").foreach { text =>
        Files.writeString(path, text)
        intercept[DataException](DeletionList.read(path))
      }
      val other = directory.resolve("other.txt")
      Files.writeString(other, "\"ab\"\n")
      Files.writeString(path, "\" yang\"\n")
      assertEquals(DeletionList.readAll(Vector(path, other)), Vector(" yang", "ab"))
      Files.writeString(other, "\"ab\"\n\" yang\"\n")
      intercept[DataException](DeletionList.readAll(Vector(path, other)))
    finally Using.resource(Files.walk(directory))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete))
  }

  test("listed tokens are deleted, protected ones kept as conflicts, unmatched entries reported, and list edits invalidate the plan") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val list = directory.resolve("deleted.txt")
      val second = directory.resolve("deleted-protocol.txt")
      Files.writeString(list, "\"ab\"\n\"not-in-this-model\"\n")
      Files.writeString(second, "\"<tool_call>\"\n")
      val policy = PolicyFile(CharacterPolicySettings(deletedTokens = Vector(list, second)))
      assertEquals(PolicyFile.parse(policy.json, Path.of("p.json")), policy)
      val options = ProcessingOptions(outputRoot = output)
      val path = CheckpointProcessor.plan(input, policy, output.resolve("runs/listed"), options)
      val prepared = PlanStore.loadVerified(path)
      val tokens = prepared.domain.analysis.tokens
      val ab = tokens.find(_.content == "ab").get
      assert(prepared.domain.analysis.cleanup.directDeleted(ab.id))
      assert(tokens.find(_.content == "<tool_call>").exists(t => t.protectedToken && t.deletion.exists(_.code == "listed")))
      val summary = JsonIO.read(path.getParent.resolve("summary.json"))
      val deletion = summary.hcursor.downField("policy").downField("deletionList")
      assertEquals(deletion.get[Int]("matchedTokens"), Right(2))
      assertEquals(deletion.get[Int]("keptByProtection"), Right(1))
      assertEquals(deletion.get[Vector[String]]("unmatchedExamples"), Right(Vector("not-in-this-model")))
      assertEquals(summary.hcursor.downField("rowsAboveMultiple").get[Int]("64"), Right(prepared.domain.projection.compactSize % 64))
      assertEquals(Files.readString(path.getParent.resolve("deletion-list-unmatched.jsonl")), "\"not-in-this-model\"\n")
      Files.writeString(second, "\"<tool_call>\"\n\"cd\"\n")
      intercept[DataException](PlanStore.loadVerified(path))
    }
  }

  test("a tool source is checked against its publisher's digest and unpacked into a versioned directory") {
    val root = Files.createTempDirectory("vocab-tool-fetch-")
    try
      val build = Files.createDirectories(root.resolve("build/package/lib"))
      Files.writeString(build.resolve("tsc.js"), "// tsc\n")
      val archive = root.resolve("typescript.tgz")
      val tar = ProcessBuilder("tar", "-czf", archive.toString, "-C", root.resolve("build").toString, "package").start()
      assertEquals(tar.waitFor(), 0)
      val integrity = "sha512-" + java.util.Base64.getEncoder.encodeToString(java.security.MessageDigest.getInstance("SHA-512").digest(Files.readAllBytes(archive)))
      def registry(stated: String): ReferenceFetch.Opener = uri =>
        if uri.getPath.endsWith(".tgz") then Files.newInputStream(archive)
        else java.io.ByteArrayInputStream(s"""{"version":"9.9.9","dist":{"integrity":"$stated"}}""".getBytes(java.nio.charset.StandardCharsets.UTF_8))
      val options = ProcessingOptions(outputRoot = root.resolve("outputs"))
      intercept[DataException](ReferenceFetch.fetch(TypeScriptSource, Some("9.9.9"), None, options, registry("sha512-AAAA")))
      val record = ReferenceFetch.fetch(TypeScriptSource, Some("9.9.9"), None, options, registry(integrity)).hcursor
      assertEquals(record.get[Boolean]("verified"), Right(true))
      assert(record.downField("files").get[String]("package").exists(_.startsWith("tree:")))
      assert(Files.isRegularFile(TypeScriptSource.compiler(ReferenceFetch.directory(root.resolve("outputs"), TypeScriptSource, "9.9.9"))))
    finally Using.resource(Files.walk(root))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete))
  }

  private def unihanFile(directory: Path, version: String = me.nsmr.vocabsurgery.unicode.UnicodeData.unicodeVersion): Path =
    val file = directory.resolve("Unihan_Variants.txt")
    // 日 has no variant; 发 is a simplified form of 發; 發 a traditional form; 国 (常用) has the traditional variant 國.
    Files.writeString(file, s"# Unihan_Variants.txt\n# Unicode Version $version\n#\nU+56FD\tkTraditionalVariant\tU+570B\n" +
      "U+53D1\tkTraditionalVariant\tU+50AA U+767C\nU+767C\tkSimplifiedVariant\tU+53D1\nU+767C\tkSemanticVariant\tU+50AA<kMatthews\n" +
      "U+75D2\tkTraditionalVariant\tU+7662\n")
    file

  test("Unihan loader keeps simplified and traditional variants and reads the Unicode version") {
    val directory = Files.createTempDirectory("vocab-unihan-")
    try
      val variants = me.nsmr.vocabsurgery.unicode.UnihanLoader.load(unihanFile(directory))
      assertEquals(variants.simplifiedForms, Set(CodePoint(0x53d1), CodePoint(0x56fd), CodePoint(0x75d2)))
      assertEquals(variants.traditionalForms, Set(CodePoint(0x767c)))
      Files.writeString(directory.resolve("Unihan_Variants.txt"), "U+53D1\tkTraditionalVariant\tU+767C\n")
      intercept[DataException](me.nsmr.vocabsurgery.unicode.UnihanLoader.load(directory.resolve("Unihan_Variants.txt")))
    finally Using.resource(Files.walk(directory))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete))
  }

  private def zipped(directory: Path, name: String, content: Array[Byte]): Path =
    val zip = directory.resolve(s"$name.zip")
    Using.resource(java.util.zip.ZipOutputStream(Files.newOutputStream(zip))) { out =>
      out.putNextEntry(java.util.zip.ZipEntry(name))
      out.write(content)
      out.closeEntry()
    }
    zip

  private def jmdict(directory: Path, created: String): Path =
    val file = directory.resolve(s"JMdict-$created.gz")
    val xml = s"""<?xml version="1.0" encoding="UTF-8"?>
      |<!DOCTYPE JMdict [
      |<!ELEMENT JMdict (entry*)>
      |<!ENTITY rK "rarely used kanji form">
      |<!ENTITY n "noun (common) (futsuumeishi)">
      |]>
      |<!-- JMdict created: $created -->
      |<JMdict>
      |<entry><ent_seq>1</ent_seq><k_ele><keb>痒み</keb><ke_pri>news1</ke_pri></k_ele><r_ele><reb>かゆみ</reb></r_ele><sense><pos>&n;</pos></sense></entry>
      |<entry><ent_seq>2</ent_seq><k_ele><keb>洒落</keb></k_ele><k_ele><keb>洒落る</keb><ke_inf>&rK;</ke_inf></k_ele><r_ele><reb>しゃれ</reb></r_ele></entry>
      |</JMdict>
      |""".stripMargin
    Using.resource(java.util.zip.GZIPOutputStream(Files.newOutputStream(file)))(_.write(xml.getBytes("UTF-8")))
    file

  test("JMdict loader keeps kanji headwords, priority and ke_inf codes without expanding entities") {
    val directory = Files.createTempDirectory("vocab-jmdict-")
    try
      val lexicon = me.nsmr.vocabsurgery.lexicon.JMdictLoader.load(jmdict(directory, "2026-10-06"))
      assertEquals(lexicon.created, "2026-10-06")
      assertEquals(lexicon.headwords.map(h => (h.text, h.priority, h.info)), Vector(("痒み", true, Set.empty[String]), ("洒落", false, Set.empty[String]), ("洒落る", false, Set("rK"))))
      val usage = lexicon.usage(CodePoint(0x6d12))
      assertEquals((usage.headwords, usage.standard, usage.priority), (2, 1, 0))
      assertEquals(lexicon.usage(CodePoint(0x4e48)).headwords, 0)
    finally Using.resource(Files.walk(directory))(_.iterator().asScala.toVector.sortBy(_.getNameCount).reverse.foreach(Files.delete))
  }

  test("reference fetch stages, checks digests, keeps versions apart and never replaces a fetched version") {
    ProcessingFixtures.withModel { (directory, _, output) =>
      val zip = zipped(directory, "Unihan_Variants.txt", Files.readAllBytes(unihanFile(Files.createDirectory(directory.resolve("src")))))
      val hash = FileIO.sha256(zip)
      var opened = 0
      val unihan: ReferenceFetch.Opener = _ => { opened += 1; Files.newInputStream(zip) }
      val options = ProcessingOptions(outputRoot = output)
      intercept[DataException](ReferenceFetch.fetch(UnihanSource, Some("17.0.0"), Some("0" * 64), options, unihan))
      assert(!Files.exists(ReferenceFetch.directory(output, UnihanSource, "17.0.0")))
      assertEquals(ReferenceFetch.fetch(UnihanSource, None, Some(hash), options, unihan).hcursor.get[Boolean]("verified"), Right(true))
      val fetched = ReferenceFetch.directory(output, UnihanSource, "17.0.0")
      assertEquals(InputInventory.files(fetched).map(_.getFileName.toString), Vector("Unihan_Variants.txt", "source.json"))
      assertEquals(ReferenceFetch.fetch(UnihanSource, None, None, options, unihan).hcursor.get[Boolean]("alreadyPresent"), Right(true))
      assertEquals(opened, 2)
      intercept[DataException](ReferenceFetch.fetch(UnihanSource, Some("18.0.0"), Some(hash), options, unihan))
      assert(!Files.exists(ReferenceFetch.directory(output, UnihanSource, "18.0.0")))
      val builds = Vector("2026-10-05", "2026-10-06").map(date => date -> jmdict(directory, date)).toMap
      def jm(date: String): ReferenceFetch.Opener = _ => Files.newInputStream(builds(date))
      val first = ReferenceFetch.fetch(JMdictSource, None, None, options, jm("2026-10-06"))
      assertEquals((first.hcursor.get[String]("version"), first.hcursor.get[Boolean]("verified")), (Right("2026-10-06"), Right(false)))
      ReferenceFetch.fetch(JMdictSource, None, None, options, jm("2026-10-05"))
      assertEquals(ReferenceFetch.fetch(JMdictSource, None, None, options, jm("2026-10-06")).hcursor.get[Boolean]("alreadyPresent"), Right(true))
      assertEquals(ReferenceFetch.latest(output, JMdictSource), Some(ReferenceFetch.directory(output, JMdictSource, "2026-10-06")))
      intercept[DataException](ReferenceFetch.fetch(JMdictSource, Some("2026-10-07"), None, options, jm("2026-10-05")))
      Using.resource(Files.list(output.resolve(".staging")))(stages => assertEquals(stages.count(), 0L))
    }
  }

  test("Han profile states list membership, Unihan variants and JMdict usage without deciding") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val unihan = Files.createDirectory(directory.resolve("unihan"))
      unihanFile(unihan)
      val jm = Files.createDirectory(directory.resolve("jmdict"))
      Files.move(jmdict(directory, "2026-10-06"), jm.resolve("JMdict_e.gz"))
      val (references, inputs) = HanProfile.load(HanReferencePaths(unihan = Some(unihan), jmdict = Some(jm)), output)
      val judge = references.judge
      val fa = judge(CodePoint(0x53d1))
      assert(fa.simplifiedForm && !fa.traditionalForm && !fa.dailyJapanese && !fa.inJapaneseReference)
      val hatsu = judge(CodePoint(0x767c))
      assert(hatsu.traditionalForm && hatsu.simplifiedVariants == Set(CodePoint(0x53d1)))
      val koku = judge(CodePoint(0x56fd))
      assert(koku.joyo && koku.simplifiedForm && koku.dailyJapanese)
      assertEquals(judge(CodePoint(0x75d2)).lexicon.priority, 1)
      assertEquals(inputs.hcursor.downField("jmdict").get[String]("created"), Right("2026-10-06"))
      val summary = HanProfile.write(HanProfile.fromText("痒い国の發"), false, references, inputs, output.resolve("runs/profile"), Vector.empty,
        ProcessingOptions(outputRoot = output))
      assertEquals(summary.hcursor.get[Int]("characters"), Right(3))
      val lines = Files.readAllLines(output.resolve("runs/profile/han-profile.jsonl")).asScala.toVector
      assert(lines.head.contains("\"glyph\":\"痒\"") && lines.head.contains("\"priority\":1"))
      intercept[DataException](HanProfile.load(HanReferencePaths(unihan = Some(unihan)), output.resolve("empty")))
    }
  }

  test("hanForms prunes judged forms, keeping daily Japanese, manual keeps and JMdict use as configured") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val unihan = Files.createDirectory(directory.resolve("unihan"))
      unihanFile(unihan)
      val jm = Files.createDirectory(directory.resolve("jmdict"))
      Files.move(jmdict(directory, "2026-10-06"), jm.resolve("JMdict_e.gz"))
      import me.nsmr.vocabsurgery.kanji.*
      def settings(rule: HanFormRule) = CharacterPolicySettings(hanForms = Some(HanFormSettings(rule, Some(unihan), Some(jm))))
      def loaded(rule: HanFormRule) = PolicyFile(settings(rule)).resolved.settings.load().policy.asInstanceOf[me.nsmr.vocabsurgery.tokenizer.CharacterPolicy]
      val both = HanFormRule(Set(HanForm.Simplified, HanForm.Traditional))
      val (fa, hatsu, kayui) = (CodePoint(0x53d1), CodePoint(0x767c), CodePoint(0x75d2))
      val conservative = loaded(both)
      assertEquals((conservative.simplifiedForms, conservative.traditionalForms), (Set(fa), Set(hatsu)))
      assertEquals(conservative.forbidden(fa).map(_.reasons.map(_.code)), Some(Set("han_simplified_form", "han_outside_japanese_reference")))
      assertEquals(conservative.forbidden(CodePoint(0x56fd)), None)
      assertEquals(loaded(both.copy(keepInJMdict = JMdictProtection.NoProtection)).simplifiedForms, Set(fa, kayui))
      assertEquals(loaded(both.copy(keepInJMdict = JMdictProtection.PriorityHeadword)).simplifiedForms, Set(fa))
      assertEquals(loaded(both.copy(keepInJMdict = JMdictProtection.NoProtection, keep = Set(kayui))).simplifiedForms, Set(fa))
      assertEquals(loaded(HanFormRule(Set(HanForm.Traditional))).simplifiedForms, Set.empty[CodePoint])
      val file = PolicyFile(settings(both.copy(keep = Set(kayui))))
      assertEquals(PolicyFile.parse(file.json, Path.of("p.json")), file)
      intercept[DataException](PolicyFile.parse(file.json.mapObject(f => f.add("hanForms", f("hanForms").get.mapObject(_.add("prune", Json.arr())))), Path.of("p.json")))
      val path = CheckpointProcessor.plan(input, file, output.resolve("runs/hanforms"), ProcessingOptions(outputRoot = output))
      assert(Files.isRegularFile(path.getParent.resolve("han-forms.jsonl")))
      assertEquals(JsonIO.read(path).hcursor.downField("policyInputs").downField("hanForms").downField("jmdict").get[String]("created"), Right("2026-10-06"))
      unihanFile(Files.createDirectory(directory.resolve("other")), "18.0.0")
      intercept[DataException](PolicyFile(CharacterPolicySettings(hanForms = Some(HanFormSettings(both, Some(directory.resolve("other")), Some(jm))))).resolved.settings.load())
      intercept[DataException](PolicyFile(CharacterPolicySettings(hanForms = Some(HanFormSettings(both, Some(directory.resolve("absent")), Some(jm))))).resolved)
    }
  }

  test("saved plan creates analysis artifacts and verifies input identity and deterministic operations") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      val path = CheckpointProcessor.plan(input, default, output.resolve("runs/test"), options)
      val loaded = PlanStore.loadVerified(path)
      assertEquals(loaded.domain.removedRows, 4)
      assert(Files.isRegularFile(path.getParent.resolve("kanji-missing-texts.jsonl")))
      assert(Files.isRegularFile(path.getParent.resolve("tokens.jsonl")))
      assertEquals(loaded.domain.projection.originalToCompactJson, JsonIO.read(path.getParent.resolve("original-to-compact.json")))
      val original = JsonIO.read(path)
      Files.writeString(path, original.mapObject(_.add("domainSha256", Json.fromString("bad"))).noSpaces)
      intercept[DataException](PlanStore.loadVerified(path))
      Files.writeString(path, original.noSpaces)
      Files.writeString(input.resolve("README.md"), "modified after plan\n")
      intercept[DataException](PlanStore.loadVerified(path))
    }
  }

  test("apply and verify project exact packed rows with copy fallback and independent outputs") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform), bufferBytes = 7)
      val path = CheckpointProcessor.plan(input, default, output.resolve("runs/apply"), options)
      val before = FileIO.sha256(input.resolve("input.safetensors"))
      val model = CheckpointProcessor.applyPlan(path, output.resolve("models/pruned"), ProcessingFixtures.reference, options)
      assertEquals(FileIO.sha256(input.resolve("input.safetensors")), before)
      assertEquals(SnapshotLoader.load(model).config.vocabSize, PlanStore.loadVerified(path).domain.projection.compactSize)
      assertEquals(CheckpointProcessor.verifyManifest(path.getParent.resolve("manifest.json"), ProcessingFixtures.reference, options).tensors.size, 7)
      val manifestPath = path.getParent.resolve("manifest.json")
      val manifest = JsonIO.read(manifestPath)
      Files.writeString(manifestPath, manifest.mapObject(_.add("compactRows", Json.fromInt(1))).noSpaces)
      intercept[DataException](CheckpointProcessor.verifyManifest(manifestPath, ProcessingFixtures.reference, options))
      Files.writeString(manifestPath, manifest.noSpaces)
      assert(!Files.isSameFile(input.resolve("README.md"), model.resolve("README.md")))
      assert(Files.isRegularFile(path.getParent.resolve("publication.json")))
      intercept[DataException](CheckpointProcessor.applyPlan(path, model, ProcessingFixtures.reference, options))
      val checkpoint = model.resolve("input.safetensors")
      Using.resource(FileChannel.open(checkpoint, StandardOpenOption.WRITE)) { channel =>
        channel.write(ByteBuffer.wrap(Array[Byte](0x42)), SafetensorsIO.read(checkpoint).payloadStart.longValue)
      }
      intercept[DataException](CheckpointProcessor.verifyManifest(path.getParent.resolve("manifest.json"), ProcessingFixtures.reference, options))
    }
  }

  test("insufficient space stops before staging; failed validation leaves diagnostics without publication") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform))
      val path = CheckpointProcessor.plan(input, default, output.resolve("runs/failure"), options)
      val target = output.resolve("models/failed")
      intercept[DataException](CheckpointProcessor.applyPlan(path, target, ProcessingFixtures.reference, options.copy(availableBytes = _ => 0L)))
      assert(!Files.exists(output.resolve(".staging")))
      val reject: CheckpointProcessor.ReferenceValidator = (_, _) => Json.obj("passed" -> Json.False)
      intercept[DataException](CheckpointProcessor.applyPlan(path, target, reject, options))
      assert(!Files.exists(target))
      Using.resource(Files.list(output.resolve(".staging"))) { paths =>
        val stages = paths.iterator().asScala.toVector
        assertEquals(stages.size, 1)
        assert(Files.isRegularFile(stages.head.resolve("failure.json")))
      }
    }
  }

  test("Flash-Next apply writes PLE token IDs and slow tokenizer files, and verification requires exactly the planned set") {
    ProcessingFixtures.withSnapshot(DomainFixtures.flashNext()) { (_, input, output) =>
      val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform))
      val path = CheckpointProcessor.plan(input, default, output.resolve("runs/flash"), options)
      val prepared = PlanStore.loadVerified(path)
      val model = CheckpointProcessor.applyPlan(path, output.resolve("models/flash"), ProcessingFixtures.reference, options)
      val table = me.nsmr.vocabsurgery.model.PleTokenIds.parse(JsonIO.read(model.resolve("ple-token-ids.json")))
      assertEquals(table.originalTokenIds, prepared.domain.projection.compactToOriginal.map(_.intValue))
      assertEquals(Files.readString(model.resolve("merges.txt")), prepared.domain.file("merges.txt").content)
      val manifest = JsonIO.read(path.getParent.resolve("manifest.json"))
      val methods = manifest.hcursor.downField("files").values.get.map(f => f.hcursor.get[String]("name").toOption.get -> f.hcursor.get[String]("method").toOption.get).toMap
      assertEquals(methods("ple-token-ids.json"), "generated")
      assertEquals(methods("README.md"), "buffered-copy")
      assertEquals(manifest.hcursor.get[String]("family"), Right("flash-next"))
      CheckpointProcessor.verifyManifest(path.getParent.resolve("manifest.json"), ProcessingFixtures.reference, options)
      val again = CheckpointProcessor.plan(model, default, output.resolve("runs/again"), options)
      assertEquals(PlanStore.loadVerified(again).domain.removedRows, 0)
      Files.delete(model.resolve("ple-token-ids.json"))
      intercept[DataException](CheckpointProcessor.verifyManifest(path.getParent.resolve("manifest.json"), ProcessingFixtures.reference, options))
    }
  }

  for (modelType, unquantized) <- Vector("qwen3_5" -> false, "qwen3_5_moe" -> false, "qwen3_5_moe" -> true) do
    test(s"Qwen3.5 $modelType dense=$unquantized persists, applies, verifies and replans without family sidecars") {
      ProcessingFixtures.withSnapshot(DomainFixtures.qwen35(modelType, unquantized)) { (_, input, output) =>
        val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform))
        val path = CheckpointProcessor.plan(input, default, output.resolve("runs/qwen35"), options)
        val prepared = PlanStore.loadVerified(path)
        assertEquals(prepared.domain.family, me.nsmr.vocabsurgery.model.Qwen35)
        val model = CheckpointProcessor.applyPlan(path, output.resolve("models/qwen35"), ProcessingFixtures.reference, options)
        val loaded = SnapshotLoader.load(model)
        assertEquals(loaded.config.family, me.nsmr.vocabsurgery.model.Qwen35)
        assertEquals(loaded.config.vocabSize, prepared.domain.projection.compactSize)
        assertEquals(loaded.config.quantization, SnapshotLoader.load(input).config.quantization)
        assert(!Files.exists(model.resolve("ple-token-ids.json")))
        val manifest = path.getParent.resolve("manifest.json")
        assertEquals(JsonIO.read(manifest).hcursor.get[String]("family"), Right("qwen35"))
        CheckpointProcessor.verifyManifest(manifest, ProcessingFixtures.reference, options)
        val again = CheckpointProcessor.plan(model, default, output.resolve("runs/again"), options)
        assertEquals(PlanStore.loadVerified(again).domain.removedRows, 0)
      }
    }

  test("publication refuses a target created after staging, and copying keeps the base publication") {
    ProcessingFixtures.withModel { (_, input, output) =>
      Files.createDirectories(output.resolve("stage"))
      Files.createDirectories(output.resolve("taken"))
      intercept[DataException](FileTransfer.platform.publishNew(output.resolve("stage"), output.resolve("taken")))
      assert(Files.isDirectory(output.resolve("stage")))
      FileTransfer.copying(FileTransfer.platform).publishNew(output.resolve("stage"), output.resolve("published"))
      assert(Files.isDirectory(output.resolve("published")) && !Files.exists(output.resolve("stage")))
      assertEquals(FileTransfer.copying(FileTransfer.platform).cloneNew(input.resolve("README.md"), output.resolve("x")), None)
      assert(!Files.exists(output.resolve("x")))
    }
  }

  test("no-deletion processing copies the entire checkpoint byte-for-byte") {
    ProcessingFixtures.withModel { (_, input, output) =>
      val loaded = SnapshotLoader.load(input)
      val rows = (loaded.tokenizer.vocabulary.map(_.id) ++ loaded.tokenizer.addedTokens.map(_.id)).distinct.size
      var cursor = 0L
      val tensors = loaded.shards.head.tensors.map { tensor =>
        val shape = if tensor.name == "body" then tensor.shape else rows.toLong +: tensor.shape.tail
        val length = shape.foldLeft(tensor.dtype.byteWidth.toLong)(Math.multiplyExact)
        val result = tensor.copy(shape = shape, dataRange = ByteRange(ByteOffset(cursor), ByteCount(length)))
        cursor += length
        result
      }
      val header = tensors.foldLeft(loaded.shards.head.originalHeader.asObject.get) { (fields, tensor) =>
        fields.add(tensor.name, fields(tensor.name).get.mapObject(_.add("shape", Json.fromValues(tensor.shape.map(Json.fromLong)))
          .add("data_offsets", Json.arr(Json.fromLong(tensor.dataRange.start.longValue), Json.fromLong(tensor.dataRange.endExclusive.longValue)))))
      }
      Files.write(input.resolve("input.safetensors"), SafetensorsIO.encodeHeaderJson(Json.fromJsonObject(header)) ++ (0 until cursor.toInt).map(_.toByte).toArray)
      Files.writeString(input.resolve("config.json"), loaded.config.original.mapObject(_.add("vocab_size", Json.fromInt(rows))).noSpaces)
      Files.writeString(input.resolve("model.safetensors.index.json"), loaded.index.original.mapObject(fields => fields.add("metadata", fields("metadata").get.mapObject(_.add("total_size", Json.fromLong(cursor))))).noSpaces)
      val settings = PolicyFile(CharacterPolicySettings(forbiddenScripts = Set.empty, pruneHanOutsideReference = false))
      val options = ProcessingOptions(outputRoot = output, transfer = FileTransfer.copying(FileTransfer.platform))
      val plan = CheckpointProcessor.plan(input, settings, output.resolve("runs/noop"), options)
      assertEquals(PlanStore.loadVerified(plan).domain.removedRows, 0)
      val result = CheckpointProcessor.applyPlan(plan, output.resolve("models/noop"), ProcessingFixtures.reference, options)
      assertEquals(FileIO.sha256(result.resolve("input.safetensors")), FileIO.sha256(input.resolve("input.safetensors")))
    }
  }

  test("changed kanji source hashes prevent application even when the glyph set is unchanged") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val reference = Files.createDirectory(directory.resolve("kanji"))
      Vector("JIS漢字.csv", "常用漢字.csv", "教育漢字.csv", "子の名に使える漢字.csv", "常用漢字表本表.json", "漢検漢字辞典漢字.csv")
        .foreach(name => Files.copy(kanji.resolve(name), reference.resolve(name)))
      val plan = CheckpointProcessor.plan(input, PolicyFile(CharacterPolicySettings(japaneseReference = reference)), output.resolve("runs/kanji"), ProcessingOptions(outputRoot = output))
      val path = reference.resolve("常用漢字表本表.json")
      val records = JsonIO.read(path).asArray.get
      Files.writeString(path, Json.fromValues(records.updated(0, records.head.mapObject(_.add("fixtureChange", Json.True)))).noSpaces)
      intercept[DataException](PlanStore.loadVerified(plan))
    }
  }

  test("output paths cannot escape, overlap inputs, follow symlinks or overwrite directories") {
    ProcessingFixtures.withModel { (directory, input, output) =>
      val options = ProcessingOptions(outputRoot = output)
      intercept[DataException](CheckpointProcessor.plan(input, default, directory.resolve("outside"), options))
      Files.createDirectories(output)
      Files.createSymbolicLink(output.resolve("linked"), input)
      intercept[DataException](CheckpointProcessor.plan(input, default, output.resolve("linked/run"), options))
      CheckpointProcessor.inspect(input, output.resolve("runs/inspect"), options)
      intercept[DataException](CheckpointProcessor.inspect(input, output.resolve("runs/inspect"), options))
      intercept[DataException](ProcessingIO.relative(input, "../outside"))
      intercept[DataException](ProcessingIO.relative(input, "/absolute"))
    }
  }

  test("clone creates an independent inode, and binary comparison catches a wrong surviving byte") {
    ProcessingFixtures.withModel { (_, input, output) =>
      Files.createDirectories(output)
      val source = input.resolve("README.md")
      val dest = output.resolve("copy.md")
      val method = BinaryTransfer.copyFile(source, dest)
      assert(Set("clonefile", "ficlone", "buffered-copy")(method))
      assert(!Files.isSameFile(source, dest))
      Files.writeString(dest, "changed independently")
      assertEquals(Files.readString(source), "Synthetic checkpoint fixture\n")
      intercept[DataException](BinaryTransfer.copyFile(source, dest))
      val plan = PlanStore.prepare(input, default)
      val shard = plan.domain.shards.head
      val checkpoint = output.resolve("projected.safetensors")
      BinaryTransfer.rewrite(shard, checkpoint, 3)
      assertEquals(BinaryTransfer.verify(shard, checkpoint, 3).map(_.comparedBytes).sum, shard.payloadBytes.longValue)
      Using.resource(FileChannel.open(checkpoint, StandardOpenOption.WRITE)) { channel =>
        channel.write(ByteBuffer.wrap(Array[Byte](1)), SafetensorsIO.read(checkpoint).payloadStart.longValue + 1L)
      }
      intercept[DataException](BinaryTransfer.verify(shard, checkpoint, 3))
    }
  }

  test("sparse source range beyond 2 GiB rewrites without payload-sized allocation") {
    val directory = Files.createTempDirectory("vocab-large-projection-")
    val path = directory.resolve("input.safetensors")
    val filler = TensorMetadata("body", TensorDType.U8, Vector(3L * 1024 * 1024 * 1024), ByteRange(ByteOffset(0), ByteCount(3L * 1024 * 1024 * 1024)))
    val rows = TensorMetadata("rows", TensorDType.U8, Vector(3L, 2L), ByteRange(filler.dataRange.endExclusive, ByteCount(6)))
    try
      val header = SafetensorsIO.encodeHeader(Vector(filler, rows))
      Using.resource(FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) { channel =>
        channel.write(ByteBuffer.wrap(header))
        channel.write(ByteBuffer.wrap(Array[Byte](1, 2, 3, 4, 5, 6)), header.length.toLong + rows.dataRange.start.longValue)
      }
      val metadata = SafetensorsIO.read(path)
      val projection = me.nsmr.vocabsurgery.tokenizer.VocabularyProjection.fromSurvivors(3, Vector(OriginalTokenId(2)))
      val onlyRows = TensorOperations.projectRows(rows, projection, ByteOffset(0))
      val plannedHeader = Json.obj("rows" -> Json.obj("dtype" -> Json.fromString("U8"), "shape" -> Json.arr(Json.fromInt(1), Json.fromInt(2)), "data_offsets" -> Json.arr(Json.fromInt(0), Json.fromInt(2))))
      val plan = ShardProjection(metadata, Vector(onlyRows), plannedHeader, ByteCount(2), true)
      val target = directory.resolve("output.safetensors")
      BinaryTransfer.rewrite(plan, target, 1)
      val written = Files.readAllBytes(target)
      assertEquals(written.takeRight(2).toVector, Vector[Byte](5, 6))
      assertEquals(BinaryTransfer.verify(plan, target, 1).head.comparedBytes, 2L)
    finally Using.resource(Files.list(directory))(_.iterator().asScala.foreach(Files.delete))
      Files.delete(directory)
  }
