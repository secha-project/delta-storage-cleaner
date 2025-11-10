package app

import io.delta.tables.DeltaTable
import java.nio.file.{Files, Path}
import org.apache.spark.sql.SparkSession
import scala.collection.JavaConverters.asScalaIteratorConverter


object DataCleaner extends App {
    val logPrefix: String = "DataCleaner: "

    if (args.length == 1 && args(0) == "--help") {
        printHelp()
        System.exit(0)
    }

    if (args.length != 3) {
        printHelp()
        System.exit(1)
    }
    println(s"${logPrefix}Arguments: ${args.mkString(", ")}, length: ${args.length}")

    // No argument validation is done here!
    val dataPath: String = args(0)
    val orderColumns: Seq[String] = args(1).split(",")
    val maxFileSize: Int = args(2).toInt


    val spark : SparkSession= SparkSession
        .builder()
        .appName("delta-table-cleaner")
        .config("spark.sql.extensions", "io.delta.sql.DeltaSparkSessionExtension")
        .config("spark.sql.catalog.spark_catalog", "org.apache.spark.sql.delta.catalog.DeltaCatalog")
        .master("local")
        .getOrCreate()

    spark.conf.set("spark.databricks.delta.optimize.maxFileSize", (maxFileSize.toDouble * 1024 * 1024).toInt)
    spark.conf.set("spark.sql.debug.maxToStringFields", 1000)
    spark.conf.set("spark.databricks.delta.retentionDurationCheck.enabled", false)
    spark.sparkContext.setLogLevel(org.apache.log4j.Level.WARN.toString())


    def printHelp(): Unit = {
        println(s"${logPrefix}Usage: DataCleaner <data-path> <order-columns> <max-file-size>")
        println(s"${logPrefix}  <data-path>: path to the delta table folder")
        println(s"${logPrefix}  <order-columns>: a comma separated list of column names that are used to order the data")
        println(s"${logPrefix}  <max-file-size>: the maximum size of each parquet file in MB")
        println(s"${logPrefix}  - WARNING: do not run the cleaner if the data is still being written to the table")
    }

    def getParquetFileList(path: String): List[Path] = {
        Files
            .list(Path.of(path))
            .iterator()
            .asScala
            .filter(x => x.toString().endsWith(".parquet"))
            .toList
    }

    def getFileCount(path: String): Int = {
        getParquetFileList(path).size
    }

    def pathSizeInMB(filePath: String): Double = {
        (
            (
                getParquetFileList(filePath)
                    .map(x => Files.size(x))
                    .sum
            )
            .toDouble / (1024 * 1024) * 100
        )
            .round / 100.0
    }

    def printInfo(count: Long, path: String): Unit = {
        println(s"${logPrefix}- ${count} data rows in total at ${path}")
        println(s"${logPrefix}- ${pathSizeInMB(path)} MB of data in ${getFileCount(path)} parquet file(s) at ${path}")
    }


    // Load the data from the target folder as a Delta table
    val deltaTable = DeltaTable.forPath(spark, dataPath)
    println(s"${logPrefix}Before cleaning:")
    printInfo(deltaTable.toDF.count(), dataPath)

    // Optimize the table
    deltaTable
        .optimize()
        .executeZOrderBy(orderColumns: _*)

    // Run vacuum operation to remove all unnecessary data files
    deltaTable
        .vacuum(0)

    // Check the table size again from scratch
    val rowCount: Long = spark
        .read
        .format("delta")
        .load(dataPath)
        .count()
    println(s"${logPrefix}After optimization and vacuum:")
    printInfo(rowCount, dataPath)


    spark.stop()
}
