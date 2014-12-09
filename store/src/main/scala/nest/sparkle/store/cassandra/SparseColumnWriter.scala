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

package nest.sparkle.store.cassandra

import scala.collection.JavaConverters._
import scala.concurrent.{ ExecutionContext, Future }

import com.datastax.driver.core.{ BatchStatement, Session }

import nest.sparkle.store.Event
import nest.sparkle.store.cassandra.ColumnTypes.serializationInfo
import nest.sparkle.store.cassandra.SparseColumnWriterStatements._
import nest.sparkle.util.GuavaConverters._
import nest.sparkle.util.{ Instrumented, Log }

object SparseColumnWriter
    extends Instrumented with Log {
  /** All SparseColumnWriters use this metric */
  protected val batchMetric = metrics.timer("store-batch-writes")

  /** constructor to create a SparseColumnWriter */
  def apply[T: CanSerialize, U: CanSerialize]( // format: OFF
        dataSetName: String, 
        columnName: String, 
        catalog: ColumnCatalog, 
        dataSetCatalog: DataSetCatalog, 
        writeNotifier:WriteNotifier,
        preparedSession: PreparedSession
      ): SparseColumnWriter[T,U] = { // format: ON

    new SparseColumnWriter[T, U](dataSetName, columnName, catalog, dataSetCatalog, writeNotifier, preparedSession)
  }

  /** constructor to create a SparseColumnWriter and update the store */
  def instance[T: CanSerialize, U: CanSerialize]( // format: OFF
        dataSetName: String, 
        columnName: String, 
        catalog: ColumnCatalog, 
        dataSetCatalog: DataSetCatalog, 
        writeNotifier:WriteNotifier,
        preparedSession: PreparedSession
      )(implicit execution:ExecutionContext):Future[SparseColumnWriter[T,U]] = { // format: ON

    val writer = new SparseColumnWriter[T, U](dataSetName, columnName, catalog, dataSetCatalog, writeNotifier, preparedSession)
    writer.updateCatalog().map { _ => writer}
  }

  /** create columns for default data types */
  def createColumnTables(session: Session)(implicit execution: ExecutionContext): Future[Unit] = {
    // This gets rid of duplicate column table creates which C* 2.1 doesn't handle correctly.
    val tables = ColumnTypes.supportedColumnTypes.map { serialInfo =>
      serialInfo.tableName -> ColumnTableInfo(serialInfo.tableName, serialInfo.domain.columnType, serialInfo.range.columnType)
    }.toMap
    val futures = tables.values.map { tableInfo =>
      createColumnTable(tableInfo, session)(execution)
    }
    Future.sequence(futures).map { _ => () }
  }

  /** create a column asynchronously
    * This should be idempotent. With C* 2.1 there appears to be a bug where creating the same table
    * simultaneously can fail.
    */
  private def createColumnTable(tableInfo: ColumnTableInfo, session: Session) // format: OFF
      (implicit execution:ExecutionContext): Future[Unit] = { // format: ON
    val createTable = s"""
      CREATE TABLE IF NOT EXISTS "${tableInfo.tableName}" (
        dataSet ascii,
        rowIndex int,
        column ascii,
        argument ${tableInfo.keyType},
        value ${tableInfo.valueType},
        PRIMARY KEY((dataSet, column, rowIndex), argument)
      ) WITH COMPACT STORAGE
      """
    val created = session.executeAsync(createTable).toFuture.map { _ => () }
    created.onFailure { case error => log.error("createEmpty failed", error) }
    created
  }

  /** For creating column tables */
  private case class ColumnTableInfo(tableName: String, keyType: String, valueType: String)

}

import nest.sparkle.store.cassandra.SparseColumnWriter._

/** Manages a column of (argument,value) data pairs.  The pair is typically
  * a millisecond timestamp and a double value.
  */
protected class SparseColumnWriter[T: CanSerialize, U: CanSerialize]( // format: OFF
    val dataSetName: String, val columnName: String,
    catalog: ColumnCatalog, dataSetCatalog: DataSetCatalog, 
    writeNotifier:WriteNotifier, preparedSession: PreparedSession)
  extends WriteableColumn[T, U] 
  with ColumnSupport 
  with Log { // format: ON

  val serialInfo = serializationInfo[T, U]()
  val tableName = serialInfo.tableName

  /** create a catalog entries for this given sparse column */
  protected def updateCatalog(description: String = "no description")(implicit executionContext: ExecutionContext): Future[Unit] = {
    // LATER check to see if table already exists first

    val entry = CassandraCatalogEntry(columnPath = columnPath, tableName = tableName, description = description,
      domainType = serialInfo.domain.nativeType, rangeType = serialInfo.range.nativeType)

    val result =
      for {
        _ <- catalog.writeCatalogEntry(entry)
        _ <- dataSetCatalog.addColumnPath(entry.columnPath)
      } yield { () }

    result.onFailure { case error => log.error("create column failed", error) }
    result
  }

  /** write a bunch of column values in a batch */ // format: OFF
  def write(items:Iterable[Event[T,U]])
      (implicit executionContext:ExecutionContext): Future[Unit] = { // format: ON
    val events = items.toSeq
    val written = writeMany(events)
    written.map { _ =>
      items.headOption.foreach { head =>
        val start = head.argument
        val end = items.last.argument
        writeNotifier.notify(columnPath, ColumnUpdate(start, end))
      }
    }
  }

  /** delete all the column values in the column */
  def erase()(implicit executionContext: ExecutionContext): Future[Unit] = { // format: ON
    val deleteAll = preparedSession.statement(DeleteAll(tableName)).bind(Seq[Object](dataSetName, columnName, rowIndex): _*)
    preparedSession.session.executeAsync(deleteAll).toFuture.map { _ => () }
  }

  private val batchSize = 25000 // CQL driver has a max batch size of 64K
  
  /** write a bunch of column values in a batch */ // format: OFF
  private def writeMany(events:Iterable[Event[T,U]])
      (implicit executionContext:ExecutionContext): Future[Unit] = { // format: ON
    val batches = events.grouped(batchSize).map { eventGroup =>
      val insertGroup = eventGroup.map { event =>
        val (argument, value) = serializeEvent(event)
        preparedSession.statement(InsertOne(tableName)).bind(
          Seq[AnyRef](dataSetName, columnName, rowIndex, argument, value): _*
        )
      }

      val batch = new BatchStatement()
      batch.addAll(insertGroup.toSeq.asJava)

      batch
    }

    val resultsIterator =
      batches.map { batch =>
        val timer = batchMetric.timerContext()
        val result = preparedSession.session.executeAsync(batch).toFuture
        result.onComplete(_ => timer.stop())
        val resultUnit = result.map { _ => }
        resultUnit
      }

    val allDone: Future[Unit] =
      resultsIterator.fold(Future.successful()) { (a, b) =>
        a.flatMap(_ => b)
      }

    allDone
  }

  /** return a tuple of cassandra serializable objects for an event */
  private def serializeEvent(event: Event[T, U]): (AnyRef, AnyRef) = {
    val argument = serialInfo.domain.serialize(event.argument)
    val value = serialInfo.range.serialize(event.value)

    (argument, value)
  }

}