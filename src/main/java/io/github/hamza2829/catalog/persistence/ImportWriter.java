package io.github.hamza2829.catalog.persistence;

import io.github.hamza2829.catalog.imports.ImportSettings;
import io.github.hamza2829.catalog.model.CatalogException;
import io.github.hamza2829.catalog.model.CatalogViews.ImportReport;
import io.github.hamza2829.catalog.model.CatalogViews.Rejection;
import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.model.RejectionCode;
import io.github.hamza2829.catalog.model.SourceRecord;
import io.github.hamza2829.catalog.rules.RuleContext;
import io.github.hamza2829.catalog.rules.RuleEngine;
import io.github.hamza2829.catalog.rules.RuleFailure;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Commits import checkpoints, product updates and dead letters in database transactions. */
@Service
public class ImportWriter {
    private final Imports imports;
    private final Products products;
    private final ProductChanges changes;
    private final DeadLetters deadLetters;
    private final JdbcTemplate sql;
    private final RuleEngine engine;
    private final ImportSettings settings;

    ImportWriter(Imports imports, Products products, ProductChanges changes, DeadLetters deadLetters,
                 JdbcTemplate sql, RuleEngine engine, ImportSettings settings) {
        this.imports = imports;
        this.products = products;
        this.changes = changes;
        this.deadLetters = deadLetters;
        this.sql = sql;
        this.engine = engine;
        this.settings = settings;
    }

    @Transactional(readOnly = true)
    public Optional<ImportReport> findByDigest(String digest) {
        return imports.findByDigest(digest).map(job -> job.report(false));
    }

    @Transactional
    public ImportReport open(String fileName, String digest, int rowCount, Instant startedAt) {
        lockWrites();
        StoredImport job = imports.findByDigest(digest)
                .orElseGet(() -> imports.save(new StoredImport(fileName, digest, rowCount, startedAt)));
        return job.report(false);
    }

    @Transactional
    public Batch processBatch(UUID importId, List<SourceRecord> rows) {
        lockWrites();
        StoredImport job = imports.findById(importId).orElseThrow();
        int end = Math.min(job.recordsProcessed + settings.batchSize(), job.recordsRead);
        List<RowOutcome> outcomes = new ArrayList<>(end - job.recordsProcessed);
        while (job.recordsProcessed < end) {
            int number = job.recordsProcessed + 1;
            RowOutcome outcome = processRow(importId, number, rows.get(number - 1), null);
            job.checkpoint(outcome);
            outcomes.add(outcome);
        }
        return new Batch(job.report(false), List.copyOf(outcomes));
    }

    @Transactional
    public RetryResult retry(UUID rejectionId) {
        lockWrites();
        DeadLetter letter = deadLetters.findById(rejectionId).orElseThrow(() ->
                new CatalogException(HttpStatus.NOT_FOUND, "REJECTION_NOT_FOUND", "Rejected record was not found."));
        if (letter.resolvedAt != null) {
            return new RetryResult(letter.view(), null, true);
        }
        RowOutcome outcome = processRow(letter.importId, letter.recordNumber, letter.source, letter);
        return new RetryResult(letter.view(), outcome, false);
    }

    private RowOutcome processRow(UUID importId, int number, SourceRecord source, DeadLetter retry) {
        RuleContext context = new RuleContext(source, article ->
                products.findById(article).map(product -> product.productData).orElse(null),
                article -> wasAccepted(importId, article));
        try {
            ProductData data = engine.evaluate(context);
            Disposition disposition = applyProduct(importId, number, data, retry, context.previous());
            sql.update("""
                    insert into import_accepted_articles (import_id, article_number)
                    values (?, ?) on conflict do nothing
                    """, importId, data.articleNumber());
            if (retry != null) {
                retry.attempted(null, data.articleNumber());
            }
            return new RowOutcome(number, data.articleNumber(), disposition, null, null);
        } catch (RuleFailure failure) {
            String article = context.articleNumber();
            if (article != null && article.length() > 64) {
                article = null;
            }
            DeadLetter letter = retry;
            if (letter == null) {
                letter = deadLetters.save(new DeadLetter(importId, number, source, article, failure, nextRevision()));
            } else {
                letter.attempted(failure, article);
            }
            return new RowOutcome(number, article, Disposition.REJECTED, failure.code(), letter.id);
        }
    }

    private Disposition applyProduct(UUID importId, int number, ProductData data, DeadLetter retry,
                                     ProductData before) {
        StoredProduct product = products.findById(data.articleNumber())
                .orElseGet(() -> new StoredProduct(data.articleNumber()));
        if (retry != null && product.revision > retry.rejectedRevision) {
            throw new RuleFailure(RejectionCode.STALE_RETRY, "A newer change already updated this article");
        }
        if (Objects.equals(data, before)) {
            return Disposition.UNCHANGED;
        }
        product.update(data, nextRevision(), Instant.now());
        if (before == null) {
            product = products.save(product);
        }
        changes.save(new StoredChange(product, importId, number, before));
        return Disposition.CHANGED;
    }

    private boolean wasAccepted(UUID importId, String article) {
        return Boolean.TRUE.equals(sql.queryForObject("""
                select exists (select 1 from import_accepted_articles where import_id = ? and article_number = ?)
                """, Boolean.class, importId, article));
    }

    private long nextRevision() {
        return Objects.requireNonNull(sql.queryForObject("select nextval('catalog_revision')", Long.class));
    }

    private void lockWrites() {
        // Hold the lock only for this transaction. Every writer uses the same ordering.
        sql.queryForObject("select id from catalog_write_lock where id = 1 for update", Integer.class);
    }

    /** Results from a committed batch, with events ready for logging and metrics. */
    public record Batch(ImportReport report, List<RowOutcome> outcomes) {
    }

    /** Disposition of an individual attempted row. */
    public enum Disposition {
        CHANGED,
        UNCHANGED,
        REJECTED
    }

    /** Metadata about one committed outcome. */
    public record RowOutcome(int number, String articleNumber, Disposition disposition, RejectionCode reason,
                             UUID rejectionId) {
    }

    /** Persisted dead letter and the event emitted by this retry, if any. */
    public record RetryResult(Rejection rejection, RowOutcome outcome, boolean replayed) {
    }
}
