package app.fmgp

import java.io.File
import java.util

import org.eclipse.jgit
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.lib.{ObjectReader, Repository}
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.{AbstractTreeIterator, CanonicalTreeParser, FileTreeIterator}

import collection.JavaConverters._

object MainGit {
  val repositoryBuilder: FileRepositoryBuilder = new FileRepositoryBuilder()
  val repository: Repository = {
    repositoryBuilder
      .setGitDir(new File("/home/fabio/workspace/github-client/.git"))
      .readEnvironment() // scan environment GIT_* variables
      .findGitDir() // scan up the file system tree
      .setMustExist(true)
      .build()
  }
  val git = new Git(repository)

  def edits = {
    val formatter: MyDiffFormatter = MyDiffFormatter(repository: Repository)
    val walk: RevWalk = new RevWalk(repository)
    val currentWorkingTree: AbstractTreeIterator = new FileTreeIterator(repository)

    val headTree = walk.parseTree(walk.parseCommit(repository.resolve(jgit.lib.Constants.HEAD)))

    val oldTreeParser : CanonicalTreeParser = new CanonicalTreeParser()
    val oldReader: ObjectReader = repository.newObjectReader()
    try {
      oldTreeParser.reset(oldReader, headTree.getId())
    } finally {
      oldReader.close()
    }

    val entries: util.List[DiffEntry] = formatter.scan(oldTreeParser, currentWorkingTree)
    entries.asScala.map(e => formatter.format(e)).toList
    DiffFormatResult.hack
  }

  def main(args: Array[String]): Unit = {
    println("HI MainGit test")
    //println(edits.toEditList().toString)
    edits.foreach(println)
  }
}
