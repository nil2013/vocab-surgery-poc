ThisBuild / scalaVersion := "3.3.8"
ThisBuild / organization := "me.nsmr"
ThisBuild / version := "0.1.0-SNAPSHOT"

lazy val root = project.in(file(".")).settings(
  name := "vocab-surgery",
  scalacOptions ++= Seq("-deprecation", "-feature", "-unchecked", "-Werror"),
  libraryDependencies ++= Seq(
    "io.circe" %% "circe-core" % "0.14.16",
    "io.circe" %% "circe-jawn" % "0.14.16",
    "org.apache.commons" % "commons-csv" % "1.14.1",
    "com.ibm.icu" % "icu4j" % "78.3",
    "net.java.dev.jna" % "jna" % "5.18.1",
    "org.scalameta" %% "munit" % "1.0.4" % Test,
    "org.scalameta" %% "munit-scalacheck" % "1.0.0" % Test
  ),
  Test / fork := true,
  Test / javaOptions ++= Seq("-Xmx512m", "--enable-native-access=ALL-UNNAMED")
)
