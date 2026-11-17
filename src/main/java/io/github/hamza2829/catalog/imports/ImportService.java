package io.github.hamza2829.catalog.imports;

import io.github.hamza2829.catalog.model.CatalogException;
import io.github.hamza2829.catalog.model.CatalogViews;
import io.github.hamza2829.catalog.model.CatalogViews.ImportReport;
import io.github.hamza2829.catalog.model.CatalogViews.ImportStatus;
import io.github.hamza2829.catalog.model.CatalogViews.RetryReport;
import io.github.hamza2829.catalog.model.SourceRecord;
import io.github.hamza2829.catalog.persistence.ImportWriter;
import io.github.hamza2829.catalog.persistence.ImportWriter.RowOutcome;
import io.github.hamza2829.catalog.telemetry.CatalogTelemetry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.slf4j.spi.LoggingEventBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Accepts uploads, resumes saved checkpoints and publishes committed outcomes. */
@Service
public class ImportService {
    private static final Logger LOG = LoggerFactory.getLogger(ImportService.class);
    private static final Pattern UNSAFE_FILENAME = Pattern.compile("[^\\p{L}\\p{N}._ -]");

    private final SourceReader reader;
    private final ImportWriter writer;
    private final CatalogTelemetry telemetry;
    private final Tracer tracer;

    public ImportService(SourceReader reader, ImportWriter writer, CatalogTelemetry telemetry, Tracer tracer) {
        this.reader = reader;
        this.writer = writer;
        this.telemetry = telemetry;
        this.tracer = tracer;
    }

    public ImportReport importFile(String name, byte[] bytes) {
        Instant startedAt = Instant.now();
        long startedNanos = System.nanoTime();
        Span span = tracer.nextSpan().name("catalog.import").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            return runImport(fileName(name), bytes, startedAt);
        } catch (RuntimeException error) {
            span.error(error);
            telemetry.importDuration("failed", Duration.ofNanos(System.nanoTime() - startedNanos));
            throw error;
        } finally {
            span.end();
        }
    }

    private ImportReport runImport(String fileName, byte[] bytes, Instant startedAt) {
        if (bytes.length == 0) {
            throw new CatalogException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "The uploaded file is empty.");
        }
        String digest = digest(bytes);
        ImportReport known = writer.findByDigest(digest).orElse(null);
        if (known != null && known.status() == ImportStatus.COMPLETE) {
            return replay(known);
        }

        String parseName = known == null ? fileName : known.fileName();
        List<SourceRecord> rows = reader.read(parseName, bytes);
        ImportReport job = writer.open(parseName, digest, rows.size(), startedAt);
        if (job.status() == ImportStatus.COMPLETE) {
            return replay(job);
        }

        UUID id = job.importId();
        try (MDC.MDCCloseable ignored = MDC.putCloseable("import_id", id.toString())) {
            LOG.atInfo().addKeyValue("file", job.fileName()).addKeyValue("checkpoint", job.recordsProcessed())
                    .log("Import started or resumed");
            if (!parseName.equals(job.fileName())) {
                rows = reader.read(job.fileName(), bytes);
            }
            boolean appliedRows = false;
            while (job.status() == ImportStatus.PENDING) {
                var batch = writer.processBatch(id, rows);
                job = batch.report();
                for (RowOutcome row : batch.outcomes()) {
                    publish("import", id, row);
                }
                if (!batch.outcomes().isEmpty()) {
                    appliedRows = true;
                    if (job.status() == ImportStatus.COMPLETE) {
                        logCompletion(job);
                    }
                }
            }
            return appliedRows ? job : replay(job);
        } catch (RuntimeException error) {
            LOG.atError().addKeyValue("import_id", id).setCause(error).log("Import interrupted");
            throw error;
        }
    }

    public RetryReport retry(UUID id) {
        Span span = tracer.nextSpan().name("catalog.retry").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(span)) {
            var result = writer.retry(id);
            if (result.outcome() != null) {
                try (MDC.MDCCloseable ignoredImport =
                             MDC.putCloseable("import_id", result.rejection().importId().toString())) {
                    publish("retry", result.rejection().importId(), result.outcome());
                }
            }
            return new RetryReport(result.rejection(), result.replayed());
        } catch (RuntimeException error) {
            span.error(error);
            throw error;
        } finally {
            span.end();
        }
    }

    private static ImportReport replay(ImportReport report) {
        LOG.atInfo().addKeyValue("import_id", report.importId()).log("Returning an existing import");
        return new ImportReport(report.importId(), report.fileName(), report.sha256(), report.status(),
                report.recordsRead(), report.recordsProcessed(), report.changed(), report.unchanged(),
                report.rejected(), report.rejectedByReason(), report.startedAt(), report.durationMs(), true);
    }

    private void logCompletion(ImportReport report) {
        telemetry.importDuration("complete", Duration.ofMillis(report.durationMs()));
        LOG.atInfo().addKeyValue("changed", report.changed()).addKeyValue("unchanged", report.unchanged())
                .addKeyValue("rejected", report.rejected()).addKeyValue("duration_ms", report.durationMs())
                .log("Import completed");
    }

    private void publish(String operation, UUID importId, RowOutcome row) {
        String disposition = row.disposition().name().toLowerCase(Locale.ROOT);
        telemetry.row(operation, disposition, row.reason());
        LoggingEventBuilder event = row.reason() == null ? LOG.atInfo() : LOG.atWarn();
        event.addKeyValue("record_id", CatalogViews.recordId(importId, row.number()))
                .addKeyValue("record_number", row.number())
                .addKeyValue("article_number", row.articleNumber())
                .addKeyValue("operation", operation)
                .addKeyValue("outcome", disposition);
        if (row.reason() != null) {
            event.addKeyValue("reason", row.reason()).addKeyValue("rejection_id", row.rejectionId());
        }
        event.log("Product row processed");
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String fileName(String name) {
        if (name == null || name.isBlank()) {
            throw new CatalogException(HttpStatus.BAD_REQUEST, "FILE_NAME_REQUIRED", "Give the upload a file name.");
        }
        int separator = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String clean = UNSAFE_FILENAME.matcher(name.substring(separator + 1).strip()).replaceAll("_");
        if (clean.length() > 255 || clean.lastIndexOf('.') <= 0 || clean.endsWith(".")) {
            throw new CatalogException(HttpStatus.BAD_REQUEST, "INVALID_FILE_NAME", "Use a short name and extension.");
        }
        return clean;
    }
}
