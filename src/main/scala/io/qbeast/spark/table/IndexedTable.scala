/*
 * Copyright 2021 Qbeast Analytics, S.L.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.qbeast.spark.table

import io.qbeast.core.keeper.Keeper
import io.qbeast.core.model._
import io.qbeast.core.model.RevisionFactory
import io.qbeast.spark.delta.StagingDataManager
import io.qbeast.spark.delta.StagingResolution
import io.qbeast.spark.internal.sources.QbeastBaseRelation
import io.qbeast.spark.internal.QbeastOptions
import io.qbeast.spark.internal.QbeastOptions.checkQbeastProperties
import io.qbeast.spark.internal.QbeastOptions.optimizationOptions
import io.qbeast.spark.internal.QbeastOptions.COLUMNS_TO_INDEX
import io.qbeast.spark.internal.QbeastOptions.CUBE_SIZE
import org.apache.spark.internal.Logging
import org.apache.spark.qbeast.config.COLUMN_SELECTOR_ENABLED
import org.apache.spark.qbeast.config.DEFAULT_NUMBER_OF_RETRIES
import org.apache.spark.sql.delta.actions.FileAction
import org.apache.spark.sql.sources.BaseRelation
import org.apache.spark.sql.types.StructType
import org.apache.spark.sql.AnalysisExceptionFactory
import org.apache.spark.sql.DataFrame

import java.util.ConcurrentModificationException

/**
 * Indexed table represents the tabular data storage indexed with the OTree indexing technology.
 */
trait IndexedTable {

  /**
   * Returns whether the table physically exists.
   * @return
   *   the table physically exists.
   */
  def exists: Boolean

  /**
   * Returns whether the table contains Qbeast metadata
   * @return
   */
  def hasQbeastMetadata: Boolean

  /**
   * Adds the indexed columns to the parameter if:
   *   - ColumnsToIndex is NOT present
   *   - AutoIndexing is enabled
   *   - Data is available
   * @param parameters
   * @param data
   * @return
   */
  def selectColumnsToIndex(
      parameters: Map[String, String],
      data: Option[DataFrame]): Map[String, String]

  /**
   * Returns the table id which identifies the table.
   *
   * @return
   *   the table id
   */
  def tableID: QTableID

  /**
   * Merge new and index current properties
   * @param properties
   *   the properties you want to merge
   * @return
   */
  def verifyAndMergeProperties(properties: Map[String, String]): Map[String, String]

  /**
   * Saves given data in the table and updates the index. The specified columns are used to define
   * the index when the table is created or overwritten. The append flag defines whether the
   * existing data should be overwritten.
   *
   * @param data
   *   the data to save
   * @param parameters
   *   the parameters to save the data
   * @param append
   *   the data should be appended to the table
   * @return
   *   the base relation to read the saved data
   */
  def save(data: DataFrame, parameters: Map[String, String], append: Boolean): BaseRelation

  /**
   * Loads the table data.
   *
   * @return
   *   the base relation to read the table data
   */
  def load(): BaseRelation

  /**
   * Analyzes the index for a given revision
   * @param revisionID
   *   the identifier of revision to analyze
   * @return
   *   the cubes to analyze
   */
  def analyze(revisionID: RevisionID): Seq[String]

  /**
   * Optimizes the given table for a given revision
   * @param revisionID
   *   the identifier of revision to optimize
   */
  def optimize(revisionID: RevisionID, options: Map[String, String]): Unit

  /**
   * Optimizes the table by optimizing the data stored in the specified index files.
   *
   * @param files
   *   the index files to optimize
   */
  def optimize(files: Seq[String], options: Map[String, String]): Unit

  /**
   * Compacts the small files for a given table
   */
  def compact(revisionID: RevisionID, options: Map[String, String]): Unit
}

/**
 * IndexedTable factory.
 */
trait IndexedTableFactory {

  /**
   * Returns a IndexedTable for given SQLContext and path. It is not guaranteed that the returned
   * table physically exists, use IndexedTable#exists attribute to verify it.
   *
   * @param tableId
   *   the table path
   * @return
   *   the table
   */
  def getIndexedTable(tableId: QTableID): IndexedTable
}

/**
 * Implementation of IndexedTableFactory.
 * @param keeper
 *   the keeper
 * @param indexManager
 *   the index manager
 * @param metadataManager
 *   the metadata manager
 * @param dataWriter
 *   the data writer
 * @param revisionFactory
 *   the revision builder
 */
