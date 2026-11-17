package io.github.hamza2829.catalog.rules;

import io.github.hamza2829.catalog.model.RejectionCode;
import java.math.BigDecimal;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MeasurementParser {

    private static final Pattern MEASUREMENT = Pattern.compile("([+-]?[0-9][0-9., ]*)\\s*([a-zA-Z€]+)?");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d+(?:[.,]\\d+)?");
    private static final Pattern EUROPEAN = Pattern.compile("[+-]?\\d{1,3}(?:\\.\\d{3})+,\\d+");
    private static final Pattern ENGLISH = Pattern.compile("[+-]?\\d{1,3}(?:,\\d{3})+\\.\\d+");
    private static final Pattern SPACES = Pattern.compile("[+-]?\\d{1,3}(?: \\d{3})+(?:[.,]\\d+)?");

    private MeasurementParser() {
    }

    static BigDecimal parse(RuleField field, String input, Map<String, BigDecimal> units) {
        Matcher matcher = MEASUREMENT.matcher(input.replace('\u00A0', ' ').replace('\u202F', ' '));
        if (input.length() > 64 || !matcher.matches()) {
            throw invalid(field);
        }
        String suffix = matcher.group(2);
        BigDecimal factor = suffix == null ? BigDecimal.ONE : units.get(suffix.toLowerCase(Locale.ROOT));
        if (factor == null) {
            throw new RuleFailure(RejectionCode.INVALID_UNIT, "Unsupported measurement unit for " + field);
        }
        String number = matcher.group(1).strip();
        BigDecimal value = new BigDecimal(decimal(field, number)).multiply(factor);
        if (value.stripTrailingZeros().scale() > 4) {
            throw new RuleFailure(RejectionCode.INVALID_NUMBER, "Too many decimal places for " + field);
        }
        return value;
    }

    private static String decimal(RuleField field, String number) {
        if (DECIMAL.matcher(number).matches()) {
            return number.replace(',', '.');
        }
        if (EUROPEAN.matcher(number).matches()) {
            return number.replace(".", "").replace(',', '.');
        }
        if (ENGLISH.matcher(number).matches()) {
            return number.replace(",", "");
        }
        if (SPACES.matcher(number).matches()) {
            return number.replace(" ", "").replace(',', '.');
        }
        throw invalid(field);
    }

    private static RuleFailure invalid(RuleField field) {
        return new RuleFailure(RejectionCode.INVALID_NUMBER, "Invalid numeric value for " + field);
    }
}
