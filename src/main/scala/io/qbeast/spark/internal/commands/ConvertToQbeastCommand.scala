/*
 * Copyright 2021 Qbeast Analytics, S.L.
 */
package io.qbeast.spark.internal.commands

import io.qbeast.IISeq
import io.qbeast.core.model._
import io.qbeast.core.transform._
import io.qbeast.spark.delta.{DeltaQbeastLog, SparkDeltaMetadataManager}
import io.qbeast.spark.index.SparkRevisionFactory
import io.qbeast.spark.internal.commands.ConvertToQbeastCommand.{
  dataTypeMinMax,
  dataTypeToName,
  extractQbeastTag
}
import io.qbeast.spark.utils.{State, TagUtils}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.Path
import org.apache.http.annotation.Experimental
import org.apache.parquet.hadoop.ParquetFileReader
import org.apache.parquet.hadoop.util.HadoopInputFile
import org.apache.spark.qbeast.config.DEFAULT_CUBE_SIZE
import org.apache.spark.sql.delta.actions.{AddFile, FileAction}
import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.delta.{DeltaLog, Snapshot}
import org.apache.spark.sql.execution.command.LeafRunnableCommand
import org.apache.spark.sql.types.{
  BooleanType,
  DateType,
  DecimalType,
  DoubleType,
  FloatType,
  IntegerType,
  LongType,
  StringType,
  StructType,
  TimestampType
}

import scala.util.matching.Regex

@Experimental
case class ConvertToQbeastCommand(
    path: String,
    columnsToIndex: Seq[String],
    cubeSize: Int = DEFAULT_CUBE_SIZE,
    partitionColumns: Seq[String] = Seq.empty)
    extends LeafRunnableCommand {

  private val isPartitioned: Boolean = partitionColumns.nonEmpty

  /**
   * Format inference for the input table. If partition columns are provided,
   * the format is assumed to be parquet. Any unsupported format is considered
   * as parquet and is detected when trying to convert it into delta.
   * @param sparkSession SparkSession to use
   * @return
   */
  private def resolveTableFormat(sparkSession: SparkSession): (String, StructType) = {
    val deltaLog = DeltaLog.forTable(sparkSession, path)
    val qbeastSnapshot = DeltaQbeastLog(deltaLog).qbeastSnapshot
    val schema = deltaLog.snapshot.schema

    val isDelta = deltaLog.tableExists
    // The first revisionID for a converted table is 0,
    // while for one that's written in the conventional fashion is 1.
    val isQbeast =
      isDelta && (qbeastSnapshot.existsRevision(0) || qbeastSnapshot.existsRevision(1))

    if (isQbeast) {
      ("qbeast", schema)
    } else if (isDelta) {
      ("delta", schema)
    } else if (isPartitioned) {
      // Partitioned parquet, table schema is required for its conversion into delta
      ("parquet", sparkSession.read.parquet(path).schema)
    } else {
      // Parquet, or any other unsupported format, schema.isEmpty but we don't need it
      ("parquet", schema)
    }
  }

  // scalastyle:off println
  def logConsole(line: String): Unit = println(line)
  // scalastyle:on println

  /**
   * Convert the parquet table using ConvertToDeltaCommand from Delta Lake.
   * Any unsupported format will cause a SparkException error.
   * @param spark SparkSession to use
   */
  private def convertParquetToDelta(spark: SparkSession, schema: StructType): Unit = {
    if (isPartitioned) {
      assert(schema.nonEmpty, "Empty schema")
      assert(
        partitionColumns.forall(schema.names.contains),
        s"""Partition column not found in schema.
           |Partition columns: $partitionColumns,
           |schema: $schema""".stripMargin)

      val colsAndTypes =
        partitionColumns.map(colName => {
          val sqlTypeName = dataTypeToName(colName, schema)
          colName + " " + sqlTypeName
        })
      spark.sql(
        s"CONVERT TO DELTA parquet.`$path` PARTITIONED BY (${colsAndTypes.mkString(", ")})")
    } else {
      spark.sql(s"CONVERT TO DELTA parquet.`$path`")
    }
  }

  /**
   * Initialize Revision for table conversion.
   * The smallest RevisionID for a converted table is 0.
   * @param schema table schema
   * @return
   */
  private def initializeRevision(schema: StructType): Revision = {
    val revision =
      SparkRevisionFactory.createNewRevision(
        QTableID(path),
        schema,
        Map("columnsToIndex" -> columnsToIndex.mkString(","), "cubeSize" -> cubeSize.toString))

    val transformations = revision.columnTransformers.map {
      case LinearTransformer(_, dataType: OrderedDataType) =>
        val minMax = dataTypeMinMax(dataType)
        LinearTransformation(minMax.minValue, minMax.maxValue, dataType)
      case HashTransformer(_, _) => HashTransformation()
    }.toIndexedSeq

    revision.copy(transformations = transformations)
  }

  private def createQbeastActions(
      snapshot: Snapshot,
      revision: Revision,
      path: String): IISeq[FileAction] = {
    val allFiles = snapshot.allFiles.collect()

    allFiles
      .map(addFile => {
        val metadataTag = extractQbeastTag(addFile, revision, path)
        addFile.copy(tags = metadataTag)
      })
      .toIndexedSeq
  }

  private def getTableChanges(revision: Revision, sparkSession: SparkSession): TableChanges = {
    val root = revision.createCubeIdRoot()

    BroadcastedTableChanges(
      isNewRevision = true,
      isOptimizeOperation = false,
      revision,
      Set.empty[CubeId],
      Set.empty[CubeId],
      sparkSession.sparkContext.broadcast(Map(root -> State.FLOODED)),
      sparkSession.sparkContext.broadcast(Map(root -> Weight.MaxValue)))
  }

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val (fileFormat, sourceSchema) = resolveTableFormat(sparkSession)

    fileFormat match {
      // Idempotent conversion
      case "qbeast" =>
        logConsole("The table you are trying to convert is already a qbeast table")
        return Seq.empty
      // Convert parquet to delta
      case "parquet" => convertParquetToDelta(sparkSession, sourceSchema)
      // delta, do nothing
      case _ =>
    }

    // Convert delta to qbeast
    val snapshot = DeltaLog.forTable(sparkSession, path).snapshot
    val revision = initializeRevision(snapshot.schema)

    SparkDeltaMetadataManager.updateWithTransaction(
      revision.tableID,
      snapshot.schema,
      append = true) {
      val tableChanges = getTableChanges(revision, sparkSession)
      val newFiles = createQbeastActions(snapshot, revision, path)

      (tableChanges, newFiles)
    }

    Seq.empty
  }

}

