package streaming.dsl.mmlib.algs

import org.apache.spark.Partitioner
import org.apache.spark.sql.{DataFrame, Row, SaveMode, SparkSession, functions => F}
import org.apache.spark.sql.expressions.UserDefinedFunction
import org.apache.spark.sql.types.{IntegerType, StructField, StructType}
import streaming.dsl.mmlib.SQLAlg

/**
 * Created by allwefantasy on 7/5/2018.
 */
class SQLRateSampler extends SQLAlg with Functions {


  def internal_train(df: DataFrame, params: Map[String, String]) = {

    val getIntFromRow = (row: Row, i: Int) => {
      row.get(i) match {
        case a: Int => a.asInstanceOf[Int]
        case a: Double => a.asInstanceOf[Double].toInt
        case a: Float => a.asInstanceOf[Float].toInt
        case a: Long => a.asInstanceOf[Long].toInt
      }
    }

    val getIntFromRowByName = (row: Row, name: String) => {
      getIntFromRow(row, row.fieldIndex(name))
    }

    val labelCol = params.getOrElse("labelCol", "label")
    val isSplitWithSubLabel = params.getOrElse("isSplitWithSubLabel", "")

    // 0.8 0.1 0.1
    val sampleRates = params.getOrElse("sampleRate", "0.9,0.1").split(",").map(f => f.toDouble)
    var basicRate = sampleRates.head
    val newSampleRates = sampleRates.zipWithIndex.map { sr =>
      if (sr._2 > 0) {
        basicRate = sr._1 + basicRate
        (basicRate, sr._2)
      }
      else sr
    }


    val labelToCountSeq = df.groupBy(labelCol).agg(F.count(labelCol).as("subLabelCount")).orderBy(F.asc("subLabelCount")).
      select(labelCol, "subLabelCount").collect().map { f =>
      (getIntFromRow(f, 0), f.getLong(1))
    }
    val forLog = labelToCountSeq.map(f => s"${f._1}:${f._2}").mkString(",")
    logger.info(s"computing data stat:${forLog}")

    val labelCount = labelToCountSeq.size

    val labelPartionMap = labelToCountSeq.map(_._1).zipWithIndex.toMap

    if (isSplitWithSubLabel=="true") {

      val dfArray = df.rdd.map { f =>
        (getIntFromRowByName(f, labelCol), f)
      }.collect()

      val splitWithSubLabel = dfArray.groupBy(_._1).flatMap(data => {

        val groupCount = data._2.length
        var splitIndex  = 0
        var beginIndex = 0
        sampleRates.flatMap(percent => {
          val takeCount = (percent * groupCount).toInt
          val endIndex = beginIndex + takeCount
          val splitData = data._2.slice(beginIndex,endIndex).map(wow => {
            Row.fromSeq(wow._2.toSeq ++ Seq(splitIndex))
          })
          splitIndex = splitIndex + 1
          beginIndex = endIndex + 1
          splitData
        })
      }).toSeq


      df.sparkSession.createDataFrame(df.sparkSession.sparkContext.parallelize(splitWithSubLabel), StructType(
        df.schema ++ Seq(StructField("__split__", IntegerType))))

    } else {
      val dfWithLabelPartition = df.rdd.map { f =>
        (getIntFromRowByName(f, labelCol), f)
      }.partitionBy(new Partitioner {
        override def numPartitions: Int = labelCount
        override def getPartition(key: Any): Int = {
          labelPartionMap.getOrElse(key.asInstanceOf[Int], 0)
        }
      }).mapPartitions { iter =>
        val r = scala.util.Random
        iter.map { wow =>
          val item = r.nextFloat()
          val index = newSampleRates.filter { sr =>
            if (sr._2 > 0) {
              val newRate = sr._1
              item <= newRate && item > newSampleRates(sr._2 - 1)._1
            }
            else {
              item <= sr._1
            }
          }.head._2
          Row.fromSeq(wow._2.toSeq ++ Seq(index))
        }
      }
      df.sparkSession.createDataFrame(dfWithLabelPartition, StructType(
        df.schema ++ Seq(StructField("__split__", IntegerType))
      ))

    }

  }

  override def train(df: DataFrame, path: String, params: Map[String, String]): Unit = {
    val newDF = internal_train(df, params)
    newDF.write.mode(SaveMode.Overwrite).parquet(path)
  }

  override def load(sparkSession: SparkSession, path: String, params: Map[String, String]): Any = ???

  override def predict(sparkSession: SparkSession, _model: Any, name: String, params: Map[String, String]): UserDefinedFunction = ???
}