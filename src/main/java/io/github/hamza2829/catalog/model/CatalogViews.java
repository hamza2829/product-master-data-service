package io.github.hamza2829.catalog.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;

/** Public response types used by the product and import APIs. */
public final class CatalogViews {

    private CatalogViews() {
    }

    /** Stable page envelope for catalog searches and change history. */
    public record Results<T>(List<T> items, int page, int size, long totalElements, int totalPages) {
        public static <T> Results<T> from(Page<T> page) {
            return new Results<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
                    page.getTotalPages());
        }
    }

    /** One committed creation or update of a product. */
    public record Change(long revision, Instant changedAt, UUID importId, int recordNumber,
                         ProductData before, ProductData after) {
    }

    /** The current product with a page of its immutable history. */
    public record ProductDetails(ProductData product, Instant updatedAt, Results<Change> history) {
    }

    /** Import state and counters from the committed checkpoint. */
    public record ImportReport(UUID importId, String fileName, String sha256, ImportStatus status, int recordsRead,
                               int recordsProcessed, int changed, int unchanged, int rejected,
                               Map<RejectionCode, Integer> rejectedByReason, Instant startedAt,
                               Long durationMs, boolean replayed) {
    }

    /** Indicates whether there are rows remaining in an import. */
    public enum ImportStatus {
        PENDING,
        COMPLETE
    }

    /** Dead letter metadata including the original reason and the latest retry result. */
    public record Rejection(UUID id, UUID importId, int recordNumber, String recordId, String articleNumber,
                            RejectionCode initialReason, RejectionCode reason, String detail, Instant rejectedAt,
                            int retryAttempts, Instant lastAttemptAt, Instant resolvedAt) {
    }

    /** Response from replaying a stored rejected row against the current configuration. */
    public record RetryReport(Rejection rejection, boolean replayed) {
    }

    public static String recordId(UUID importId, int recordNumber) {
        return importId + ":" + recordNumber;
    }
}
