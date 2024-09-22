name := "akka-tcp-chat"
version := "0.0.1-SNAPSHOT"
scalaVersion := "2.13.14"
startYear := Some(2024)
developers := List(Developer("haghard", "Vadim Bondarev", "hagard84@gmail.com", url("https://github.com/haghard")))
licenses += ("Apache-2.0", new URL("https://www.apache.org/licenses/LICENSE-2.0.txt"))

val AkkaVersion = "2.6.21"

//https://repo1.maven.org/maven2/com/lihaoyi/ammonite-compiler_3.3.1/3.0.0-M2-3-b5eb4787/
val AmmoniteVersion = "3.0.0-M2-3-b5eb4787"

scalacOptions := Seq(
  //"-Xsource:3",
  "-Xsource:3-cross",
  "-language:experimental.macros",
  "-release:17",
  "-deprecation",
  "-feature",
  "-unchecked",
  "-Yrangepos",
  "-Xlog-reflective-calls",
  "-Xlint",
  // "-Wunused:imports",
  //"-Xfatal-warnings",
  // Generated code for methods/fields marked 'deprecated'
  "-Wconf:msg=Marked as deprecated in proto file:silent",
  // silence pb
  s"-Wconf:src=${(Compile / target).value}/scala-2.13/src_managed/.*:silent",
  "-Wconf:cat=other-match-analysis:error" // Transform exhaustivity warnings into errors.
)

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor-typed" % AkkaVersion,
  "com.typesafe.akka" %% "akka-stream" % AkkaVersion,

  "com.typesafe.akka" %% "akka-slf4j"      % AkkaVersion,
  "ch.qos.logback"     % "logback-classic" % "1.2.13",

  "com.github.bastiaanjansen" % "otp-java" % "2.0.3",

  "com.lihaoyi"  % "ammonite" % AmmoniteVersion % "test" cross CrossVersion.full,

  "com.typesafe.akka" %% "akka-testkit" % AkkaVersion % Test,
  "org.scalatest" %% "scalatest" % "3.2.19" % Test
)

run / fork := true //false for ammonite
run / connectInput := true

javaOptions ++= Seq(
  "-XX:+PrintCommandLineFlags",
  //"-XX:+PrintFlagsFinal",
  //"-XshowSettings:system -version",
  "-Xms128m",
  "-Xmx256m",
  "-XX:+UseZGC",
  "--add-opens",
  "java.base/sun.nio.ch=ALL-UNNAMED"
)

buildInfoPackage := "akkastreamchat"
buildInfoKeys := Seq[BuildInfoKey](
  version,
  scalaVersion,
  sbtVersion,
  "gitHash" -> SbtUtils.fullGitHash.getOrElse(""),
  "gitBranch" -> SbtUtils.branch.getOrElse(""),
)

enablePlugins(BuildInfoPlugin)


Compile / PB.targets := Seq(scalapb.gen() -> (Compile / sourceManaged).value)
libraryDependencies += "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"

scalafmtOnCompile := true
scalafixOnCompile := true

ThisBuild / scalafixDependencies ++= Seq(
  "com.nequissimus" %% "sort-imports" % "0.6.1",
  "org.scala-lang" %% "scala-rewrites" % "0.1.5",
)

Global / semanticdbEnabled := true
Global / semanticdbVersion := scalafixSemanticdb.revision
Global / watchAntiEntropy := scala.concurrent.duration.FiniteDuration(5, java.util.concurrent.TimeUnit.SECONDS)


promptTheme := ScalapenosTheme

// ammonite repl
Test / sourceGenerators += Def.task {
  val file = (Test / sourceManaged).value / "amm.scala"
  IO.write(file, """object amm extends App { ammonite.Main().run() }""")
  Seq(file)
}.taskValue

addCommandAlias("c", "compile")
addCommandAlias("r", "reload")
addCommandAlias("sfix", "scalafix OrganizeImports; test:scalafix OrganizeImports")