object ConvertToQbeastCommand {
  private val numRecordsPattern: Regex = """"numRecords":(\d+),""".r

  private val intMinMax = ColumnMinMax(-1e8.toInt, 1e8.toInt)
  private val doubleMinMax = ColumnMinMax(-1e10, 1e10)
  private val longMinMax = ColumnMinMax(-1e15.toLong, 1e15.toLong)

  private val dataTypeMinMax = Map(
    DoubleDataType -> doubleMinMax,
    IntegerDataType -> intMinMax,
    LongDataType -> longMinMax,
    FloatDataType -> doubleMinMax,
    DecimalDataType -> doubleMinMax,
    TimestampDataType -> longMinMax,
    DateDataType -> longMinMax)

  /**
   * Extract record count from a parquet file metadata.
   * @param parquetFilePath target parquet file path
   * @return
   */
  def extractParquetFileCount(parquetFilePath: String): String = {
    val path = new Path(parquetFilePath)
    val file = HadoopInputFile.fromPath(path, new Configuration())
    val reader = ParquetFileReader.open(file)
    reader.getRecordCount.toString
  }

  /**
   * Extract Qbeast metadata for an AddFile.
   * @param addFile AddFile to be converted into a qbeast block for the root
   * @param revision the conversion revision to use, revisionID = 0
   * @param tablePath path of the table
   * @return
   */
  def extractQbeastTag(
      addFile: AddFile,
      revision: Revision,
      tablePath: String): Map[String, String] = {
    val elementCount = addFile.stats match {
      case stats: String =>
        numRecordsPattern.findFirstMatchIn(stats) match {
          case Some(matching) => matching.group(1)
          // stats does not contain record count, proceed extraction using parquet metadata
          case _ => extractParquetFileCount(tablePath + "/" + addFile.path)
        }
      // AddFile entries with no 'stats' field, proceed extraction using parquet metadata
      case _ => extractParquetFileCount(tablePath + "/" + addFile.path)
    }

    Map(
      TagUtils.cube -> "",
      TagUtils.minWeight -> Weight.MinValue.value.toString,
      TagUtils.maxWeight -> Weight.MaxValue.value.toString,
      TagUtils.state -> State.FLOODED,
      TagUtils.revision -> revision.revisionID.toString,
      TagUtils.elementCount -> elementCount)

  }

  /**
   * Convert a Spark data type into a Sql data type. Used to convert partitioned parquet tables
   * @param columnName, name of the column whose data type is of our interest
   * @param schema table schema of the partitioned parquet table
   * @return
   */
  private def dataTypeToName(columnName: String, schema: StructType): String = {
    val dataType = schema(columnName).dataType
    dataType match {
      //      case _: ArrayType => "ARRAY"
      //      case _: BinaryType => "BINARY"
      case _: BooleanType => "BOOLEAN"
      //      case _: ByteType => "TINYINT"
      case _: DateType => "DATE"
      case _: DecimalType => "DECIMAL"
      case _: DoubleType => "DOUBLE"
      case _: FloatType => "FLOAT"
      case _: IntegerType => "INT"
      case _: LongType => "BIGINT"
      //      case _: MapType => "MAP"
      //      case _: ShortType => "SMALLINT"
      case _: StringType => "STRING"
      //      case _: StructType => "STRUCT"
      case _: TimestampType => "TIMESTAMP"
      case _ => throw new RuntimeException(s"$dataType is not supported")
    }
  }

}

case class ColumnMinMax(minValue: Any, maxValue: Any)
