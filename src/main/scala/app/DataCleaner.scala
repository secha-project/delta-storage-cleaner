package app

import org.apache.spark.sql.SparkSession
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.functions.col


object DataCleaner extends App {
    val logPrefix: String = "DataCleaner: "
    val filePrefix: String = "file:"

    if (args.length == 1 && args(0) == "--help") {
        printHelp()
        System.exit(0)
    }

    if (args.length != 4) {
        printHelp()
        System.exit(1)
    }

    // NOTE: no checking is done for the user input
    val schemaName: String = args(0)
    val tableName: String = args(1)
    val orderColumns: Seq[String] = args(2).split(",").iterator.map(_.trim).filter(_.nonEmpty).toIndexedSeq
    val maxFileSize: Int = args(3).toInt
    if (maxFileSize <= 0) {
        println(s"${logPrefix}Error: <max-file-size> must be a positive integer (MB).")
        System.exit(1)
    }

    // NOTE: only checks for presence of environment variables, not their validity
    val sparkUrl: String = System.getenv("SPARK_URL")
    val ucUrl: String = System.getenv("UC_URL")
    val ucToken: String = System.getenv("UC_TOKEN")
    val ucCatalog: String = System.getenv("UC_CATALOG")

    val missingEnvVars: List[String] = List(
        "SPARK_URL" -> sparkUrl,
        "UC_URL" -> ucUrl,
        "UC_TOKEN" -> ucToken,
        "UC_CATALOG" -> ucCatalog,
    )
        .filter({case (_, value) => value == null || value.isEmpty})
        .map({case (name, _) => name})

    if (missingEnvVars.nonEmpty) {
        println(s"${logPrefix}Error: Missing required environment variables: ${missingEnvVars.mkString(", ")}")
        printHelp()
        System.exit(1)
    }

    def printHelp(): Unit = {
        println(s"${logPrefix}Usage: DataCleaner <schema> <table> <order-columns> <max-file-size>")
        println(s"${logPrefix}  <schema>: the schema of the target table")
        println(s"${logPrefix}  <table>: the name of the target table")
        println(s"${logPrefix}  <order-columns>: a comma separated list of column names that are used to order the data")
        println(s"${logPrefix}  <max-file-size>: the maximum size of each parquet file in MB")
        println(logPrefix)
        println(s"${logPrefix}The following environment variables are required:")
        println(s"${logPrefix}- SPARK_URL : URL for Spark Connect server (e.g. 'sc://127.0.0.1:15002')")
        println(s"${logPrefix}- UC_URL: URL for Unity Catalog server (e.g. 'http://127.0.0.1:8080')")
        println(s"${logPrefix}- UC_TOKEN : Access token for Unity Catalog")
        println(s"${logPrefix}- UC_CATALOG : Catalog name for Unity Catalog")
        println(logPrefix)
        println(s"${logPrefix}  - WARNING: do not run the cleaner if the data is still being written to the table")
    }

    val spark: SparkSession = try {
        SparkSession
            .builder()
            .config("spark.sql.catalog.unity.uri", ucUrl)
            .config("spark.sql.catalog.unity.token", ucToken)
            .config("spark.sql.defaultCatalog", ucCatalog)
            .config("spark.api.mode", "connect")
            .remote(sparkUrl)
            .getOrCreate()
    }
    catch {
        case error: Exception =>
            println(s"${logPrefix}Error creating Spark session: ${error.getMessage}")
            error.printStackTrace()
            throw error
    }

    try {
        mainSparkLogic()
        spark.stop()
    }
    catch {
        case error: Exception =>
            println(s"${logPrefix}Error during cleaning process: ${error.getMessage}")
            error.printStackTrace()
            spark.stop()
    }

