package io.github.hamza2829.catalog.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/** Canonical product snapshot. Measurements use volts, amps, millimetres and euros. */
public record ProductData(
        String articleNumber,
        String name,
        BigDecimal voltageV,
        BigDecimal currentA,
        BigDecimal widthMm,
        BigDecimal heightMm,
        BigDecimal depthMm,
        String protectionClass,
        String material,
        String unit,
        String category,
        LifecycleStatus status,
        BigDecimal priceEur,
        List<String> keywords) {

    public ProductData {
        voltageV = decimal(voltageV);
        currentA = decimal(currentA);
        widthMm = decimal(widthMm);
        heightMm = decimal(heightMm);
        depthMm = decimal(depthMm);
        priceEur = decimal(priceEur);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
    }

    private static BigDecimal decimal(BigDecimal value) {
        return value == null ? null : value.setScale(4, RoundingMode.UNNECESSARY);
    }
}
