val circeVersion = "0.10.0"
//resolvers += "Artifactory" at "http://<host>:<port>/artifactory/<repo-key>/"
//https://dl.bintray.com/sbt/sbt-plugin-releases/
resolvers += "Artifactory" at "https://dl.bintray.com/sbt-plugin-releases/"
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.0") //needed as a library dependency

addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")

useJGit

lazy val root = (project in file("."))
  .enablePlugins(SbtPlugin)
  .enablePlugins(GitBranchPrompt)
  .settings(
    scalaVersion := "2.12.8",
    name := "sbt-github-client",
    organization := "app.fmgp.sbt",
    version := "0.1-SNAPSHOT",
    //Because of sbt plugin
    //SEE https://medium.com/@phkadam2008/write-test-your-own-scala-sbt-plugin-6701b0e36a62
    //SEE https://www.scala-sbt.org/1.0/docs/Testing-sbt-plugins.html
    sbtPlugin := true,
    scriptedLaunchOpts := {
      scriptedLaunchOpts.value ++
        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
    },
    scriptedBufferLog := false,
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion,
      "org.eclipse.jgit" % "org.eclipse.jgit" % "4.9.0.201710071750-r",
      "org.sangria-graphql" %% "sangria" % "1.4.2",
      "org.sangria-graphql" %% "sangria-circe" % "1.2.1",
      "com.typesafe.akka" %% "akka-actor" % "2.5.22",
      "com.typesafe.akka" %% "akka-stream" % "2.5.22",
      "com.typesafe.akka" %% "akka-http" % "10.1.8",
      "com.typesafe.akka" %% "akka-http2-support" % "10.1.8",
      //"com.geirsson" %% "com.geirsson" % "2.0.0-RC1",
    )
  )

//
//lazy val root = (project in file("."))
//  .enablePlugins(SbtPlugin)
//  .settings(
//      name := "sbt-github-client",
//      organization := "app.fmgp.sbt",
//      version := "0.1-SNAPSHOT",
//      sbtPlugin := true,
//      sbtPlugin := true,
//      scriptedLaunchOpts := { scriptedLaunchOpts.value ++
//        Seq("-Xmx1024M", "-Dplugin.version=" + version.value)
//      },
//
//      //    scriptedLaunchOpts := { scriptedLaunchOpts.value.filter(
//      //      a => Seq("-Xmx", "-Xms", "-XX", "-Dsbt.log.noformat").exists(a.startsWith)
//      //    ) ++ Seq("-Xmx1024M", "-Dplugin.version=" + version.value)},
//      scriptedBufferLog := false
//  )
