package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.telemetry.CatalogTelemetry;
import java.util.List;
import org.springframework.stereotype.Component;

/** Executes and times the ordered rules for one row. */
@Component
public class RuleEngine {

    private final List<ProductRule> rules;
    private final CatalogTelemetry telemetry;

    public RuleEngine(List<ProductRule> rules, CatalogTelemetry telemetry) {
        this.rules = List.copyOf(rules);
        this.telemetry = telemetry;
    }

    public ProductData evaluate(RuleContext context) {
        for (ProductRule rule : rules) {
            telemetry.rule(rule.id()).record(() -> rule.apply(context));
        }
        return context.candidate();
    }
}
