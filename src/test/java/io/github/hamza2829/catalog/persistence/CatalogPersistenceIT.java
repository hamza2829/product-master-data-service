package io.github.hamza2829.catalog.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hamza2829.catalog.imports.ImportService;
import io.github.hamza2829.catalog.model.CatalogViews.ImportReport;
import io.github.hamza2829.catalog.model.CatalogViews.ImportStatus;
import io.github.hamza2829.catalog.model.CatalogViews.Rejection;
import io.github.hamza2829.catalog.model.LifecycleStatus;
import io.github.hamza2829.catalog.model.RejectionCode;
import io.github.hamza2829.catalog.rules.RuleConfiguration;
import io.github.hamza2829.catalog.rules.RuleField;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "catalog.imports.batch-size=2")
@Testcontainers
class CatalogPersistenceIT {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private ImportService imports;

    @Autowired
    private CatalogQueries catalog;

    @Autowired
    private Imports jobs;

    @Autowired
    private JdbcTemplate sql;

    @Autowired
    private MeterRegistry metrics;

    @Autowired
    private RuleConfiguration rules;

    @BeforeEach
    void clearDatabase() {
        sql.execute("truncate table import_accepted_articles, dead_letters, product_changes, products, import_jobs");
    }

    @Test
    void searchesCanonicalProductsAndKeepsNewestHistoryFirst() {
        write(row(1, "230 V"), row(2, "0.4 kV"));
        write("""
                {"sku":"IND-000001","status":"phase out","price":"24,99 EUR"}
                """.strip());
        var results = catalog.search("beacon", "RELAYS", LifecycleStatus.PHASE_OUT, 0, 1);
        assertThat(results.totalElements()).isEqualTo(1);
        assertThat(results.items().getFirst().articleNumber()).isEqualTo("IND-000001");

        var details = catalog.product("ind-000001", 0, 10);
        assertThat(details.product().priceEur()).isEqualByComparingTo("24.99");
        assertThat(details.history().items()).hasSize(2);
        assertThat(details.history().items().getFirst().before().priceEur()).isEqualByComparingTo("12.50");
        assertThat(details.history().items().getLast().before()).isNull();
    }

