ThisBuild / scalaVersion := "3.1.1"
ThisBuild / organization := "ir.ashkan"

lazy val root =
  (project in file("."))
    .settings(
      scalacOptions ++= Seq("-Ykind-projector:underscores", "-source:future"),
      name := "av1 decoder",
      libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-core" % "2.7.0",
        "org.typelevel" %% "cats-effect" % "3.3.9",
        "co.fs2" %% "fs2-core" % "3.2.6",
        "co.fs2" %% "fs2-io" % "3.2.6",
        "co.fs2" %% "fs2-scodec" % "3.2.6"
      )
    )