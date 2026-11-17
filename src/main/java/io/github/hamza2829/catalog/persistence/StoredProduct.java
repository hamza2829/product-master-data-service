package io.github.hamza2829.catalog.persistence;

import io.github.hamza2829.catalog.model.ProductData;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "products")
class StoredProduct {

    @Id
    @Column(length = 64)
    String articleNumber;

    @Column(columnDefinition = "text")
    String category;

    @Column(columnDefinition = "text")
    String lifecycleStatus;

    @Column(nullable = false, columnDefinition = "text")
    String searchText;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    ProductData productData;

    @Column(nullable = false)
    long revision;

    @Column(nullable = false)
    Instant updatedAt;

    protected StoredProduct() {
    }

    StoredProduct(String articleNumber) {
        this.articleNumber = articleNumber;
    }

    void update(ProductData data, long nextRevision, Instant now) {
        productData = data;
        category = data.category();
        lifecycleStatus = data.status() == null ? null : data.status().name();
        searchText = (articleNumber + " " + text(data.name()) + " " + text(data.material()) + " "
                + String.join(" ", data.keywords())).toLowerCase(Locale.ROOT);
        revision = nextRevision;
        updatedAt = now;
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }
}