    def mainSparkLogic(): Unit = {
        spark.conf.set("spark.databricks.delta.optimize.maxFileSize", maxFileSize.toLong * 1024L * 1024L)
        spark.conf.set("spark.sql.debug.maxToStringFields", 1000)
        spark.conf.set("spark.databricks.delta.retentionDurationCheck.enabled", false)


        case class TableStorageStats(location: String, numFiles: Long, sizeMb: Double)

        def getTableStorageStats(schema: String, table: String): TableStorageStats = {
            // Query table metadata via Spark SQL so it works in Spark Connect mode.
            val detail = spark
                .sql(s"DESCRIBE DETAIL `${schema.replace("`", "``")}`.`${table.replace("`", "``")}`")
                .collect()
                .headOption

            val storedLocation: String = detail
                .map(row => row.getAs[String]("location"))
                .getOrElse("")

            val numFiles: Long = detail
                .map(row => row.getAs[Long]("numFiles"))
                .getOrElse(0L)

            val sizeMb: Double = detail
                .map(row => row.getAs[Long]("sizeInBytes"))
                .map(bytes => ((bytes.toDouble / (1024 * 1024)) * 100).round / 100.0)
                .getOrElse(0.0)

            val location =
                if (storedLocation.startsWith(filePrefix)) {
                    storedLocation.stripPrefix(filePrefix)
                } else {
                    storedLocation
                }

            TableStorageStats(location, numFiles, sizeMb)
        }

        def printInfo(count: Long, stats: TableStorageStats): Unit = {
            println(s"${logPrefix}- ${count} data rows in total at ${stats.location}")
            println(s"${logPrefix}- ${stats.sizeMb} MB of data in ${stats.numFiles} parquet file(s) at ${stats.location}")
        }

        def rewriteTableForCompaction(fullTableName: String, stats: TableStorageStats, orderCols: Seq[String]): Unit = {
            val targetSizeMB = maxFileSize.toLong
            val targetFiles = math.max(1, math.ceil(stats.sizeMb / targetSizeMB).toInt)

            val sortedDf =
                if (orderCols.nonEmpty) {
                    spark.table(fullTableName).sortWithinPartitions(orderCols.map(col): _*)
                } else {
                    spark.table(fullTableName)
                }

            sortedDf
                .repartition(targetFiles)
                .write
                .format("delta")
                .mode("overwrite")
                .option("dataChange", "false")
                .save(stats.location)

            println(s"${logPrefix}Fallback compaction finished using repartition(${targetFiles}) for ${fullTableName}")
        }

        val fullTableName: String = s"`${schemaName.replace("`", "``")}`.`${tableName.replace("`", "``")}`"
        val statsBefore: TableStorageStats = try {
             getTableStorageStats(schemaName, tableName)
        }
        catch {
            case error: Exception =>
                println(s"${logPrefix}Error retrieving table storage stats: ${error.getMessage}")
                error.printStackTrace()
                TableStorageStats("", 0L, 0.0)
        }
        if (statsBefore.location.isEmpty) {
            println(s"${logPrefix}Error: Could not get data location for ${schemaName}.${tableName}")
            spark.stop()
            System.exit(1)
        }

        println(s"${logPrefix}Before cleaning:")
        printInfo(spark.table(fullTableName).count(), statsBefore)

        // Use SQL OPTIMIZE when available; otherwise fall back to a rewrite-based compaction.
        try {
            println(s"${logPrefix}Running OPTIMIZE to compact data...")
            val zOrderClause =
                if (orderColumns.nonEmpty) {
                    val cols = orderColumns.map(c => s"`${c.replace("`", "``")}`").mkString(",")
                    s" ZORDER BY ($cols)"
                } else {
                    ""
                }
            spark.sql(s"OPTIMIZE ${fullTableName}$zOrderClause")
        }
        catch {
            case _: ParseException =>
                println(s"${logPrefix}OPTIMIZE not supported by this Spark/Delta runtime. Falling back to rewrite-based compaction.")
                rewriteTableForCompaction(fullTableName, statsBefore, orderColumns)
        }

        // Run vacuum operation to remove all unnecessary data files.
        println(s"${logPrefix}Running VACUUM to remove old unnecessary data files...")
        spark.sql(s"VACUUM ${fullTableName} RETAIN 0 HOURS")

        // Check the table size again from scratch.
        val rowCount: Long = spark.table(fullTableName).count()
        val statsAfter: TableStorageStats = getTableStorageStats(schemaName, tableName)
        println(s"${logPrefix}After optimization and vacuum:")
        printInfo(rowCount, statsAfter)
    }
}
