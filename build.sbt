ThisBuild / version := "0.1.0-SNAPSHOT"

ThisBuild / scalaVersion := "2.13.11"

ThisBuild / startYear := Some(2023)

lazy val root = (project in file("."))
  .aggregate(core, demo)

lazy val core = (project in file("core"))
  // main source
  .settings(
    name := "netty-4-cats",
    idePackagePrefix := Some("cats.netty"),
    libraryDependencies ++= Seq(
      "io.netty" % "netty-all" % "4.1.93.Final",
      "org.typelevel" %% "cats-effect" % "3.4.11",
      "org.slf4j" % "slf4j-api" % "2.0.7",
      "eu.timepit" %% "refined" % "0.10.3",
    )
  )
  // test
  .settings(
    libraryDependencies ++= Seq(
      "org.scalatest" %% "scalatest" % "3.2.16" % Test,
      "ch.qos.logback" % "logback-classic" % "1.3.8" % Test,
      "net.logstash.logback" % "logstash-logback-encoder" % "7.3" % Test,
    ),
    Test / testOptions +=
      Tests.Argument(TestFrameworks.ScalaTest, "-oD"),
    Test / fork := true,
  )
  // linting
  .settings(
    inThisBuild(
      Seq(
        scalafixScalaBinaryVersion := "2.13",
        addCompilerPlugin(scalafixSemanticdb)
      )
    ),
    Compile / compile / wartremoverErrors := Warts.unsafe
      .filterNot(_ == Wart.Any) ++ Seq(
      Wart.FinalCaseClass,
      Wart.ExplicitImplicitTypes
    ),
    Test / compile / wartremoverErrors := Warts.unsafe
      .filterNot(_ == Wart.Any) ++ Seq(
      Wart.FinalCaseClass,
      Wart.ExplicitImplicitTypes
    ),
    wartremoverExcluded += sourceManaged.value,
    scalacOptions += "-Ywarn-unused"
  )

lazy val demo = (project in file("demo"))
  .dependsOn(core)
  .settings(
    name := "demo",
    idePackagePrefix := Some("cats.netty"),
  )
  // linting
  .settings(
    inThisBuild(
      Seq(
        scalafixScalaBinaryVersion := "2.13",
        addCompilerPlugin(scalafixSemanticdb)
      )
    ),
    Compile / compile / wartremoverErrors := Warts.unsafe
      .filterNot(_ == Wart.Any) ++ Seq(
      Wart.FinalCaseClass,
      Wart.ExplicitImplicitTypes
    ),
    wartremoverExcluded += sourceManaged.value,
    scalacOptions += "-Ywarn-unused"
  )
