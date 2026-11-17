package io.github.hamza2829.catalog.persistence;

import io.github.hamza2829.catalog.model.CatalogViews;
import io.github.hamza2829.catalog.model.CatalogViews.Rejection;
import io.github.hamza2829.catalog.model.RejectionCode;
import io.github.hamza2829.catalog.model.SourceRecord;
import io.github.hamza2829.catalog.rules.RuleFailure;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "dead_letters")
class DeadLetter {

    @Id
    UUID id;

    @Column(nullable = false)
    UUID importId;

    @Column(nullable = false)
    int recordNumber;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    SourceRecord source;

    @Column(length = 64)
    String articleNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    RejectionCode initialReason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 48)
    RejectionCode reason;

    @Column(nullable = false, columnDefinition = "text")
    String detail;

    @Column(nullable = false)
    Instant rejectedAt;

    @Column(nullable = false)
    long rejectedRevision;

    @Column(nullable = false)
    int retryAttempts;

    Instant lastAttemptAt;
    Instant resolvedAt;

    protected DeadLetter() {
    }

    DeadLetter(UUID importId, int recordNumber, SourceRecord source, String articleNumber,
               RuleFailure failure, long revision) {
        id = UUID.randomUUID();
        this.importId = importId;
        this.recordNumber = recordNumber;
        this.source = source;
        this.articleNumber = articleNumber;
        initialReason = failure.code();
        reason = failure.code();
        detail = failure.getMessage();
        rejectedAt = Instant.now();
        rejectedRevision = revision;
    }

    void attempted(RuleFailure failure, String articleNumber) {
        retryAttempts++;
        lastAttemptAt = Instant.now();
        this.articleNumber = articleNumber;
        if (failure == null) {
            resolvedAt = lastAttemptAt;
        } else {
            reason = failure.code();
            detail = failure.getMessage();
        }
    }

    Rejection view() {
        return new Rejection(id, importId, recordNumber, CatalogViews.recordId(importId, recordNumber), articleNumber,
                initialReason, reason, detail, rejectedAt, retryAttempts, lastAttemptAt, resolvedAt);
    }
}
