package io.github.hamza2829.catalog.imports;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Limits on decoded files and on rows committed per transaction. */
@Validated
@ConfigurationProperties("catalog.imports")
public record ImportSettings(@Min(1) @Max(1000) int batchSize, @Min(1) @Max(50000) int maxRecords) {
}
