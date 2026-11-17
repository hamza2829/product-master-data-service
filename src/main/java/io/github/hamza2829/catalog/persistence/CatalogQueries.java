package io.github.hamza2829.catalog.persistence;

import io.github.hamza2829.catalog.model.CatalogException;
import io.github.hamza2829.catalog.model.CatalogViews.ImportReport;
import io.github.hamza2829.catalog.model.CatalogViews.ProductDetails;
import io.github.hamza2829.catalog.model.CatalogViews.Rejection;
import io.github.hamza2829.catalog.model.CatalogViews.Results;
import io.github.hamza2829.catalog.model.LifecycleStatus;
import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.model.RejectionCode;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Reads the searchable catalog, individual histories and import outcomes. */
@Service
@Transactional(readOnly = true)
public class CatalogQueries {
    private final Imports imports;
    private final Products products;
    private final ProductChanges changes;
    private final DeadLetters deadLetters;

    CatalogQueries(Imports imports, Products products, ProductChanges changes, DeadLetters deadLetters) {
        this.imports = imports;
        this.products = products;
        this.changes = changes;
        this.deadLetters = deadLetters;
    }

    public Results<ProductData> search(String query, String category, LifecycleStatus status, int page, int size) {
        Pageable pagination = PageRequest.of(page, size, Sort.by("articleNumber"));
        return Results.from(products.search(lower(query), lower(category), status == null ? "" : status.name(),
                pagination).map(product -> product.productData));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ProductDetails product(String article, int page, int size) {
        StoredProduct product = products.findById(article.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> new CatalogException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND",
                        "No product has this article number."));
        Pageable pagination = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "revision"));
        return new ProductDetails(product.productData, product.updatedAt,
                Results.from(changes.findByArticleNumber(product.articleNumber, pagination).map(StoredChange::view)));
    }

    public ImportReport importJob(UUID id) {
        return imports.findById(id).map(job -> job.report(false)).orElseThrow(() ->
                new CatalogException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "The import was not found."));
    }

    public Results<Rejection> rejections(RejectionCode reason, boolean includeResolved, int page, int size) {
        Pageable pagination = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "rejectedAt", "id"));
        Page<DeadLetter> letters;
        if (includeResolved) {
            letters = reason == null ? deadLetters.findAll(pagination) : deadLetters.findByReason(reason, pagination);
        } else {
            letters = reason == null ? deadLetters.findByResolvedAtIsNull(pagination)
                    : deadLetters.findByReasonAndResolvedAtIsNull(reason, pagination);
        }
        return Results.from(letters.map(DeadLetter::view));
    }

    private static String lower(String text) {
        return text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
    }
}
