package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.model.RejectionCode;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Derives canonical categories and stable searchable keywords. */
@Component
@Order(20)
public class EnrichmentRule implements ProductRule {

    private static final Pattern SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    private final RuleConfiguration config;

    public EnrichmentRule(RuleConfiguration config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "enrichment";
    }

    @Override
    public void apply(RuleContext context) {
        ProductData data = context.candidate();
        String category = category(data.category(), data.name());
        TreeSet<String> keywords = new TreeSet<>();
        addWords(keywords, data.name());
        addWords(keywords, category);
        addWords(keywords, data.material());
        addWords(keywords, data.protectionClass());
        context.candidate(new ProductData(data.articleNumber(), data.name(), data.voltageV(), data.currentA(),
                data.widthMm(), data.heightMm(), data.depthMm(), data.protectionClass(), data.material(),
                data.unit(), category, data.status(), data.priceEur(), List.copyOf(keywords)));
    }

    private String category(String input, String name) {
        if (input != null) {
            String canonical = config.categoryTerms().containsKey(input)
                    ? input : config.categoryAliases().get(NormalizationRule.key(input));
            if (canonical == null || !config.categoryTerms().containsKey(canonical)) {
                throw new RuleFailure(RejectionCode.UNKNOWN_CATEGORY, "Unrecognised product category");
            }
            return canonical;
        }
        if (name == null) {
            return null;
        }
        String lowerName = name.toLowerCase(Locale.ROOT);
        return config.categoryTerms().entrySet().stream()
                .filter(entry -> entry.getValue().stream()
                        .anyMatch(term -> lowerName.contains(term.toLowerCase(Locale.ROOT))))
                .map(java.util.Map.Entry::getKey)
                .sorted()
                .findFirst()
                .orElse(null);
    }

    private static void addWords(TreeSet<String> words, String text) {
        if (text == null) {
            return;
        }
        for (String word : SEPARATORS.split(text.toLowerCase(Locale.ROOT))) {
            if (word.length() >= 2) {
                words.add(word);
            }
        }
    }
}
