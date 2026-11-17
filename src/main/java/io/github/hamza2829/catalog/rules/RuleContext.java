package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.model.SourceRecord;
import java.util.function.Function;
import java.util.function.Predicate;

/** Per-row state shared by rules, including access to the previous committed product. */
public final class RuleContext {
    private final SourceRecord source;
    private final Function<String, ProductData> findProduct;
    private final Predicate<String> alreadyAccepted;
    private String articleNumber;
    private ProductData previous;
    private ProductData candidate;

    public RuleContext(SourceRecord source, Function<String, ProductData> findProduct,
                       Predicate<String> alreadyAccepted) {
        this.source = source;
        this.findProduct = findProduct;
        this.alreadyAccepted = alreadyAccepted;
    }

    public SourceRecord source() {
        return source;
    }

    public void identify(String article) {
        articleNumber = article;
        previous = article == null ? null : findProduct.apply(article);
    }

    public String articleNumber() {
        return articleNumber;
    }

    public ProductData previous() {
        return previous;
    }

    public ProductData candidate() {
        return candidate;
    }

    public void candidate(ProductData product) {
        candidate = product;
    }

    public boolean alreadyAccepted() {
        return alreadyAccepted.test(articleNumber);
    }
}
