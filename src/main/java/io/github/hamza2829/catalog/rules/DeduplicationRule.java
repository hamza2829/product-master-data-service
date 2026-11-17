package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.RejectionCode;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Applies the configured first-accepted-row policy within each file. */
@Component
@Order(40)
public class DeduplicationRule implements ProductRule {

    private final RuleConfiguration config;

    public DeduplicationRule(RuleConfiguration config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "deduplication";
    }

    @Override
    public void apply(RuleContext context) {
        if (config.rejectDuplicates() && context.alreadyAccepted()) {
            throw new RuleFailure(RejectionCode.DUPLICATE_ARTICLE, "An earlier row already accepted this article");
        }
    }
}
