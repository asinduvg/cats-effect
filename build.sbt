ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "3.3.1"

lazy val root = (project in file("."))
  .settings(
    name := "cats-effect",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect" % "3.5.0"
    )
  )