final class IndexedTableFactoryImpl(
    private val keeper: Keeper,
    private val indexManager: IndexManager[DataFrame],
    private val metadataManager: MetadataManager[StructType, FileAction, QbeastOptions],
    private val dataWriter: DataWriter[DataFrame, StructType, FileAction],
    private val revisionFactory: RevisionFactory[StructType, QbeastOptions],
    private val columnSelector: ColumnsToIndexSelector[DataFrame])
    extends IndexedTableFactory {

  override def getIndexedTable(tableID: QTableID): IndexedTable =
    new IndexedTableImpl(
      tableID,
      keeper,
      indexManager,
      metadataManager,
      dataWriter,
      revisionFactory,
      columnSelector)

}

/**
 * Implementation of IndexedTable.
 *
 * @param tableID
 *   the table identifier
 * @param keeper
 *   the keeper
 * @param indexManager
 *   the index manager
 * @param metadataManager
 *   the metadata manager
 * @param dataWriter
 *   the data writer
 * @param revisionFactory
 *   the revision factory
 * @param columnSelector
 *   the auto indexer
 */
private[table] class IndexedTableImpl(
    val tableID: QTableID,
    private val keeper: Keeper,
    private val indexManager: IndexManager[DataFrame],
    private val metadataManager: MetadataManager[StructType, FileAction, QbeastOptions],
    private val dataWriter: DataWriter[DataFrame, StructType, FileAction],
    private val revisionFactory: RevisionFactory[StructType, QbeastOptions],
    private val columnSelector: ColumnsToIndexSelector[DataFrame])
    extends IndexedTable
    with StagingUtils
    with Logging {
  private var snapshotCache: Option[QbeastSnapshot] = None

  /**
   * Latest Revision Available
   *
   * @return
   */
  private def latestRevision: Revision = snapshot.loadLatestRevision

  override def exists: Boolean = !snapshot.isInitial

  override def hasQbeastMetadata: Boolean = try {
    snapshot.loadLatestRevision
    true
  } catch {
    case _: Exception => false
  }

  override def verifyAndMergeProperties(properties: Map[String, String]): Map[String, String] = {
    if (!exists) {
      // IF not exists, we should only check new properties
      checkQbeastProperties(properties)
      properties
    } else if (hasQbeastMetadata) {
      // IF has qbeast metadata, we can merge both properties: new and current
      val currentColumnsIndexed =
        latestRevision.columnTransformers.map(_.columnName).mkString(",")
      val currentCubeSize = latestRevision.desiredCubeSize.toString
      val finalProperties = {
        (properties.contains(COLUMNS_TO_INDEX), properties.contains(CUBE_SIZE)) match {
          case (true, true) => properties
          case (false, false) =>
            properties + (COLUMNS_TO_INDEX -> currentColumnsIndexed, CUBE_SIZE -> currentCubeSize)
          case (true, false) => properties + (CUBE_SIZE -> currentCubeSize)
          case (false, true) =>
            properties + (COLUMNS_TO_INDEX -> currentColumnsIndexed)
        }
      }
      finalProperties
    } else {
      throw AnalysisExceptionFactory.create(
        s"Table ${tableID.id} exists but does not contain Qbeast metadata. " +
          "Please use ConvertToQbeastCommand to convert the table to Qbeast.")
    }
  }

  private def isNewRevision(qbeastOptions: QbeastOptions): Boolean = {

    // TODO feature: columnsToIndex may change between revisions
    val columnsToIndex = qbeastOptions.columnsToIndex
    val currentColumnsToIndex = latestRevision.columnTransformers.map(_.columnName)
    val isNewColumns = !latestRevision.matchColumns(columnsToIndex)
    if (isNewColumns) {
      throw AnalysisExceptionFactory.create(
        s"Columns to index '${columnsToIndex.mkString(",")}' do not match " +
          s"existing index ${currentColumnsToIndex.mkString(",")}.")
    }
    // Checks if the desiredCubeSize is different from the existing one
    val isNewCubeSize = latestRevision.desiredCubeSize != qbeastOptions.cubeSize
    // Checks if the user-provided column boundaries would trigger the creation of
    // a new revision.
    val isNewSpace = qbeastOptions.stats match {
      case None => false
      case Some(stats) =>
        val columnStats = stats.first()
        val transformations = latestRevision.transformations

        val newPossibleTransformations =
          latestRevision.columnTransformers.map(t =>
            t.makeTransformation(columnName => columnStats.getAs[Object](columnName)))

        transformations
          .zip(newPossibleTransformations)
          .forall(t => {
            t._1.isSupersededBy(t._2)
          })
    }

    isNewCubeSize || isNewSpace

  }

  override def selectColumnsToIndex(
      parameters: Map[String, String],
      data: Option[DataFrame]): Map[String, String] = {
    val optionalColumnsToIndex = parameters.contains(COLUMNS_TO_INDEX)
    if (!optionalColumnsToIndex && !COLUMN_SELECTOR_ENABLED) {
      // IF autoIndexingEnabled is disabled, and no columnsToIndex are specified we should throw an exception
      throw AnalysisExceptionFactory.create(
        "Auto indexing is disabled. Please specify the columns to index in a comma separated way" +
          " as .option(columnsToIndex, ...) or enable auto indexing with spark.qbeast.index.autoIndexingEnabled=true")
    } else if (!optionalColumnsToIndex && COLUMN_SELECTOR_ENABLED) {
      data match {
        case Some(df) => {
          // If columnsToIndex is NOT present, the column selector is ENABLED and DATA is AVAILABLE
          // We can automatically choose the columnsToIndex based on dataFrame
          val columnsToIndex = columnSelector.selectColumnsToIndex(df)
          parameters + (COLUMNS_TO_INDEX -> columnsToIndex.mkString(","))
        }
        case None =>
          throw AnalysisExceptionFactory.create(
            "Auto indexing is enabled but no data is available to select columns to index")
      }
    } else parameters
  }

  override def save(
      data: DataFrame,
      parameters: Map[String, String],
      append: Boolean): BaseRelation = {
    logTrace(s"Begin: save table ${tableID}")
    val (indexStatus, options) =
      if (exists && append) {
        // If the table exists and we are appending new data
        // 1. Load existing IndexStatus
        val options = QbeastOptions(verifyAndMergeProperties(parameters))
        logDebug(s"Appending data to table ${tableID} with revision=${latestRevision.revisionID}")
        if (isStaging(latestRevision)) { // If the existing Revision is Staging
          val revision = revisionFactory.createNewRevision(tableID, data.schema, options)
          (IndexStatus(revision), options)
        } else {
          if (isNewRevision(options)) {
            // If the new parameters generate a new revision, we need to create another one
            val newPotentialRevision = revisionFactory
              .createNewRevision(tableID, data.schema, options)
            val newRevisionCubeSize = newPotentialRevision.desiredCubeSize
            // Merge new Revision Transformations with old Revision Transformations
            logDebug(
              s"Merging transformations for table ${tableID} with cubeSize=${newRevisionCubeSize}")
            val newRevisionTransformations =
              latestRevision.transformations.zip(newPotentialRevision.transformations).map {
                case (oldTransformation, newTransformation)
                    if oldTransformation.isSupersededBy(newTransformation) =>
                  Some(oldTransformation.merge(newTransformation))
                case _ => None
              }

            // Create a RevisionChange
            val revisionChanges = RevisionChange(
              supersededRevision = latestRevision,
              timestamp = System.currentTimeMillis(),
              desiredCubeSizeChange = Some(newRevisionCubeSize),
              transformationsChanges = newRevisionTransformations)
            logDebug(
              s"Creating new revision changes for table ${tableID} with revisionChanges=${revisionChanges})")

            // Output the New Revision into the IndexStatus
            (IndexStatus(revisionChanges.createNewRevision), options)
          } else {
            // If the new parameters does not create a different revision,
            // load the latest IndexStatus
            logDebug(
              s"Loading latest revision for table ${tableID} with revision=${latestRevision.revisionID}")
            (snapshot.loadIndexStatus(latestRevision.revisionID), options)
          }
        }
      } else {
        // IF autoIndexingEnabled, choose columns to index
        val updatedParameters = selectColumnsToIndex(parameters, Some(data))
        val options = QbeastOptions(updatedParameters)
        val revision = revisionFactory.createNewRevision(tableID, data.schema, options)
        (IndexStatus(revision), options)
      }
    val result = write(data, indexStatus, options, append)
    logTrace(s"End: Save table ${tableID}")
    result
  }

  override def load(): BaseRelation = {
    clearCaches()
    createQbeastBaseRelation()
  }

  private def snapshot = {
    if (snapshotCache.isEmpty) {
      snapshotCache = Some(metadataManager.loadSnapshot(tableID))
    }
    snapshotCache.get
  }

  private def clearCaches(): Unit = {
    snapshotCache = None
  }

  /**
   * Creates a QbeastBaseRelation for the given table.
   * @return
   *   the QbeastBaseRelation
   */
  private def createQbeastBaseRelation(): BaseRelation = {
    QbeastBaseRelation.forQbeastTable(this)
  }

  private def write(
      data: DataFrame,
      indexStatus: IndexStatus,
      options: QbeastOptions,
      append: Boolean): BaseRelation = {
    logTrace(s"Begin: Writing data to table ${tableID}")
    val revision = indexStatus.revision
    logDebug(s"Writing data to table ${tableID} with revision ${revision.revisionID}")
    keeper.withWrite(tableID, revision.revisionID) { write =>
      var tries = DEFAULT_NUMBER_OF_RETRIES
      while (tries > 0) {
        val announcedSet = write.announcedCubes.map(indexStatus.revision.createCubeId)
        val updatedStatus = indexStatus.addAnnouncements(announcedSet)
        val replicatedSet = updatedStatus.replicatedSet
        val revisionID = updatedStatus.revision.revisionID
        try {
          doWrite(data, updatedStatus, options, append)
          tries = 0
        } catch {
          case cme: ConcurrentModificationException
              if metadataManager.hasConflicts(
                tableID,
                revisionID,
                replicatedSet,
                announcedSet) || tries == 0 =>
            // Nothing to do, the conflict is unsolvable
            throw cme
          case _: ConcurrentModificationException =>
            // Trying one more time if the conflict is solvable
            tries -= 1
        }

      }
    }
    clearCaches()
    val result = createQbeastBaseRelation()
    logTrace(s"End: Done writing data to table ${tableID}")
    result
  }

  private def doWrite(
      data: DataFrame,
      indexStatus: IndexStatus,
      options: QbeastOptions,
      append: Boolean): Unit = {
    logTrace(s"Begin: Writing data to table ${tableID}")
    val stagingDataManager: StagingDataManager = new StagingDataManager(tableID)
    stagingDataManager.updateWithStagedData(data) match {
      case r: StagingResolution if r.sendToStaging =>
        stagingDataManager.stageData(data, indexStatus, options, append)

      case StagingResolution(dataToWrite, removeFiles, false) =>
        val schema = dataToWrite.schema
        metadataManager.updateWithTransaction(tableID, schema, options, append) {
          val (qbeastData, tableChanges) = indexManager.index(dataToWrite, indexStatus)
          val fileActions = dataWriter.write(tableID, schema, qbeastData, tableChanges)
          (tableChanges, fileActions ++ removeFiles)
        }
    }
    logTrace(s"End: Writing data to table ${tableID}")
  }

  override def analyze(revisionID: RevisionID): Seq[String] = {
    val indexStatus = snapshot.loadIndexStatus(revisionID)
    val cubesToAnnounce = indexManager.analyze(indexStatus).map(_.string)
    keeper.announce(tableID, revisionID, cubesToAnnounce)
    cubesToAnnounce

  }

  override def optimize(revisionID: RevisionID, options: Map[String, String]): Unit = {
    val files = snapshot.loadIndexFiles(revisionID).map(_.path)
    optimize(files, options)
  }

  override def optimize(files: Seq[String], options: Map[String, String]): Unit = {
    val paths = files.toSet
    val schema = metadataManager.loadCurrentSchema(tableID)
    snapshot.loadAllRevisions.foreach { revision =>
      val indexFiles = snapshot
        .loadIndexFiles(revision.revisionID)
        .filter(file => paths.contains(file.path))
        .toIndexedSeq
      if (indexFiles.nonEmpty) {
        val indexStatus = snapshot.loadIndexStatus(revision.revisionID)
        metadataManager.updateWithTransaction(
          tableID,
          schema,
          optimizationOptions(options),
          append = true) {
          val tableChanges = BroadcastedTableChanges(None, indexStatus, Map.empty, Map.empty)
          val fileActions =
            dataWriter.compact(tableID, schema, revision, indexStatus, indexFiles)
          (tableChanges, fileActions)
        }
      }
    }
  }

  override def compact(revisionID: RevisionID, options: Map[String, String]): Unit =
    optimize(revisionID, options)

}
