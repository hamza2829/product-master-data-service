package io.github.hamza2829.catalog.persistence;

import io.github.hamza2829.catalog.model.CatalogViews.Change;
import io.github.hamza2829.catalog.model.ProductData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "product_changes")
class StoredChange {

    @Id
    Long revision;

    @Column(nullable = false, length = 64)
    String articleNumber;

    @Column(nullable = false)
    UUID importId;

    @Column(nullable = false)
    int recordNumber;

    @Column(nullable = false)
    Instant changedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    ProductData beforeData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    ProductData afterData;

    protected StoredChange() {
    }

    StoredChange(StoredProduct product, UUID importId, int recordNumber, ProductData beforeData) {
        revision = product.revision;
        articleNumber = product.articleNumber;
        this.importId = importId;
        this.recordNumber = recordNumber;
        changedAt = product.updatedAt;
        this.beforeData = beforeData;
        afterData = product.productData;
    }

    Change view() {
        return new Change(revision, changedAt, importId, recordNumber, beforeData, afterData);
    }
}
