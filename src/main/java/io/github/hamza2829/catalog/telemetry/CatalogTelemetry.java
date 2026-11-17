package io.github.hamza2829.catalog.telemetry;

import io.github.hamza2829.catalog.model.RejectionCode;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** Low-cardinality metrics shared by import, retry and rule evaluation. */
@Component
public class CatalogTelemetry {

    private final MeterRegistry registry;

    public CatalogTelemetry(MeterRegistry registry) {
        this.registry = registry;
    }

    public Timer rule(String rule) {
        return registry.timer("catalog.rule.execution", "rule", rule);
    }

    public void row(String operation, String outcome, RejectionCode rejection) {
        registry.counter("catalog.records.processed", "operation", operation, "outcome", outcome).increment();
        if (rejection != null) {
            registry.counter("catalog.records.rejected", "operation", operation, "reason", rejection.name()).increment();
        }
    }

    public void importDuration(String outcome, Duration duration) {
        registry.timer("catalog.import.duration", "outcome", outcome).record(duration);
    }
}
