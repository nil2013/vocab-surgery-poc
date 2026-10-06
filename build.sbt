ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "me.nsmr"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val stageCli = taskKey[File]("Stage the CLI and runtime dependency jars under target/cli")

lazy val root = project.in(file(".")).settings(
  name := "vocab-surgery",
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror"),
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-core" % "0.14.16",
    "io.circe" %% "circe-jawn" % "0.14.16",
    "org.apache.commons" % "commons-csv" % "1.14.1",
    "com.ibm.icu" % "icu4j" % "78.3",
    "net.java.dev.jna" % "jna" % "5.18.1",
    "com.github.scopt" %% "scopt" % "4.1.0",
    "org.scalameta" %% "munit" % "1.0.4" % Test,
    "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test
  ),
  Test / fork := true,
  Compile / mainClass := Some("me.nsmr.vocabsurgery.cli.Main"),
  stageCli := Def.uncached {
    val converter = fileConverter.value
    val app = converter.toPath((Compile / packageBin).value).toFile
    val jars = (app +: (Runtime / dependencyClasspath).value.map(entry => converter.toPath(entry.data).toFile).filter(_.isFile)).distinct
    require(jars.map(_.getName).distinct.size == jars.size, "duplicate runtime jar filenames")
    val destination = baseDirectory.value / "target" / "cli" / "lib"
    IO.delete(destination)
    IO.createDirectory(destination)
    jars.foreach(jar => IO.copyFile(jar, destination / jar.getName))
    val compiler = baseDirectory.value / "target" / "cli" / "compiler"
    IO.delete(compiler)
    IO.createDirectory(compiler)
    scalaInstance.value.allJars.foreach(jar => IO.copyFile(jar, compiler / jar.getName))
    streams.value.log.info(s"Staged CLI at $destination; use scripts/vocab-surgery --help")
    destination
  },
  Test / javaOptions ++= Seq("-Xmx512m", "--enable-native-access=ALL-UNNAMED")
)
