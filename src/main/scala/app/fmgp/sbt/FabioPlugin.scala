package app.fmgp.sbt

import java.io.PrintWriter
import java.nio.file.Path

import org.scalafmt.interfaces.Scalafmt
import org.scalafmt.sbt.ScalafmtPlugin.autoImport.scalafmtConfig
import org.scalafmt.sbt.{ScalafmtPlugin, ScalafmtSbtReporter}
import sbt.Keys._
import sbt.util.{CacheStoreFactory, FileInfo, FilesInfo, Logger}
import sbt._

object FabioPlugin extends AutoPlugin {
  override val trigger: PluginTrigger = noTrigger
  override val requires: Plugins = plugins.JvmPlugin && ScalafmtPlugin
  object autoImport extends FabioKeys
  import autoImport._

  override lazy val projectSettings: Seq[Setting[_]] = Seq(
    targetZipDir := target.value / "zip",
    zip := zipTask.value,
    //scalafmtGitHub := scalafmtGitHubTask.value
  ) ++ Seq(Compile, Test).flatMap(inConfig(_)(scalafmtGitHubConfigSettings))

  private def zipTask = Def.task {
    val log = sLog.value
    lazy val zip = new File(targetZipDir.value, sourceZipDir.value.getName + ".zip")
    log.info("Zipping file...")
    IO.zip(sbt.Path.allSubpaths(sourceZipDir.value), zip)
    zip
  }

  private val scalaConfig = {
    scalafmtConfig.map { f =>
      if (f.exists()) {
        f.toPath
      } else {
        throw new MessageOnlyException(s"File not exists: ${f.toPath}")
      }
    }
  }
  private val sbtConfig = scalaConfig

  private type Input = String
  private type Output = String

  private lazy val sbtSources = thisProject.map { proj =>
    val rootSbt =
      BuildPaths.configurationSources(proj.base).filterNot(_.isHidden)
    val projectSbt =
      (BuildPaths.projectStandard(proj.base) * GlobFilter("*.sbt")).get.filterNot(_.isHidden)
    rootSbt ++ projectSbt
  }
  private lazy val projectSources = thisProject.map { proj =>
    (BuildPaths.projectStandard(proj.base) * GlobFilter("*.scala")).get
  }

  val globalInstance = Scalafmt.create(this.getClass.getClassLoader)

  private def withFormattedSources[T](
    sources: Seq[File],
    config: Path,
    log: Logger,
    writer: PrintWriter
  )(
    onError: (File, Throwable) => T,
    onFormat: (File, Input, Output) => T
  ): Seq[Option[T]] = {
    val reporter = new ScalafmtSbtReporter(log, writer)
    val scalafmtInstance = globalInstance.withReporter(reporter)
    sources.map { file =>
      val input = IO.read(file)
      val output =
        scalafmtInstance.format(
          config.toAbsolutePath,
          file.toPath.toAbsolutePath,
          input
        )
      Some(onFormat(file, input, output))
    }
  }

  private def formatSources(
    sources: Seq[File],
    config: Path,
    log: Logger,
    writer: PrintWriter
  ): Unit = {
    log.info(s"111111 - $sources")
    log.info(s"222222 - $config")
    log.info(s"333333 - $writer")

    val cnt = withFormattedSources(sources, config, log, writer)(
      (file, e) => {
        log.err(e.toString)
        0
      },
      (file, input, output) => {
        if (input != output) {
          IO.write(file, output)
          1
        } else {
          0
        }
      }
    ).flatten.sum

    if (cnt > 1) {
      log.info(s"Reformatted $cnt Scala sources")
    }
    log.info("HI Fabio")
  }

  private def formatSources(
    cacheDirectory: File,
    sources: Seq[File],
    config: Path,
    log: Logger,
    writer: PrintWriter
  ): Unit = {
    cached(cacheDirectory, FilesInfo.lastModified) { modified =>
      val changed = modified.filter(_.exists)
      if (changed.size > 0) {
        log.info(s"Formatting ${changed.size} Scala sources...")
        formatSources(changed.toSeq, config, log, writer)
      }
    }(sources.toSet).getOrElse(())
  }

  private def cached[T](cacheBaseDirectory: File, inStyle: FileInfo.Style)(
    action: Set[File] => T
  ): Set[File] => Option[T] = {
    lazy val inCache = Difference.inputs(
      CacheStoreFactory(cacheBaseDirectory).make("in-cache"),
      inStyle
    )
    inputs => {
      inCache(inputs) { inReport =>
        if (!inReport.modified.isEmpty) Some(action(inReport.modified))
        else None
      }
    }
  }

  lazy val scalafmtGitHubConfigSettings: Seq[Def.Setting[_]] = Seq(
    scalafmtGitHub := formatSources(
      streams.value.cacheDirectory,
      (unmanagedSources in scalafmtGitHub).value,
      scalaConfig.value,
      streams.value.log,
      streams.value.text()
    )
  )
}
