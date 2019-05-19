package app.fmgp

import app.fmgp.DiffFormatResult.SingleDiff

import java.io.{ByteArrayOutputStream, IOException, OutputStream}
import java.util
import java.util.{Collections, List}

import org.eclipse.jgit
import org.eclipse.jgit.diff.DiffAlgorithm.SupportedAlgorithm
import org.eclipse.jgit.diff.DiffEntry.ChangeType
import org.eclipse.jgit.diff.DiffEntry.ChangeType.{ADD, COPY, DELETE, RENAME}
import org.eclipse.jgit.diff.{ContentSource, DiffAlgorithm, DiffConfig, DiffEntry, Edit, EditList, RawText, RawTextComparator, RenameDetector}
import org.eclipse.jgit.dircache.DirCacheIterator
import org.eclipse.jgit.errors.{AmbiguousObjectException, CorruptObjectException, LargeObjectException, MissingObjectException}
import org.eclipse.jgit.internal.JGitText
import org.eclipse.jgit.lib.Constants.{encode, encodeASCII}
import org.eclipse.jgit.lib.{AbbreviatedObjectId, Config, ConfigConstants, FileMode, ObjectId, ObjectReader, Repository}
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.patch.FileHeader.PatchType
import org.eclipse.jgit.revwalk.FollowFilter
import org.eclipse.jgit.storage.pack.PackConfig
import org.eclipse.jgit.treewalk.{AbstractTreeIterator, TreeWalk, WorkingTreeIterator}
import org.eclipse.jgit.treewalk.filter.{AndTreeFilter, IndexDiffFilter, NotIgnoredFilter, PathFilter, TreeFilter}
import org.eclipse.jgit.util.QuotedString
import collection.JavaConverters._

object DiffFormatResult {
  case class SingleDiff(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int, newTest: String)
  var hack: Seq[SingleDiff] = Seq[SingleDiff]()
}

object MyDiffUtils {
  implicit class DiffEntryImprovements(val o: DiffEntry) {
    //def oldId_=(value: AbbreviatedObjectId):Unit = o.oldId = value
    def oldIdX: AbbreviatedObjectId = o.getOldId

    //def newIdX_=(value: AbbreviatedObjectId):Unit = o.newId = value
    def newIdX: AbbreviatedObjectId = o.getNewId
  }

}

case class MyFormatResult(header: FileHeader, a: Option[RawText], b: Option[RawText])


