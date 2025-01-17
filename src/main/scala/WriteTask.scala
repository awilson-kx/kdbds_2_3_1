/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.sql.{Timestamp => JTimestamp, Date => JDate}
import java.util

import org.apache.spark.sql.sources.v2._
import org.apache.spark.sql.types._
import org.apache.spark.sql.Row
import org.apache.spark.sql.SaveMode
import org.apache.spark.sql.sources.v2.writer._
import org.apache.log4j.Logger

import scala.collection.mutable

//TODO: Must test with multiple Spark partitions (partitionId?)

class WriteTask(partitionId: Int, attemptNumber: Int, jobid: String, schema: StructType, mode: SaveMode, optionmap: util.Map[String,String])
    extends DataWriter[Row] {
  @transient lazy val options = new DataSourceOptions(optionmap)
  @transient lazy val log: Logger = Logger.getLogger("kdb")
  
  var batch: Array[Object] = _
  var batchSize = 0 // Number of rows per batch
  var batchCount = 1 // Current batch number
  var rowCount = 0 // Total number of rows written
  var indBatch = 0 // Index into batch "rows"

  override def write(row: Row): Unit = {
    /*
     * If this is the first write, initialize the batch given the columns provided
     * in the schema. A batch is a set of column arrays.
     */
    if (batch == null) {
      batchSize = options.getInt(Opt.BATCHSIZE, Opt.BATCHSIZEDEF)
      if (log.isDebugEnabled) {
        log.debug("write(): first call")
        log.debug(s"  batchsize:     $batchSize")
        log.debug(s"  partitionId:   $partitionId")
        log.debug(s"  attemptNumber: $attemptNumber")
      }

      batch = new Array[Object](schema.length)
      for (colind <- 0 until schema.length) {
        batch(colind) = createColumnArray(schema(colind).dataType, batchSize)
      }
    }

    /*
     * Loop through the columns in the provided row, placing the data into the batch
     * column arrays
     */
    for (i <- 0 until schema.length) {
      val nn = row(i) != null; // Not null

      batch(i) match {
        case a:Array[Boolean] => a(indBatch) = row.getAs[Boolean](i)
        case a:Array[Byte] => a(indBatch) = row.getAs[Byte](i)
        case a:Array[Short] => a(indBatch) = if (nn) row.getAs[Short](i) else Type.ShortNull
        case a:Array[Int] => a(indBatch) = if (nn) row.getAs[Int](i) else Type.IntNull
        case a:Array[Long] => a(indBatch) = if (nn) row.getAs[Long](i) else Type.LongNull
        case a:Array[Float] => a(indBatch) = if (nn) row.getAs[Float](i) else Type.FloatNull
        case a:Array[Double] => a(indBatch) = if (nn) row.getAs[Double](i) else Type.DoubleNull
        case a:Array[JTimestamp] => a(indBatch) = if (nn) row.getAs[JTimestamp](i) else Type.TimestampNull
        case a:Array[JDate] => a(indBatch) = if (nn) row.getAs[JDate](i) else Type.DateNull
        case a:Array[Object] => a(indBatch) =
          schema(i).dataType match {
            case StringType => if (nn) row.getAs[String](i).toCharArray.asInstanceOf[Object] else Type.StringNull
            case Type.BooleanArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[Boolean]]].array
            case Type.ByteArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[Byte]]].array
            case Type.ShortArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[Short]]].array
            case Type.IntegerArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[Int]]].array
            case Type.LongArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[Long]]].array
            case Type.FloatArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[Float]]].array
            case Type.DoubleArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[Double]]].array
            case Type.TimestampArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[JTimestamp]]].array
            case Type.DateArrayType => row(i).asInstanceOf[mutable.WrappedArray[Array[JDate]]].array
            case _ => throw new Exception("Unsupported data type: " + schema(i).dataType)
          }
        case _ => throw new Exception("Unsupported data type: " + batch(i).getClass)
      }
    }

    /* If we filled a batch; send it to kdb+ */
    indBatch += 1
    if (indBatch == batchSize) {
      writeBatch(Opt.WRITE)
      batchCount += 1
      indBatch = 0
    }
  }

  override def commit(): WriterCommitMessage = {
    if (batch != null) {
      truncateBatch(indBatch) // Resize batch to fit remaining rows
      writeBatch(Opt.COMMIT)
      batch = null; // Free memory
    }
    null
  }

  override def abort(): Unit = {
    truncateBatch(0)
    writeBatch(Opt.ABORT)
    batch = null; // Free memory
  }

  /* Send batch to kdb+ specifying write disposition */
  private def writeBatch(disp: String): Unit = {
    optionmap.put(Opt.WRITEACTION, disp)
    optionmap.put(Opt.BATCHCOUNT, batchCount.toString)
    optionmap.put(Opt.PARTITIONID, partitionId.toString)

    KdbCall.write(optionmap, schema, batch)
    rowCount += indBatch
    log.debug(s"Batches written: $batchCount; Rows written: $rowCount")    
  }
  
  /* Truncate batch by keeping the first n rows */
  def truncateBatch(n: Int) {
    for (colind <- batch.indices) {
      batch(colind) = batch(colind) match {
        case a:Array[_] => a.take(n)
      }
    }
  }
  
  /* Create a kdb+ array of length <bs> given Spark datatype */
  def createColumnArray(dt: DataType, bs: Int): Array[_] = {    
    dt match {
      case BooleanType => new Array[Boolean](bs)
      case ByteType => new Array[Byte](bs)
      case ShortType => new Array[Short](bs)
      case IntegerType => new Array[Int](bs)
      case LongType => new Array[Long](bs)
      case FloatType => new Array[Float](bs)
      case DoubleType => new Array[Double](bs)
      case StringType => new Array[Object](bs)
      case TimestampType => new Array[JTimestamp](bs)
      case DateType => new Array[JDate](bs)
      case Type.BooleanArrayType => new Array[Object](bs)
      case Type.ByteArrayType => new Array[Object](bs)
      case Type.ShortArrayType => new Array[Object](bs)
      case Type.IntegerArrayType => new Array[Object](bs)
      case Type.LongArrayType => new Array[Object](bs)
      case Type.FloatArrayType => new Array[Object](bs)
      case Type.DoubleArrayType => new Array[Object](bs)
      case Type.TimestampArrayType => new Array[Object](bs)
      case Type.DateArrayType => new Array[Object](bs)
      case _ => throw new Exception(s"Unsupported data type: $dt")
    }
  }
}