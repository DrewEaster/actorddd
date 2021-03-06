organization  := "com.example"

version       := "0.1"

scalaVersion  := "2.11.2"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= {
  val akkaV = "2.3.6"
  val sprayV = "1.3.2"
  Seq(
    "io.spray"            %%  "spray-can"       % sprayV,
    "io.spray"            %%  "spray-routing"   % sprayV,
    "io.spray"            %%  "spray-json"      % "1.3.1",
    "com.typesafe.play"   %%  "play-json"       % "2.3.8",
    "io.spray"            %%  "spray-testkit"   % sprayV    % "test",
    "org.specs2"          %%  "specs2-core"     % "2.3.11"  % "test",
    "com.typesafe.akka" %% "akka-contrib" % akkaV,
    "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaV,
    "org.scalatest" %% "scalatest" % "2.1.6" % "test"
  )
}

Revolver.settings