case class MyDiffFormatter(repository: Repository) { //extends DiffFormatter(out) {
  import MyDiffUtils._

  def binaryFileThreshold_ = PackConfig.DEFAULT_BIG_FILE_THRESHOLD //o.binaryFileThreshold
  val out: OutputStream = new OutputStream {def write(b: Int): Unit = ()} //DisabledOutputStream.INSTANCE //(System.out)
  val reader: ObjectReader = repository.newObjectReader
  var _source: ContentSource.Pair = {
    val cs = ContentSource.create(reader)
    new ContentSource.Pair(cs, cs)
  }
  var _pathFilter: TreeFilter = TreeFilter.ALL
  var renameDetector: RenameDetector = _
  val closeReader: Boolean = false
  val cfg: Config = repository.getConfig
  var diffCfg: DiffConfig = cfg.get(DiffConfig.KEY)
  setDetectRenames(diffCfg.isRenameDetectionEnabled)
  def setDetectRenames(on: Boolean): Unit = {
    if (on && renameDetector == null) {
      assertHaveReader
      renameDetector = new RenameDetector(reader, diffCfg)
    } else if (!on) renameDetector = null
  }

  val diffAlgorithm: DiffAlgorithm = DiffAlgorithm.getAlgorithm(cfg.getEnum(ConfigConstants.CONFIG_DIFF_SECTION, null, ConfigConstants.CONFIG_KEY_ALGORITHM, SupportedAlgorithm.HISTOGRAM))
  var oldPrefix = "a/"
  var newPrefix = "b/"
  var abbreviationLength = 7
  private val context = 3
  private val noNewLine = encodeASCII("\\ No newline at end of file\n") //$NON-NLS-1$

  private val comparator = RawTextComparator.DEFAULT
  private def diff(a: RawText, b: RawText) = {
    val edits = diffAlgorithm.diff(comparator, a, b)
    edits.asScala.toArray[Edit].foreach { edit =>
      val before = (edit.getBeginA, edit.getEndA)
      val after = (edit.getBeginB, edit.getEndB)
      val text = (after._1 to math.max(after._1, after._2 - 1)).map(line => b.getString(line)).mkString("\n")
      DiffFormatResult.hack = DiffFormatResult.hack :+ SingleDiff(
        oldStart = before._1,
        oldEnd = before._2,
        newStart = after._1,
        newEnd = after._2,
        text
      )
    }
    edits
  }

  private def format(id: AbbreviatedObjectId) = {
    if (id.isComplete && reader != null)
      try {
        reader.abbreviate(id.toObjectId, abbreviationLength).name
      } catch {
        case cannotAbbreviate: IOException =>
          id.name // Ignore this. We'll report the full identity.
      } else id.name
  }

  def format(ent: DiffEntry): Unit = {
    val res = createFormatResult(ent)
    format(res.header, res.a.orNull, res.b.orNull)
  }

  @throws[IOException]
  private def format(head: FileHeader, a: RawText, b: RawText): Unit = { // Reuse the existing FileHeader as-is by blindly copying its
    // header lines, but avoiding its hunks. Instead we recreate
    // the hunks from the text instances we have been supplied.
    //
    val start = head.getStartOffset
    var end = head.getEndOffset
    if (!head.getHunks.isEmpty) end = head.getHunks.get(0).getStartOffset
    out.write(head.getBuffer, start, end - start)
    if (head.getPatchType eq PatchType.UNIFIED) format(head.toEditList, a, b)
  }

  @throws[IOException]
  private def writeHunkHeader(aStartLine: Int, aEndLine: Int, bStartLine: Int, bEndLine: Int): Unit = {
    out.write('@')
    out.write('@')
    writeRange('-', aStartLine + 1, aEndLine - aStartLine)
    writeRange('+', bStartLine + 1, bEndLine - bStartLine)
    out.write(' ')
    out.write('@')
    out.write('@')
    out.write('\n')
  }

  @throws[IOException]
  private def writeRange(prefix: Char, begin: Int, cnt: Int): Unit = {
    out.write(' ')
    out.write(prefix)
    cnt match {
      case 0 =>
        // If the range is empty, its beginning number must be the
        // line just before the range, or 0 if the range is at the
        // start of the file stream. Here, begin is always 1 based,
        // so an empty file would produce "0,0".
        //
        out.write(encodeASCII(begin - 1))
        out.write(',')
        out.write('0')
      case 1 =>
        // If the range is exactly one line, produce only the number.
        out.write(encodeASCII(begin))
      case _ =>
        out.write(encodeASCII(begin))
        out.write(',')
        out.write(encodeASCII(cnt))
    }
  }

  private def findCombinedEnd(edits: util.List[Edit], i: Int) = {
    var end = i + 1
    while ({
      end < edits.size && (combineA(edits, end) || combineB(edits, end))
    }) {
      end += 1; end - 1
    }
    end - 1
  }

  private def combineA(e: util.List[Edit], i: Int) = e.get(i).getBeginA - e.get(i - 1).getEndA <= 2 * context

  private def combineB(e: util.List[Edit], i: Int) = e.get(i).getBeginB - e.get(i - 1).getEndB <= 2 * context

  private def end(edit: Edit, a: Int, b: Int) = edit.getEndA <= a && edit.getEndB <= b

  @throws[IOException]
  private def writeContextLine(text: RawText, line: Int): Unit = {
    writeLine(' ', text, line)
  }

  private def isEndOfLineMissing(text: RawText, line: Int) = line + 1 == text.size && text.isMissingNewlineAtEnd

  @throws[IOException]
  private def writeRemovedLine(text: RawText, line: Int): Unit = {
    writeLine('-', text, line)
  }

  @throws[IOException]
  private def writeLine(prefix: Char, text: RawText, cur: Int): Unit = {
    out.write(prefix)
    text.writeLine(out, cur)
    out.write('\n')
  }

  @throws[IOException]
  private def writeAddedLine(text: RawText, line: Int): Unit = {
    writeLine('+', text, line)
  }

  @throws[IOException]
  def format(edits: EditList, a: RawText, b: RawText): Unit = {
    var curIdx = 0
    while ({
      curIdx < edits.size
    }) {
      var curEdit = edits.get(curIdx)
      val endIdx = findCombinedEnd(edits, curIdx)
      val endEdit = edits.get(endIdx)
      var aCur = Math.max(0, curEdit.getBeginA.toLong - context).toInt
      var bCur = Math.max(0, curEdit.getBeginB.toLong - context).toInt
      val aEnd = Math.min(a.size, endEdit.getEndA.toLong + context).toInt
      val bEnd = Math.min(b.size, endEdit.getEndB.toLong + context).toInt
      writeHunkHeader(aCur, aEnd, bCur, bEnd)
      while ({
        aCur < aEnd || bCur < bEnd
      }) {
        if (aCur < curEdit.getBeginA || endIdx + 1 < curIdx) {
          writeContextLine(a, aCur)
          if (isEndOfLineMissing(a, aCur)) out.write(noNewLine)
          aCur += 1
          bCur += 1
        } else if (aCur < curEdit.getEndA) {
          writeRemovedLine(a, aCur)
          if (isEndOfLineMissing(a, aCur)) out.write(noNewLine)
          aCur += 1
        } else if (bCur < curEdit.getEndB) {
          writeAddedLine(b, bCur)
          if (isEndOfLineMissing(b, bCur)) out.write(noNewLine)
          bCur += 1
        }
        if (end(curEdit, aCur, bCur) && {
              curIdx += 1; curIdx
            } < edits.size) curEdit = edits.get(curIdx)
      }
    }
  }

  private def EMPTY = Array[Byte]()
  private def BINARY = Array[Byte]()

  @throws[IOException] // private
  def open(side: DiffEntry.Side, entry: DiffEntry): Array[Byte] = {
    if (entry.getMode(side) == FileMode.MISSING) EMPTY
    else if (entry.getMode(side).getObjectType() != jgit.lib.Constants.OBJ_BLOB) EMPTY
    else {
      var id = entry.getId(side)
      if (!id.isComplete()) {
        val ids: util.Collection[ObjectId] = ??? //TODO reader.resolve(id)
        if (ids.size() == 1) {
          id = AbbreviatedObjectId.fromObjectId(ids.iterator().next())
          ???
          //          side match {
          //            case DiffEntry.Side.OLD => entry.oldIdX = id
          //            case DiffEntry.Side.NEW => entry.newIdX = id
          //          }
        } else if (ids.size() == 0) throw new MissingObjectException(id, jgit.lib.Constants.OBJ_BLOB)
        else throw new AmbiguousObjectException(id, ids)
      }
      try {
        val ldr = _source.open(side, entry)
        ldr.getBytes(binaryFileThreshold_)
      } catch {
        case overLimit: LargeObjectException.ExceedsLimit => BINARY
        case overLimit: LargeObjectException.ExceedsByteArrayLimit => BINARY
        case tooBig: LargeObjectException.OutOfMemory => BINARY
        case tooBig: LargeObjectException =>
          tooBig.setObjectId(id.toObjectId)
          throw tooBig
      }
    }
  }

  private def quotePath(name: String) = QuotedString.GIT_PATH.quote(name)

  @throws[IOException]
  private def formatGitDiffFirstHeaderLine(o: ByteArrayOutputStream, `type`: DiffEntry.ChangeType, oldPath: String, newPath: String): Unit = {
    o.write(encodeASCII("diff --git ")) //$NON-NLS-1$

    o.write(
      encode(
        quotePath(
          oldPrefix + (if (`type` eq ADD) newPath
                       else oldPath)
        )
      )
    )
    o.write(' ')
    o.write(
      encode(
        quotePath(
          newPrefix + (if (`type` eq DELETE) oldPath
                       else newPath)
        )
      )
    )
    o.write('\n')
  }

  @throws[IOException]
  private def formatIndexLine(o: OutputStream, ent: DiffEntry): Unit = {
    o.write(encodeASCII("index " + format(ent.getOldId) + ".." + format(ent.getNewId)))
    if (ent.getOldMode == ent.getNewMode) {
      o.write(' ')
      ent.getNewMode.copyTo(o)
    }
    o.write('\n')
  }

  @throws[IOException] //private
  private def formatHeader(o: ByteArrayOutputStream, ent: DiffEntry): Unit = {
    import jgit.lib.Constants._

    val `type` = ent.getChangeType
    val oldp = ent.getOldPath
    val newp = ent.getNewPath
    val oldMode = ent.getOldMode
    val newMode = ent.getNewMode

    formatGitDiffFirstHeaderLine(o, `type`, oldp, newp)

    if ((`type` == ChangeType.MODIFY || `type` == ChangeType.COPY || `type` == ChangeType.RENAME) && !oldMode.equals(newMode)) {
      o.write(encodeASCII("old mode ")); //$NON-NLS-1$
      oldMode.copyTo(o)
      o.write('\n')
      o.write(encodeASCII("new mode ")); //$NON-NLS-1$
      newMode.copyTo(o)
      o.write('\n')
    }
    `type` match {
      case ChangeType.ADD =>
        o.write(encodeASCII("new file mode ")); //$NON-NLS-1$
        newMode.copyTo(o)
        o.write('\n')
      case ChangeType.DELETE =>
        o.write(encodeASCII("deleted file mode ")); //$NON-NLS-1$
        oldMode.copyTo(o)
        o.write('\n')
      case ChangeType.RENAME =>
        o.write(encodeASCII("similarity index " + ent.getScore() + "%")); //$NON-NLS-1$ //$NON-NLS-2$
        o.write('\n')
        o.write(encode("rename from " + quotePath(oldp))); //$NON-NLS-1$
        o.write('\n')
        o.write(encode("rename to " + quotePath(newp))); //$NON-NLS-1$
        o.write('\n')
      case ChangeType.COPY =>
        o.write(encodeASCII("similarity index " + ent.getScore() + "%")); //$NON-NLS-1$ //$NON-NLS-2$
        o.write('\n')
        o.write(encode("copy from " + quotePath(oldp))); //$NON-NLS-1$
        o.write('\n')
        o.write(encode("copy to " + quotePath(newp))); //$NON-NLS-1$
        o.write('\n')
      case ChangeType.MODIFY =>
        if (0 < ent.getScore) {
          o.write(
            encodeASCII(
              "dissimilarity index " //$NON-NLS-1$
                + (100 - ent.getScore) + "%"
            )
          ); //$NON-NLS-1$
          o.write('\n')
        }
    }

    if (ent.getOldId != null && !ent.getOldId.equals(ent.getNewId)) formatIndexLine(o, ent)

  }

  @throws[IOException]
  private def formatOldNewPaths(o: ByteArrayOutputStream, ent: DiffEntry): Unit = {
    if (ent.oldIdX == ent.newIdX) return
    var oldp: String = null
    var newp: String = null
    ent.getChangeType match {
      case ADD =>
        oldp = DiffEntry.DEV_NULL
        newp = quotePath(newPrefix + ent.getNewPath)
      case DELETE =>
        oldp = quotePath(oldPrefix + ent.getOldPath)
        newp = DiffEntry.DEV_NULL
      case _ =>
        oldp = quotePath(oldPrefix + ent.getOldPath)
        newp = quotePath(newPrefix + ent.getNewPath)
    }
    o.write(encode("--- " + oldp + "\n")) //$NON-NLS-1$ //$NON-NLS-2$

    o.write(encode("+++ " + newp + "\n"))
  }

  @throws[IOException]
  @throws[CorruptObjectException]
  @throws[MissingObjectException]
  private def createFormatResult(ent: DiffEntry): MyFormatResult = {
    val buf = new ByteArrayOutputStream()
    var editList = new EditList()
    var `type` = PatchType.UNIFIED

    formatHeader(buf, ent)

    val oRawAB = if (ent.getOldId() == null || ent.getNewId() == null) {
      // Content not changed (e.g. only mode, pure rename)
      //REMOVE editList = new EditList()
      //REMOVE `type` = PatchType.UNIFIED
      None
    } else {
      //assertHaveReader()

      val (aRaw: Array[Byte], bRaw: Array[Byte]) = if (ent.getOldMode() == FileMode.GITLINK || ent.getNewMode() == FileMode.GITLINK) {
        //          (
        //            writeGitLinkText(ent.getOldId()),
        //            writeGitLinkText(ent.getNewId())
        //          )
        ???
      } else {
        (open(DiffEntry.Side.OLD, ent), open(DiffEntry.Side.NEW, ent))
      }

      if (aRaw == BINARY || bRaw == BINARY || RawText.isBinary(aRaw) || RawText.isBinary(bRaw)) {
        formatOldNewPaths(buf, ent)
        buf.write(encodeASCII("Binary files differ\n")); //$NON-NLS-1$
        editList = new EditList()
        `type` = PatchType.BINARY
        None
      } else {
        val a = new RawText(aRaw);
        val b = new RawText(bRaw);
        editList = diff(a, b);
        `type` = PatchType.UNIFIED;

        ent.getChangeType match {
          case RENAME =>
          case COPY => if (!editList.isEmpty) formatOldNewPaths(buf, ent)
          case _ => formatOldNewPaths(buf, ent)
        }
        Some(a, b)
      }
    }

    MyFormatResult(
      new FileHeader(buf.toByteArray(), editList, `type`),
      oRawAB.map(_._1), //.map(new RawText(_)),
      oRawAB.map(_._2) //.map(new RawText(_))
    )
  }

  //private static
  private def getDiffTreeFilterFor(a: AbstractTreeIterator, b: AbstractTreeIterator): TreeFilter = {
    (a, b) match {
      case (_: DirCacheIterator, _: WorkingTreeIterator) => new IndexDiffFilter(0, 1)
      case (_: WorkingTreeIterator, _: DirCacheIterator) => new IndexDiffFilter(1, 0)
      case _ =>
        var filter: TreeFilter = TreeFilter.ANY_DIFF
        if (a.isInstanceOf[WorkingTreeIterator]) filter = AndTreeFilter.create(new NotIgnoredFilter(0), filter);
        if (b.isInstanceOf[WorkingTreeIterator]) filter = AndTreeFilter.create(new NotIgnoredFilter(1), filter);
        filter
    }
  }

  private def assertHaveReader: Unit =
    if (reader == null) throw new IllegalStateException(JGitText.get().readerIsRequired)

  private def source(iterator: AbstractTreeIterator): ContentSource = iterator match {
    case o: WorkingTreeIterator => ContentSource.create(o)
    case _ => ContentSource.create(reader)
  }

  private def isAdd(files: util.List[DiffEntry]): Boolean = {
    val oldPath = _pathFilter.asInstanceOf[FollowFilter].getPath
    import scala.collection.JavaConverters._
    files.asScala.exists(ent => (ent.getChangeType eq ADD) && ent.getNewPath == oldPath)
  }

  private def isRename(ent: DiffEntry) = (ent.getChangeType eq RENAME) || (ent.getChangeType eq COPY)
  @throws[IOException]
  private def detectRenames(files: util.List[DiffEntry]) = {
    renameDetector.reset()
    renameDetector.addAll(files)
    renameDetector.compute(reader, null) //FIXME progressMonitor)
  }

  private def updateFollowFilter(files: util.List[DiffEntry]): util.List[DiffEntry] = {
    val oldPath = _pathFilter.asInstanceOf[FollowFilter].getPath
    import scala.collection.JavaConverters._
    files.asScala
      .find(ent => isRename(ent) && ent.getNewPath == oldPath)
      .map { ent =>
        _pathFilter = FollowFilter.create(ent.getOldPath, null) //FIXME diffCfg)
        Collections.singletonList(ent)
      }
      .getOrElse(Collections.emptyList[DiffEntry])
  }

  @throws[IOException]
  def scan(a: AbstractTreeIterator, b: AbstractTreeIterator): List[DiffEntry] = {
    assertHaveReader

    val walk = new TreeWalk(reader)
    walk.addTree(a)
    walk.addTree(b)
    walk.setRecursive(true)

    val filter = getDiffTreeFilterFor(a, b)
    _pathFilter match {
      case _: FollowFilter =>
        walk.setFilter(AndTreeFilter.create(PathFilter.create(_pathFilter.asInstanceOf[FollowFilter].getPath), filter))
      case _ => walk.setFilter(AndTreeFilter.create(_pathFilter, filter))
    }

    _source = new ContentSource.Pair(source(a), source(b))

    val files = DiffEntry.scan(walk)
    if (_pathFilter.isInstanceOf[FollowFilter] && isAdd(files)) {
      // The file we are following was added here, find where it
      // came from so we can properly show the rename or copy,
      // then continue digging backwards.
      //
      a.reset()
      b.reset()
      walk.reset()
      walk.addTree(a)
      walk.addTree(b)
      walk.setFilter(filter)
      if (renameDetector == null) setDetectRenames(true)
      updateFollowFilter(detectRenames(DiffEntry.scan(walk)))
    } else if (renameDetector != null) detectRenames(files)
    else files
  }

  @throws[IOException]
  @throws[CorruptObjectException]
  @throws[MissingObjectException]
  def toFileHeader(ent: DiffEntry): FileHeader = createFormatResult(ent).header
}
