addSbtPlugin("org.jetbrains.scala" % "sbt-ide-settings" % "1.1.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.5.0")

// look for syntax anti-patterns at compile time and support programmatic rewrites
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.11.0")

// look for functional anti-patterns at compile time
addSbtPlugin("org.wartremover" % "sbt-wartremover" % "3.1.3")

// auto import useful scala compiler flags TODO: Disable until test code is clean/compiler settings nitpicked
//addSbtPlugin("io.github.davidgregory084" % "sbt-tpolecat" % "0.4.2")
