/* Copyright 2014  Nest Labs

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.  */

package nest.sparkle.loader

import nest.sparkle.util.WatchPath
import nest.sparkle.util.WatchPath._
import akka.actor.ActorSystem
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.Files
import nest.sparkle.store.cassandra.serializers._
import scala.concurrent.Future
import nest.sparkle.store.cassandra.WriteableColumn
import scala.collection.immutable.Range
import nest.sparkle.store.WriteableStore
import nest.sparkle.store.Event
import nest.sparkle.util.Exceptions.NYI
import scala.concurrent.Promise
import scala.util.Success
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._

/** emitted to the event stream when the file has been completely loaded */
case class LoadComplete(filePath: String)

case class LoadPathDoesNotExist(path: String) extends RuntimeException

object FilesLoader {
  def apply(rootDirectory: String, store: WriteableStore, strip: Int = 0) // format: OFF
      (implicit system: ActorSystem): FilesLoader = { // format: ON
    new FilesLoader(rootDirectory, store, strip)
  }
}

/** Load all the events in the csv/tsv files in a directory or load a single file.
  * The directory or file must exist.
  *
  * @param loadPath Path to directory to load from/watch or a single file to load.
  * @param store Store to write data to.
  * @param strip Number of leading path elements to strip when
  *              creating the DataSet name.
  */
protected class FilesLoader(loadPath: String, store: WriteableStore, strip: Int)(implicit system: ActorSystem) {
  val log = LoggerFactory.getLogger(classOf[FilesLoader])
  implicit val executor = system.dispatcher
  val root = Paths.get(loadPath)

  if (Files.notExists(root)) {
    log.error(s"$loadPath does not exist. Can not load data from this path")
    throw LoadPathDoesNotExist(loadPath)
  }

  if (Files.isDirectory(root)) {
    log.info(s"Watching $loadPath for files to load into store")
    val watcher = WatchPath(root)
    val initialFiles = watcher.watch{ change => fileChange(change, store) }
    initialFiles.foreach{ futureFiles =>
      futureFiles.foreach{ path =>
        loadFile(root.resolve(path), store)
      }
    }
  } else {
    loadFile(root, store)
  }

  /** called when a file is changed in the directory we're watching */
  private def fileChange(change: Change, store: WriteableStore) {
    change match {
      case Added(path) =>
        loadFile(path, store)
      case Removed(path) =>
        log.warn(s"removed $path.  ignoring for now")
      case Modified(path) =>
        log.warn(s"modified $path.  ignoring for now")
    }
  }

  private def loadFile(fullPath: Path, store: WriteableStore) {
    fullPath match {
      case ParseableFile(format) if Files.isRegularFile(fullPath) =>
        log.info(s"Started loading $fullPath into the sparkle store")
        TabularFile.load(fullPath, format).map { rowInfo =>
          loadRows(rowInfo, store, fullPath).andThen {
            case _ => rowInfo.close()
          } foreach { _ =>
            log.info(s"Finished loading $fullPath into the sparkle store")
            system.eventStream.publish(LoadComplete(fullPath.toString))
          }
        }
      case x => log.warn(s"$fullPath could not be parsed, ignoring")
    }
  }

  private def loadRows(rowInfo: CloseableRowInfo, store: WriteableStore, path: Path): Future[Path] = {
    val dsString = dataSetString(path)

    /** indices of RowData columns that we'll store (i.e. not the time column) */
    val valueColumnIndices = {
      val indices = Range(0, rowInfo.names.size)
      rowInfo.keyColumn match {
        case None           => indices
        case Some(rowIndex) => indices.filterNot(_ == rowIndex)
      }
    }

    /** collect up futures that complete with column write interface objects */
    val futureColumnsWithIndex: Seq[Future[(Int, WriteableColumn[Long, Double])]] =
      valueColumnIndices.map { index =>
        val name = rowInfo.names(index)
        val columnPath = dsString + "/" + name
        store.writeableColumn[Long, Double](columnPath) map { futureColumn =>
          (index, futureColumn)
        }
      }

    /** write all the row data into storage columns */
    def writeColumns[T, U](rowInfo: RowInfo,
                           columnsWithIndex: Seq[(Int, WriteableColumn[T, U])]): Future[Unit] = {
      rowInfo.keyColumn.isDefined || NYI("tables without key column")

      val rowFutures =
        rowInfo.rows.flatMap { row =>
          for {
            (index, column) <- columnsWithIndex
            value <- row.values(index)
            key <- row.key(rowInfo)
          } yield {
            val event = Event(key.asInstanceOf[T], value.asInstanceOf[U])
            column.write(Seq(event))
          }
        }

      Future.sequence(rowFutures).map { _ => () }
    }

    val pathWritten:Future[Path] =
      for {
        columnsWithIndex <- Future.sequence(futureColumnsWithIndex)
        columns = columnsWithIndex map { case (index, column) => column }
        written <- writeColumns(rowInfo, columnsWithIndex)
      } yield {
        path
      }

    pathWritten
  }

  /** Make the DataSet string from the file's path.
    *
    * The dataset string is the file's path minus any parts beginning with an
    * underscore.
    *
    * Strips off leading path elements if strip > 0.
    *
    * @param path Path of the tsv/csv file
    * @return The DataSet as a string.
    */
  private def dataSetString(path: Path): String = {
    val parent =
      path.getParent.iterator.drop(strip)
        .filterNot(p => p.toString.startsWith("_"))
        .mkString("/")
    val fileName = path.getFileName.toString match {
      //case s if s.endsWith(".tsv") => s.stripSuffix(".tsv")
      //case s if s.endsWith(".csv") => s.stripSuffix(".csv")
      case s => s
    }

    (parent + (fileName match {
      case s if s.startsWith("_") => ""
      case _                      => "/" + fileName
    })).stripPrefix("/")
  }

}

