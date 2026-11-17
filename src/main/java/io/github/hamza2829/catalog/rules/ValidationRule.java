package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.model.RejectionCode;
import java.math.BigDecimal;
import java.util.Comparator;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Checks required fields, identifiers, protection class and configured numeric ranges. */
@Component
@Order(30)
public class ValidationRule implements ProductRule {
    private final RuleConfiguration config;
    private final Pattern articlePattern;
    private final Pattern protectionClassPattern;

    public ValidationRule(RuleConfiguration config) {
        this.config = config;
        articlePattern = Pattern.compile(config.articlePattern());
        protectionClassPattern = Pattern.compile(config.protectionClassPattern());
        config.ranges().keySet().forEach(field -> {
            switch (field) {
                case VOLTAGE, CURRENT, WIDTH, HEIGHT, DEPTH, PRICE -> { }
                default -> throw new IllegalArgumentException("Range requires a numeric field: " + field);
            }
        });
    }

    @Override
    public String id() {
        return "validation";
    }

    @Override
    public void apply(RuleContext context) {
        ProductData data = context.candidate();
        // The identifier is needed for storage even if other required fields are configurable.
        require(data, RuleField.ARTICLE_NUMBER);
        config.requiredFields().stream().sorted().forEach(field -> require(data, field));
        if (data.articleNumber().length() > 64 || !articlePattern.matcher(data.articleNumber()).matches()) {
            throw new RuleFailure(RejectionCode.INVALID_ARTICLE, "Article number does not match the configured format");
        }
        if (data.protectionClass() != null && !protectionClassPattern.matcher(data.protectionClass()).matches()) {
            throw new RuleFailure(RejectionCode.INVALID_PROTECTION_CLASS, "Invalid protection class");
        }
        config.ranges().entrySet().stream()
                .sorted(Comparator.comparing(java.util.Map.Entry::getKey))
                .forEach(entry -> {
                    BigDecimal number = (BigDecimal) value(data, entry.getKey());
                    if (number != null && (number.compareTo(entry.getValue().min()) < 0
                            || number.compareTo(entry.getValue().max()) > 0)) {
                        throw new RuleFailure(RejectionCode.OUT_OF_RANGE, "Value outside allowed range for "
                                + entry.getKey());
                    }
                });
    }

    private static void require(ProductData data, RuleField field) {
        if (value(data, field) == null) {
            throw new RuleFailure(RejectionCode.MISSING_REQUIRED_FIELD, "Missing required field " + field);
        }
    }

    private static Object value(ProductData data, RuleField field) {
        return switch (field) {
            case ARTICLE_NUMBER -> data.articleNumber();
            case NAME -> data.name();
            case VOLTAGE -> data.voltageV();
            case CURRENT -> data.currentA();
            case WIDTH -> data.widthMm();
            case HEIGHT -> data.heightMm();
            case DEPTH -> data.depthMm();
            case PROTECTION_CLASS -> data.protectionClass();
            case MATERIAL -> data.material();
            case UNIT -> data.unit();
            case CATEGORY -> data.category();
            case STATUS -> data.status();
            case PRICE -> data.priceEur();
        };
    }
}
