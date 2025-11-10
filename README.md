# Cleaner and optimizer for Delta tables

- Compiling the project: `./compile_cleaner.sh`
- Running the project: `./run_cleaner.sh <data-path> <order-columns> <max-file-size>`

Example:

```bash
export SPARK_HOME=/opt/spark-3.5.3
./run_cleaner.sh /path/to/delta/table/ timestamp 100
```

Optimizes the delta table at `/path/to/delta/table/` based on the `timestamp` column and aims for a maximum file size of 100 MB.
