//SEE https://www.scala-sbt.org/1.0/docs/Testing-sbt-plugins.html
sys.props.get("plugin.version") match {
  case Some(x) => addSbtPlugin("app.fmgp.sbt" % "sbt-github-client" % x)
  case _ => sys.error("""|The system property 'plugin.version' is not defined.
                         |Specify this property using the scriptedLaunchOpts -D.""".stripMargin)
}
//addSbtPlugin("app.fmgp.sbt" % "sbt-github-client" % System.getProperty("plugin.version"))

addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "0.14.9")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.0.0")
