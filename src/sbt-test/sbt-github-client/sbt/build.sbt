import scala.sys.process.Process

lazy val root = (project in file("."))
  .enablePlugins(FabioPlugin)
  .enablePlugins(AssemblyPlugin)
  .settings(
    version := "0.1",
    sourceZipDir := crossTarget.value,
    assemblyJarName in assembly := "foo.jar",
    TaskKey[Unit]("check") := {
      val process = Process("java", Seq("-jar", (crossTarget.value / "foo.jar").toString))
      val out = (process!!)
      if (out.trim != "hello") sys.error("unexpected output: " + out)
      ()
    }
  )
