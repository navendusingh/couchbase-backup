package com.talena.agents.couchbase.mstore

import org.apache.hadoop.fs.{FileSystem, Path}
import org.apache.spark.{SparkConf, SparkContext}
import org.apache.spark.sql.{DataFrame, Row, SQLContext}

sealed trait MStore {
  def dedupeFilter(): MStore = {
    this match {
      case L0(MData(mutations, Filter(filter, source), rollbackLog), props) =>
        import props.env.sqlCtx.implicits._
        L0(MData(mutations, Filter(filter
          .groupBy($"pid", $"key")
          .max("seqno")
          .toDF("pid", "key", "seqno"), source), rollbackLog), props)
      case _ => this
    }
  }

  def applyFilter(): MStore = {
    this
  }

  def updateFilter(other: MStore): MStore = {
    this
  }
}

case class L0(data: MData, props: Props) extends MStore
case class L1(data: Option[MData], props: Props) extends MStore
case class L2(data: Option[MData], props: Props) extends MStore

case class MData(mutations: Mutations, filter: Filter,
  rollbackLog: Option[RollbackLog])

sealed trait MDataClass
case class Mutations(dataFrame: DataFrame, source: Files) extends MDataClass
case class Filter(dataFrame: DataFrame, source: File) extends MDataClass
case class RollbackLog(dataFrame: DataFrame, source: File) extends MDataClass

sealed trait DataFrameStorage
case class File(file: String, format: FileFormat) extends DataFrameStorage
case class Files(files: Array[String], format: FileFormat)
  extends DataFrameStorage

sealed trait FileFormat
case object CSV extends FileFormat
case object JSON extends FileFormat
case object SequenceFile extends FileFormat

case class Props(id: String, level: Level, loc: String, format: FileFormat,
  env: Env)

case class Env(sparkCtx: SparkContext, sqlCtx: SQLContext, fs: FileSystem)

sealed trait Schema
case class MutationSchema(pid: Int, seqno: Long, key: String, value: String,
  meta: String, data: String) extends Schema
case class FilterSchema(pid: Int, seqno: Long, key: String) extends Schema
case class RollbackLogSchema(pid: Int, seqno: Long) extends Schema

sealed trait Level
case object Level0 extends Level
case object Level1 extends Level
case object Level2 extends Level

object MStore {
  def open(props:(Props, Props, Props)): (MStore, MStore, MStore) = {
    val (props0, props1, props2) = props
    (openMStore(props0), openMStore(props1), openMStore(props2))
  }

  private def openMStore(props: Props): MStore = {
    (props.level, openMData(props))  match {
      case (Level0, Some(mdata)) => L0(mdata, props)
      case (Level0, None) => throw new IllegalArgumentException(
        "L0 location may not be empty");
      case (Level1, mdata) => L1(mdata, props)
      case (Level2, mdata) => L2(mdata, props)
    }
  }

  private def openMData(props: Props): Option[MData] = {
    val mutationFiles = props.loc + "/*.db"
    val filterFile = props.loc + "/*.meta"
    if(verifyFiles(props.env.fs, props.loc, (mutationFiles, filterFile))) {
      Some(MData(
        openMutations(mutationFiles, props),
        openFilter(filterFile, props),
        openRollbackLog(props)))
    } else {
      None
    }
  }

  private def openMutations(files: String, props: Props): Mutations = {
    import props.env.sqlCtx.implicits._
    val df = props.env.sparkCtx.textFile(files)
      .map(_.split(",")).map(p => MutationSchema(
        p(0).toInt, p(1).toInt, p(2), p(3), p(4), p(5))).toDF()
    Mutations(df, Files(Array(files), props.format))
  }

  private def openFilter(file: String, props: Props): Filter = {
    import props.env.sqlCtx.implicits._
    val df = props.env.sparkCtx.textFile(file)
      .map(_.split(",")).map(p => FilterSchema(
        p(0).toInt, p(1).toInt, p(2))).toDF()
    Filter(df, File(file, props.format))
  }

  private def openRollbackLog(props: Props): Option[RollbackLog] = {
    None
  }

  private def verifyFiles(fs: FileSystem, loc: String, files: (String, String))
    : Boolean = {
    Utils.verifyLocation(fs, loc)
    val (mutationFiles, filterFile) = files
    val mutationsFilesExist = (fs.globStatus(new Path(mutationFiles)).length > 0)
    val filterFileExists = (fs.globStatus(new Path(filterFile)).length == 1)
    (mutationsFilesExist, filterFileExists) match {
      case (true, true) =>
        System.out.println("Found " + files);
        true
      case (false, false) =>
        System.out.println(files + " missing");
        false
      case (true, false) => throw new IllegalArgumentException(
        "Filter file " + filterFile + " missing")
      case (false, true) => throw new IllegalArgumentException(
        "Mutation files " + mutationFiles + " missing")
    }
  }
}
