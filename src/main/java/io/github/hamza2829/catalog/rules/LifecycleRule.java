package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.LifecycleStatus;
import io.github.hamza2829.catalog.model.RejectionCode;
import java.util.Set;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** Checks changes to an existing product against the configured transition graph. */
@Component
@Order(50)
public class LifecycleRule implements ProductRule {

    private final RuleConfiguration config;

    public LifecycleRule(RuleConfiguration config) {
        this.config = config;
    }

    @Override
    public String id() {
        return "lifecycle";
    }

    @Override
    public void apply(RuleContext context) {
        if (context.previous() == null) {
            return;
        }
        LifecycleStatus before = context.previous().status();
        LifecycleStatus after = context.candidate().status();
        if (before != null && before != after
                && !config.lifecycleTransitions().getOrDefault(before, Set.of()).contains(after)) {
            throw new RuleFailure(RejectionCode.ILLEGAL_LIFECYCLE_TRANSITION,
                    "Lifecycle cannot move from " + before + " to " + after);
        }
    }
}
