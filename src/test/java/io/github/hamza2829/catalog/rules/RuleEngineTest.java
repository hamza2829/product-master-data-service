package io.github.hamza2829.catalog.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.hamza2829.catalog.model.LifecycleStatus;
import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.model.RejectionCode;
import io.github.hamza2829.catalog.model.SourceRecord;
import io.github.hamza2829.catalog.telemetry.CatalogTelemetry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class RuleEngineTest {

    private static RuleConfiguration config;

    @BeforeAll
    static void loadConfiguration() throws IOException {
        config = configuration(Map.of());
    }

    @Test
    void normalizationHandlesAliasesUnitsLocalizedNumbersAndWhitespace() {
        RuleContext context = context(base(), null, false);
        new NormalizationRule(config).apply(context);

        ProductData result = context.candidate();
        assertThat(result.articleNumber()).isEqualTo("IND-000001");
        assertThat(result.name()).isEqualTo("Lumen Power Supply");
        assertThat(result.voltageV()).isEqualByComparingTo("400");
        assertThat(result.currentA()).isEqualByComparingTo("1.25");
        assertThat(result.widthMm()).isEqualByComparingTo("25");
        assertThat(result.heightMm()).isEqualByComparingTo("30");
        assertThat(result.depthMm()).isEqualByComparingTo("42");
        assertThat(result.priceEur()).isEqualByComparingTo("1234.50");
        assertThat(result.unit()).isEqualTo("EA");
        assertThat(result.status()).isEqualTo(LifecycleStatus.ACTIVE);
    }

    @Test
    void normalizationSeparatesInvalidNumbersAndUnsupportedUnits() {
        NormalizationRule rule = new NormalizationRule(config);
        assertCode(() -> rule.apply(context(row("price_eur", "1,2,3"), null, false)), RejectionCode.INVALID_NUMBER);
        assertCode(() -> rule.apply(context(row("width_mm", "20 in"), null, false)), RejectionCode.INVALID_UNIT);
        assertCode(() -> rule.apply(context(row("lifecycle", "new"), null, false)), RejectionCode.INVALID_STATUS);
    }

    @Test
    void normalizationRejectsConflictingAliasesAndUnreadableRows() {
        NormalizationRule rule = new NormalizationRule(config);
        assertCode(() -> rule.apply(context(row("articleNumber", "IND-000002"), null, false)),
                RejectionCode.AMBIGUOUS_FIELD);
        RuleContext unreadable = new RuleContext(SourceRecord.invalid("Expected an object"), ignored -> null,
                ignored -> false);
        assertCode(() -> rule.apply(unreadable), RejectionCode.INVALID_RECORD);
    }

    @Test
    void enrichmentUsesNameOrMappedCategoryAndBuildsKeywords() {
        EnrichmentRule rule = new EnrichmentRule(config);
        RuleContext derived = normalized(base());
        rule.apply(derived);
        assertThat(derived.candidate().category()).isEqualTo("power-supplies");
        assertThat(derived.candidate().keywords()).contains("lumen", "supply", "steel", "ip54")
                .isSorted().doesNotHaveDuplicates();

        RuleContext mapped = normalized(row("family", "PSU"));
        rule.apply(mapped);
        assertThat(mapped.candidate().category()).isEqualTo("power-supplies");
        assertCode(() -> rule.apply(normalized(row("family", "unmapped"))), RejectionCode.UNKNOWN_CATEGORY);
    }

    @Test
    void validationChecksRequiredFieldsPatternsAndInclusiveRanges() {
        RuleEngine engine = engine(config, new SimpleMeterRegistry());
        assertThat(engine.evaluate(context(row("rated_voltage", "1.5 kV"), null, false)).voltageV())
                .isEqualByComparingTo("1500");
        assertCode(() -> engine.evaluate(context(row("description", " "), null, false)),
                RejectionCode.MISSING_REQUIRED_FIELD);
        assertCode(() -> engine.evaluate(context(row("sku", "wrong"), null, false)), RejectionCode.INVALID_ARTICLE);
        assertCode(() -> engine.evaluate(context(row("ip_rating", "IP99"), null, false)),
                RejectionCode.INVALID_PROTECTION_CLASS);
        assertCode(() -> engine.evaluate(context(row("rated_voltage", "1.501 kV"), null, false)),
                RejectionCode.OUT_OF_RANGE);
    }

    @Test
    void validationReadsChangedThresholdFromYamlConfiguration() throws IOException {
        RuleConfiguration stricter = configuration(Map.of("catalog.rules.ranges.VOLTAGE.max", "200"));
        assertCode(() -> engine(stricter, new SimpleMeterRegistry()).evaluate(context(base(), null, false)),
                RejectionCode.OUT_OF_RANGE);
    }

    @Test
    void deduplicationHonorsConfiguredFirstAcceptedRowPolicy() throws IOException {
        new DeduplicationRule(config).apply(context(base(), null, false));
        assertCode(() -> new DeduplicationRule(config).apply(context(base(), null, true)),
                RejectionCode.DUPLICATE_ARTICLE);
        RuleConfiguration allowDuplicates = configuration(Map.of("catalog.rules.reject-duplicates", false));
        new DeduplicationRule(allowDuplicates).apply(context(base(), null, true));
    }

    @Test
    void lifecycleAllowsForwardAndSameStatusUpdatesAndRejectsReactivation() {
        RuleEngine engine = engine(config, new SimpleMeterRegistry());
        ProductData active = engine.evaluate(context(base(), null, false));
        ProductData phaseOut = engine.evaluate(context(Map.of("sku", active.articleNumber(), "status", "EOL"),
                active, false));
        assertThat(phaseOut.status()).isEqualTo(LifecycleStatus.PHASE_OUT);
        assertThat(phaseOut.name()).isEqualTo(active.name());
        ProductData retired = engine.evaluate(context(row("lifecycle", "retired"), phaseOut, false));
        assertThat(retired.status()).isEqualTo(LifecycleStatus.DISCONTINUED);
        assertThat(engine.evaluate(context(Map.of("sku", active.articleNumber()), retired, false))).isEqualTo(retired);
        assertCode(() -> engine.evaluate(context(base(), retired, false)), RejectionCode.ILLEGAL_LIFECYCLE_TRANSITION);
    }

    @Test
    void engineStopsAtFirstRejectionAndTimesTheFailedRule() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        RuleEngine engine = engine(config, registry);
        assertCode(() -> engine.evaluate(context(row("price_eur", "-1"), null, false)), RejectionCode.OUT_OF_RANGE);
        assertThat(registry.get("catalog.rule.execution").tag("rule", "validation").timer().count()).isEqualTo(1);
        assertThat(registry.find("catalog.rule.execution").tag("rule", "lifecycle").timer()).isNull();
    }

    private static RuleEngine engine(RuleConfiguration rules, SimpleMeterRegistry registry) {
        return new RuleEngine(List.of(new NormalizationRule(rules), new EnrichmentRule(rules),
                new ValidationRule(rules), new DeduplicationRule(rules), new LifecycleRule(rules)),
                new CatalogTelemetry(registry));
    }

    private static RuleConfiguration configuration(Map<String, Object> overrides) throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new YamlPropertySourceLoader()
                .load("base", new ClassPathResource("rules.yml")).getFirst());
        environment.getPropertySources().addFirst(new MapPropertySource("overrides", overrides));
        return Binder.get(environment).bind("catalog.rules", Bindable.of(RuleConfiguration.class)).get();
    }

    private static RuleContext normalized(Map<String, String> row) {
        RuleContext context = context(row, null, false);
        new NormalizationRule(config).apply(context);
        return context;
    }

    private static RuleContext context(Map<String, String> fields, ProductData previous, boolean duplicate) {
        return new RuleContext(SourceRecord.fields(fields), ignored -> previous, ignored -> duplicate);
    }

    private static Map<String, String> row(String field, String value) {
        Map<String, String> fields = new LinkedHashMap<>(base());
        fields.put(field, value);
        return fields;
    }

    private static Map<String, String> base() {
        return Map.ofEntries(
                Map.entry("sku", " ind-000001 "), Map.entry("description", " Lumen Power Supply  "),
                Map.entry("rated_voltage", "0,4 kV"), Map.entry("I", "1250 mA"),
                Map.entry("width_mm", "2,5 cm"), Map.entry("H", "30 mm"),
                Map.entry("D", "42"), Map.entry("ip_rating", " ip54 "), Map.entry("housing", " Steel "),
                Map.entry("uom", " Pcs "), Map.entry("lifecycle", "live"), Map.entry("price_eur", "1.234,50 EUR"));
    }

    private static void assertCode(Runnable action, RejectionCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RuleFailure.class,
                rejection -> assertThat(rejection.code()).isEqualTo(code));
    }
}
