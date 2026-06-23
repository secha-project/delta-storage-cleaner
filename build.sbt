name := "data-cleaner"
version := "1.1.1"
scalaVersion := "2.13.18"

val MainClass: String = "app.DataCleaner"

val SparkVersion: String = "4.1.1"

Compile / run / mainClass := Some(MainClass)
Compile / scalacOptions += "-Xlint"
assembly / mainClass := Some(MainClass)
assembly / assemblyJarName := s"${name.value}-${version.value}.jar"
assembly / assemblyMergeStrategy := {
	case path if path == "module-info.class" => MergeStrategy.discard
	case path if path.startsWith("META-INF/versions/") && path.endsWith("/module-info.class") => MergeStrategy.discard
	case "META-INF/org/apache/logging/log4j/core/config/plugins/Log4j2Plugins.dat" => MergeStrategy.discard
	case path => (assembly / assemblyMergeStrategy).value(path)
}

libraryDependencies += "org.apache.spark" %% "spark-connect-client-jvm" % SparkVersion
