# Import runbook

## Find a rejected row

Open the CI run and download the `import-report` artifact. In `report.json`, find the file under `imports` and copy its `importId`. Search `service.log` for that value under `import_id`. Each row has a `record_id` made from the import ID and the one-based data row number. CSV row numbers count data records after the header, including records that span more than one physical line. JSON and XML row numbers follow the source order.

When the service is running in the workflow, use its loopback API to look up an import and filter dead letters by reason.

```bash
curl -fsS "http://127.0.0.1:8080/api/v1/imports/$IMPORT_ID"
curl -fsS "http://127.0.0.1:8080/api/v1/rejections?reason=OUT_OF_RANGE"
```

The dead letter gives you the article if it could be read, the rule's code, a short detail, and the retry count. Use `traceId` to follow a particular request in the logs and `record_id` to link the original row with later retry attempts. The source values stay in the database and can be checked against the original fixture in the `synthetic-inputs` artifact.

## Fix the cause and retry

- **Missing value, invalid number or unknown unit.** Correct the source row and upload it in a new file. The same bytes return the original report. Empty cells preserve existing values on updates.
- **Out of range or an unmapped source value.** Check the source first. If the value should be supported, put a deliberate override in `rules-override.yml` in the running service's working directory and restart with the same database. For example, this updates the configured voltage cap in volts while keeping the existing lower bound.

```yaml
catalog:
  rules:
    ranges:
      VOLTAGE:
        max: 1800
```

- **Duplicate article.** Compare the two rows. The first accepted row in a file takes precedence. Send an intentional update in a new file when its values should change.
- **Illegal lifecycle transition.** Check the product's history. A discontinued product cannot return to active under the default rules. If an obsolete rejected row would overwrite a newer change, retry reports `STALE_RETRY`. Use the current product as the basis for a new update.

After a rule change, retry the stored row by its rejection ID.

```bash
curl -fsS -X POST "http://127.0.0.1:8080/api/v1/rejections/$REJECTION_ID/retry"
```

A resolved row has a `resolvedAt` timestamp. List resolved rows with `includeResolved=true`. The original import report keeps its original counts. A successful retry contributes its own metrics under `operation="retry"`.

## Fix a failing import

1. Check `/actuator/health/readiness`. Readiness checks whether the app can accept work and its PostgreSQL connection is available. If the process fails at startup, inspect the first exception in `service.log` for the connection, migration or configuration error.
2. If the upload returns `MALFORMED_FILE`, check the file extension and structure. An unreadable file has no checkpoint. For HTTP request errors, look for the problem response's `code` and `traceId`.
3. If an import was interrupted, find the `Import interrupted` log and inspect the failure. A report with status `PENDING` shows how many rows committed. After fixing the database or connection, send the original bytes again to continue from that checkpoint.
4. If the report says `COMPLETE` with rejected rows, inspect the dead letters. Valid rows have already committed.

## Monitor the service

Scrape `/actuator/prometheus` while the service runs. Import the [Grafana dashboard](grafana/product-master-data.json) and select the Prometheus data source when prompted. Its panels show the processed row rate, rejection rate by reason, and the p95 for rule and import durations. The import report artifact includes `metrics.prom` for a point-in-time view of the baseline run. Correlation IDs are log fields.
