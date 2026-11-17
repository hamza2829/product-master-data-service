package io.github.hamza2829.catalog.persistence;

import io.github.hamza2829.catalog.model.CatalogViews.ImportReport;
import io.github.hamza2829.catalog.model.CatalogViews.ImportStatus;
import io.github.hamza2829.catalog.model.RejectionCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "import_jobs")
class StoredImport {

    @Id
    UUID id;

    @Column(nullable = false, unique = true, length = 64)
    String digest;

    @Column(nullable = false, length = 255)
    String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    ImportStatus status;

    @Column(nullable = false)
    int recordsRead;

    @Column(nullable = false)
    int recordsProcessed;

    @Column(nullable = false)
    int changed;

    @Column(nullable = false)
    int unchanged;

    @Column(nullable = false)
    int rejected;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    Map<RejectionCode, Integer> rejectedByReason = new EnumMap<>(RejectionCode.class);

    @Column(nullable = false)
    Instant startedAt;

    Long durationMs;

    protected StoredImport() {
    }

    StoredImport(String fileName, String digest, int recordsRead, Instant startedAt) {
        id = UUID.randomUUID();
        this.fileName = fileName;
        this.digest = digest;
        this.recordsRead = recordsRead;
        this.startedAt = startedAt;
        status = ImportStatus.PENDING;
    }

    void checkpoint(ImportWriter.RowOutcome outcome) {
        recordsProcessed++;
        switch (outcome.disposition()) {
            case CHANGED -> changed++;
            case UNCHANGED -> unchanged++;
            case REJECTED -> {
                rejected++;
                rejectedByReason.merge(outcome.reason(), 1, Integer::sum);
            }
        }
        if (recordsProcessed == recordsRead) {
            status = ImportStatus.COMPLETE;
            durationMs = Math.max(0, Duration.between(startedAt, Instant.now()).toMillis());
        }
    }

    ImportReport report(boolean replayed) {
        return new ImportReport(id, fileName, digest, status, recordsRead, recordsProcessed, changed, unchanged,
                rejected, Map.copyOf(rejectedByReason), startedAt, durationMs, replayed);
    }
}
