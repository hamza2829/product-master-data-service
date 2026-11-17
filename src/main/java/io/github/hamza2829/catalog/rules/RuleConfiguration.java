package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.LifecycleStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Thresholds, mappings and lifecycle policy loaded from Spring Boot configuration. */
@Validated
@ConfigurationProperties(prefix = "catalog.rules")
public record RuleConfiguration(
        @NotBlank String articlePattern,
        @NotBlank String protectionClassPattern,
        @NotEmpty Set<RuleField> requiredFields,
        @NotEmpty Map<RuleField, List<String>> columnAliases,
        @NotEmpty Map<RuleField, Map<String, BigDecimal>> measurementUnits,
        @NotEmpty Map<RuleField, @Valid Bounds> ranges,
        @NotEmpty Map<String, String> unitAliases,
        @NotEmpty Map<String, LifecycleStatus> statusAliases,
        @NotEmpty Map<String, String> categoryAliases,
        @NotEmpty Map<String, List<String>> categoryTerms,
        @NotNull Map<LifecycleStatus, Set<LifecycleStatus>> lifecycleTransitions,
        boolean rejectDuplicates) {

    /** Inclusive limits in the canonical unit. */
    public record Bounds(@NotNull BigDecimal min, @NotNull BigDecimal max) {
        public Bounds {
            if (min != null && max != null && min.compareTo(max) > 0) {
                throw new IllegalArgumentException("A lower bound cannot exceed its upper bound");
            }
        }
    }
}
