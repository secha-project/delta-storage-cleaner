# Cleaner and optimizer for Delta tables

- Compiling the project: `./compile_cleaner.sh`
- Environment variables are read from the `.env` file
- Running the project: `./run_cleaner.sh <schema> <table> <order-columns> <max-file-size>`

Example:

```bash
# Use .env.template file as a template and create .env file with required environment variables
# The required environment variables are:
# - SPARK_URL  : URL for Spark Connect server (e.g. 'sc://127.0.0.1:15002')
# - UC_URL     : URL for Unity Catalog server (e.g. 'http://127.0.0.1:8080')
# - UC_TOKEN   : Access token for Unity Catalog
# - UC_CATALOG : Catalog name for Unity Catalog

./run_cleaner.sh data_source table_name timestamp 100
```

Optimizes the delta table stored in the Unity Catalog at schema `data_source` and table name `table_name` based on the `timestamp` column and aims for a maximum file size of 100 MB.