    @Test
    void exactFileReplayKeepsImportAndHistoryUnchanged() {
        byte[] file = file(row(1, "10 V"), row(2, "20 V"));
        var first = imports.importFile("first.json", file);
        var second = imports.importFile("renamed.json", file);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.importId()).isEqualTo(first.importId());
        assertThat(second.durationMs()).isEqualTo(first.durationMs());
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(catalog.product("IND-000001", 0, 20).history().totalElements()).isEqualTo(1);
    }

    @Test
    void aDifferentFileWithEquivalentValuesDoesNotAddARevision() {
        var first = write(row(1, "10 V"));
        var second = write(row(1, "0,01 kV"));
        assertThat(second.importId()).isNotEqualTo(first.importId());
        assertThat(second.unchanged()).isEqualTo(1);
        assertThat(second.changed()).isZero();
        assertThat(catalog.product("IND-000001", 0, 20).history().totalElements()).isEqualTo(1);
    }

    @Test
    void aBrokenBatchCommitsValidRowsAndRetriesWhenTheRulesChange() {
        var report = write(row(1, "24 V"), row(2, "1700 V"), row(3, "50 V"), row(1, "24 V"));
        assertThat(report.recordsProcessed()).isEqualTo(4);
        assertThat(report.changed()).isEqualTo(2);
        assertThat(report.rejectedByReason()).containsEntry(RejectionCode.OUT_OF_RANGE, 1)
                .containsEntry(RejectionCode.DUPLICATE_ARTICLE, 1);
        Rejection rejected = catalog.rejections(RejectionCode.OUT_OF_RANGE, false, 0, 20).items().getFirst();
        assertThat(rejected.recordNumber()).isEqualTo(2);
        assertThat(imports.retry(rejected.id()).rejection().retryAttempts()).isEqualTo(1);
        assertThat(imports.retry(rejected.id()).rejection().resolvedAt()).isNull();

        RuleConfiguration.Bounds original = rules.ranges().get(RuleField.VOLTAGE);
        rules.ranges().put(RuleField.VOLTAGE, new RuleConfiguration.Bounds(original.min(), new BigDecimal("2000")));
        try {
            var fixed = imports.retry(rejected.id());
            assertThat(fixed.rejection().resolvedAt()).isNotNull();
            assertThat(fixed.rejection().retryAttempts()).isEqualTo(3);
            assertThat(imports.retry(rejected.id()).replayed()).isTrue();
            assertThat(catalog.rejections(RejectionCode.OUT_OF_RANGE, false, 0, 20).items()).isEmpty();
            assertThat(catalog.product("IND-000002", 0, 20).product().voltageV()).isEqualByComparingTo("1700");
            assertThat(catalog.importJob(report.importId()).rejected()).isEqualTo(2);
        } finally {
            rules.ranges().put(RuleField.VOLTAGE, original);
        }
    }

    @Test
    void retryWillNotOverwriteAProductChangedAfterTheRejection() {
        write(row(1, "24 V"));
        write(row(1, "1700 V"));
        Rejection rejected = catalog.rejections(RejectionCode.OUT_OF_RANGE, false, 0, 20).items().getFirst();
        write(row(1, "48 V"));

        RuleConfiguration.Bounds original = rules.ranges().get(RuleField.VOLTAGE);
        rules.ranges().put(RuleField.VOLTAGE, new RuleConfiguration.Bounds(original.min(), new BigDecimal("2000")));
        try {
            assertThat(imports.retry(rejected.id()).rejection().reason()).isEqualTo(RejectionCode.STALE_RETRY);
            assertThat(catalog.product("IND-000001", 0, 20).product().voltageV()).isEqualByComparingTo("48");
        } finally {
            rules.ranges().put(RuleField.VOLTAGE, original);
        }
    }

    @Test
    void interruptedImportResumesAtLastCommittedCheckpoint() {
        byte[] file = file(row(1, "10 V"), row(2, "20 V"), row(3, "30 V"), row(4, "40 V"));
        sql.execute("alter table products add constraint test_block_article check (article_number <> 'IND-000004')");
        try {
            assertThatThrownBy(() -> imports.importFile("interrupted.json", file))
                    .isInstanceOf(DataIntegrityViolationException.class);
            var job = jobs.findAll().getFirst();
            assertThat(job.status).isEqualTo(ImportStatus.PENDING);
            assertThat(job.recordsProcessed).isEqualTo(2);
            assertThat(catalog.search(null, null, null, 0, 20).totalElements()).isEqualTo(2);
        } finally {
            sql.execute("alter table products drop constraint test_block_article");
        }
        var completed = imports.importFile("interrupted.json", file);
        assertThat(completed.status()).isEqualTo(ImportStatus.COMPLETE);
        assertThat(completed.recordsProcessed()).isEqualTo(4);
        assertThat(completed.changed()).isEqualTo(4);
        assertThat(catalog.product("IND-000001", 0, 20).history().totalElements()).isEqualTo(1);
    }

    @Test
    void concurrentIdenticalUploadsShareOneCheckpointAndHistory() throws Exception {
        byte[] file = file(row(1, "10 V"), row(2, "20 V"), row(3, "30 V"));
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> {
                start.await();
                return imports.importFile("parallel.json", file);
            });
            var second = executor.submit(() -> {
                start.await();
                return imports.importFile("parallel.json", file);
            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS).importId())
                    .isEqualTo(second.get(20, TimeUnit.SECONDS).importId());
        }
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(catalog.product("IND-000003", 0, 20).history().totalElements()).isEqualTo(1);
    }

    @Test
    void metricsCountCommittedRowsByOutcomeAndReasonAndIgnoreReplays() {
        double processed = processedRows();
        double rejected = rejectedRows();
        long importsCompleted = completedImports();
        byte[] file = file(row(1, "24 V"), row(2, "-20 V"));

        imports.importFile("metrics.json", file);
        assertThat(processedRows() - processed).isEqualTo(2);
        assertThat(rejectedRows() - rejected).isEqualTo(1);
        assertThat(completedImports() - importsCompleted).isEqualTo(1);
        assertThat(metrics.get("catalog.rule.execution").tag("rule", "validation").timer().count())
                .isGreaterThanOrEqualTo(2);

        imports.importFile("metrics.json", file);
        assertThat(processedRows() - processed).isEqualTo(2);
        assertThat(rejectedRows() - rejected).isEqualTo(1);
        assertThat(completedImports() - importsCompleted).isEqualTo(1);
    }

    private double processedRows() {
        return metrics.find("catalog.records.processed").tag("operation", "import").counters().stream()
                .mapToDouble(Counter::count).sum();
    }

    private double rejectedRows() {
        return metrics.find("catalog.records.rejected").tags("operation", "import", "reason", "OUT_OF_RANGE")
                .counters().stream().mapToDouble(Counter::count).sum();
    }

    private long completedImports() {
        return metrics.find("catalog.import.duration").tag("outcome", "complete").timers().stream()
                .mapToLong(timer -> timer.count()).sum();
    }

    private ImportReport write(String... rows) {
        return imports.importFile("batch.json", file(rows));
    }

    private static byte[] file(String... rows) {
        return ("[" + String.join(",", rows) + "]").getBytes(StandardCharsets.UTF_8);
    }

    private static String row(int id, String voltage) {
        return String.format(Locale.ROOT, """
                {"sku":"IND-%06d","name":"Beacon Relay","voltage":"%s","current":"5 A","width":"16 mm",
                "height":"20 mm","depth":"10 mm","ip_rating":"IP54","material":"Steel","status":"active",
                "unit":"pcs","price":"12.50 EUR"}
                """, id, voltage).strip();
    }
}
