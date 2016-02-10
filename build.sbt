organization  := "com.example"

version       := "0.1"

scalaVersion  := "2.11.7"

scalacOptions := Seq("-unchecked", "-deprecation", "-encoding", "utf8")

javacOptions ++= Seq("-source", "1.8", "-target", "1.8")

resolvers += "Typesafe Releases" at "http://repo.typesafe.com/typesafe/releases/"

libraryDependencies ++= {
  val akkaV = "2.4.1"
  val sprayV = "1.3.3"
  Seq(
    "io.spray"            %%  "spray-can"       % sprayV,
    "io.spray"            %%  "spray-routing"   % sprayV,
    "io.spray"            %%  "spray-json"      % "1.3.2",
    "com.typesafe.play"   %%  "play-json"       % "2.4.6",
    "io.spray"            %%  "spray-testkit"   % sprayV    % "test",
    "org.specs2"          %%  "specs2-core"     % "3.7"  % "test",
    "com.typesafe.akka" %% "akka-contrib" % akkaV,
    "com.typesafe.akka" %% "akka-cluster-sharding" % akkaV,
    "com.typesafe.akka" %% "akka-multi-node-testkit" % akkaV,
    "com.typesafe.akka" %% "akka-persistence-query-experimental" % akkaV,
    "org.iq80.leveldb"            % "leveldb"          % "0.7",
    "org.fusesource.leveldbjni"   % "leveldbjni-all"   % "1.8",
    "org.scalatest" %% "scalatest" % "2.2.6" % "test"
  )
}

Revolver.settings
