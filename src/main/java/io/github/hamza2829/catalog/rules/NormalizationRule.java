package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.LifecycleStatus;
import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.model.RejectionCode;
import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Resolves source aliases, canonicalises measurements and applies sparse updates. */
@Component
@Order(10)
public class NormalizationRule implements ProductRule {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^a-z0-9]");
    private static final ProductData EMPTY = new ProductData(
            null, null, null, null, null, null, null, null, null, null, null, null, null, List.of());

    private final RuleConfiguration config;
    private final Map<String, RuleField> aliases = new HashMap<>();

    public NormalizationRule(RuleConfiguration config) {
        this.config = config;
        config.columnAliases().forEach((field, names) -> names.forEach(name -> {
            RuleField existing = aliases.putIfAbsent(key(name), field);
            if (existing != null && existing != field) {
                throw new IllegalArgumentException("Conflicting column alias: " + name);
            }
        }));
    }

    @Override
    public String id() {
        return "normalization";
    }

    @Override
    public void apply(RuleContext context) {
        if (context.source().issue() != null) {
            throw new RuleFailure(RejectionCode.INVALID_RECORD, context.source().issue());
        }
        Map<RuleField, String> values = new EnumMap<>(RuleField.class);
        context.source().fields().forEach((column, rawValue) -> put(values, column, rawValue));
        String article = upper(values.get(RuleField.ARTICLE_NUMBER));
        context.identify(article);

        ProductData previous = Objects.requireNonNullElse(context.previous(), EMPTY);
        String name = values.getOrDefault(RuleField.NAME, previous.name());
        String category = values.getOrDefault(RuleField.CATEGORY,
                Objects.equals(name, previous.name()) ? previous.category() : null);
        context.candidate(new ProductData(
                article,
                name,
                measurement(values, RuleField.VOLTAGE, previous.voltageV()),
                measurement(values, RuleField.CURRENT, previous.currentA()),
                measurement(values, RuleField.WIDTH, previous.widthMm()),
                measurement(values, RuleField.HEIGHT, previous.heightMm()),
                measurement(values, RuleField.DEPTH, previous.depthMm()),
                upper(values.getOrDefault(RuleField.PROTECTION_CLASS, previous.protectionClass())),
                lower(values.getOrDefault(RuleField.MATERIAL, previous.material())),
                unit(values.get(RuleField.UNIT), previous.unit()),
                category,
                status(values.get(RuleField.STATUS), previous.status()),
                measurement(values, RuleField.PRICE, previous.priceEur()),
                List.of()));
    }

    private void put(Map<RuleField, String> values, String column, String rawValue) {
        RuleField field = aliases.get(key(column));
        String value = rawValue.strip();
        if (field == null || value.isEmpty()) {
            return;
        }
        String existing = values.putIfAbsent(field, value);
        if (existing != null && !existing.equals(value)) {
            throw new RuleFailure(RejectionCode.AMBIGUOUS_FIELD, "Conflicting values for " + field);
        }
    }

    private BigDecimal measurement(Map<RuleField, String> values, RuleField field, BigDecimal fallback) {
        String value = values.get(field);
        if (value == null) {
            return fallback;
        }
        return MeasurementParser.parse(field, value, config.measurementUnits().getOrDefault(field, Map.of()));
    }

    private LifecycleStatus status(String value, LifecycleStatus fallback) {
        if (value == null) {
            return fallback;
        }
        LifecycleStatus status = config.statusAliases().get(key(value));
        if (status == null) {
            throw new RuleFailure(RejectionCode.INVALID_STATUS, "Unrecognised lifecycle status");
        }
        return status;
    }

    private String unit(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String unit = config.unitAliases().get(key(value));
        if (unit == null) {
            throw new RuleFailure(RejectionCode.INVALID_UNIT, "Unrecognised selling unit");
        }
        return unit;
    }

    static String key(String value) {
        return NON_ALPHANUMERIC.matcher(value.toLowerCase(Locale.ROOT)).replaceAll("");
    }

    private static String upper(String value) {
        return value == null ? null : value.toUpperCase(Locale.ROOT);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }
}
