package io.github.hamza2829.catalog.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hamza2829.catalog.rules.RuleField;
import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import org.apache.commons.csv.CSVFormat;

/** Generates the versioned, intentionally inconsistent inputs using a fixed seed. */
public final class SyntheticData {
    static final long SEED = 2829L;
    static final int PRODUCT_COUNT = 500;
    static final int UPDATE_BATCH_COUNT = 20;
    static final Path OUTPUT = Path.of("target", "demo-data");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<Family> FAMILIES = List.of(
            new Family("Alder Terminal Block", "TB", " polyamide ", "pcs"),
            new Family("Birch Connector", "Conn", " Tinned Copper ", "piece"),
            new Family("Cedar Power Supply", "PSU", "aluminium", "ea"),
            new Family("Elm Cable", "Cable", " PVC ", "metre"),
            new Family("Rowan Relay", "Relay", " Stainless Steel ", "pcs"));
    private static final int[] VOLTAGES = {24, 48, 120, 230, 400, 690, 1000};
    private static final String[] PROTECTION = {"ip20", "IP54", " IP65 "};

    private SyntheticData() {
    }

    public static void main(String[] args) throws IOException, XMLStreamException {
        Path output = args.length == 0 ? OUTPUT : Path.of(args[0]);
        generate(output);
        System.out.println("Generated synthetic inputs in " + output);
    }

    static void generate(Path output) throws IOException, XMLStreamException {
        Random random = new Random(SEED);
        int start = 1;
        int[] cutoffs = {170, 335, PRODUCT_COUNT};
        for (Format format : Format.values()) {
            List<Map<RuleField, String>> rows = new ArrayList<>();
            for (int number = start; number <= cutoffs[format.ordinal()]; number++) {
                rows.add(initial(number, random));
            }
            addBadRows(rows);
            String file = String.format(Locale.ROOT, "%02d-initial.%s", format.ordinal() + 1, format.extension);
            write(output.resolve("initial").resolve(file), format, rows, true);
            start = cutoffs[format.ordinal()] + 1;
        }
        for (int batch = 1; batch <= UPDATE_BATCH_COUNT; batch++) {
            Format format = Format.values()[(batch - 1) % Format.values().length];
            String file = String.format(Locale.ROOT, "%02d-update.%s", batch, format.extension);
            write(output.resolve("updates").resolve(file), format, updates(batch, random), false);
        }
    }

    private static Map<RuleField, String> initial(int number, Random random) {
        Family family = FAMILIES.get((number - 1) % FAMILIES.size());
        Map<RuleField, String> row = new EnumMap<>(RuleField.class);
        row.put(RuleField.ARTICLE_NUMBER, number % 4 == 0 ? " " + article(number).toLowerCase(Locale.ROOT) + " "
                : article(number));
        row.put(RuleField.NAME, " " + family.name() + " " + number + (number % 19 == 0 ? " & Mount" : "") + " ");
        int voltage = VOLTAGES[random.nextInt(VOLTAGES.length)];
        int current = random.nextInt(1, 80);
        row.put(RuleField.VOLTAGE, number % 3 == 0 ? decimal(voltage, 3, true) + " kV" : voltage + " V");
        row.put(RuleField.CURRENT, number % 5 == 0 ? current * 1000 + " mA" : current + " A");
        row.put(RuleField.WIDTH, length(random.nextInt(12, 120), number % 2 == 0));
        row.put(RuleField.HEIGHT, length(random.nextInt(12, 220), number % 3 == 0));
        row.put(RuleField.DEPTH, length(random.nextInt(12, 180), number % 4 == 0));
        row.put(RuleField.PROTECTION_CLASS, PROTECTION[number % PROTECTION.length]);
        row.put(RuleField.MATERIAL, family.material());
        row.put(RuleField.UNIT, family.unit());
        row.put(RuleField.STATUS, number % 2 == 0 ? " live " : "ACTIVE");
        row.put(RuleField.PRICE, decimal(random.nextInt(250, 90000), 2, number % 2 == 0) + " EUR");
        if (number % 3 == 0) {
            row.put(RuleField.CATEGORY, family.alias());
        }
        return row;
    }

    private static void addBadRows(List<Map<RuleField, String>> rows) {
        Map<RuleField, String> exemplar = rows.getFirst();
        rows.add(exemplar);
        Map<RuleField, String> missingName = new EnumMap<>(exemplar);
        missingName.remove(RuleField.NAME);
        rows.addFirst(missingName);
        rows.add(with(exemplar, RuleField.ARTICLE_NUMBER, "invalid-article"));
        rows.add(with(exemplar, RuleField.CURRENT, "unknown"));
        rows.add(with(exemplar, RuleField.VOLTAGE, "9,8 kV"));
        rows.add(with(exemplar, RuleField.STATUS, "unpublished"));
    }

    private static List<Map<RuleField, String>> updates(int batch, Random random) {
        List<Map<RuleField, String>> rows = new ArrayList<>();
        for (int slot = 0; slot < 12; slot++) {
            int number = (batch - 1) * 12 + slot + 1;
            Map<RuleField, String> update = new EnumMap<>(RuleField.class);
            update.put(RuleField.ARTICLE_NUMBER, article(number));
            update.put(RuleField.PRICE, decimal(random.nextInt(100000, 250000), 2, slot % 2 == 0) + " EUR");
            update.put(RuleField.CURRENT, random.nextInt(10, 85) + " A");
            if (slot % 2 == 0) {
                update.put(RuleField.WIDTH, length(random.nextInt(20, 150), true));
            }
            if (slot % 4 == 0) {
                update.put(RuleField.STATUS, batch == 1 && slot == 0 ? "retired" : "phase out");
            }
            rows.add(update);
        }
        if (batch == 2) {
            rows.add(Map.of(RuleField.ARTICLE_NUMBER, article(1), RuleField.STATUS, "active"));
        }
        if (batch == UPDATE_BATCH_COUNT) {
            rows.add(Map.of(RuleField.ARTICLE_NUMBER, article(PRODUCT_COUNT)));
        }
        return rows;
    }

    private static Map<RuleField, String> with(Map<RuleField, String> original, RuleField field, String value) {
        Map<RuleField, String> copy = new EnumMap<>(original);
        copy.put(field, value);
        return copy;
    }

    private static String article(int number) {
        return String.format(Locale.ROOT, "IND-%06d", number);
    }

    private static String length(int millimetres, boolean centimetres) {
        return centimetres ? decimal(millimetres, 1, true) + " cm" : millimetres + " mm";
    }

    private static String decimal(int unscaled, int scale, boolean comma) {
        String value = BigDecimal.valueOf(unscaled, scale).toPlainString();
        return comma ? value.replace('.', ',') : value;
    }

    private static void write(Path file, Format format, List<Map<RuleField, String>> rows, boolean initial)
            throws IOException, XMLStreamException {
        Files.createDirectories(file.getParent());
        List<Map<String, String>> encoded = rows.stream().map(format::encode).toList();
        try (Writer writer = Files.newBufferedWriter(file)) {
            switch (format) {
                case CSV -> writeCsv(writer, format, encoded);
                case JSON -> writeJson(writer, encoded, initial);
                case XML -> writeXml(writer, encoded);
            }
        }
    }

    private static void writeCsv(Writer writer, Format format, List<Map<String, String>> rows) throws IOException {
        var settings = CSVFormat.DEFAULT.builder().setHeader(format.columns).setRecordSeparator("\n").get();
        try (var csv = settings.print(writer)) {
            for (var row : rows) {
                List<String> values = new ArrayList<>();
                for (String column : format.columns) {
                    values.add(row.get(column));
                }
                csv.printRecord(values);
            }
        }
    }

    private static void writeJson(Writer writer, List<Map<String, String>> rows, boolean wrapped) throws IOException {
        writer.write(wrapped ? "{\"products\":[\n" : "[\n");
        for (int index = 0; index < rows.size(); index++) {
            writer.write("  " + JSON.writeValueAsString(rows.get(index)));
            writer.write(index == rows.size() - 1 ? "\n" : ",\n");
        }
        writer.write(wrapped ? "]}\n" : "]\n");
    }

    private static void writeXml(Writer writer, List<Map<String, String>> rows) throws XMLStreamException {
        var xml = XMLOutputFactory.newDefaultFactory().createXMLStreamWriter(writer);
        xml.writeStartDocument("UTF-8", "1.0");
        xml.writeCharacters("\n");
        xml.writeStartElement("products");
        for (var row : rows) {
            xml.writeCharacters("\n  ");
            xml.writeStartElement("product");
            for (var field : row.entrySet()) {
                xml.writeStartElement(field.getKey());
                xml.writeCharacters(field.getValue());
                xml.writeEndElement();
            }
            xml.writeEndElement();
        }
        xml.writeCharacters("\n");
        xml.writeEndElement();
        xml.writeCharacters("\n");
        xml.writeEndDocument();
        xml.close();
    }

    private record Family(String name, String alias, String material, String unit) {
    }

    private enum Format {
        CSV("csv", "article_no", "product_name", "voltage", "current", "width_mm", "height_mm", "depth_mm",
                "ip_rating", "housing", "uom", "family", "lifecycle", "price_eur"),
        JSON("json", "sku", "description", "rated_voltage", "rated_current", "width", "height", "depth",
                "protectionClass", "material", "unit", "category", "status", "unitPrice"),
        XML("xml", "partCode", "label", "U", "I", "W", "H", "D", "ingress", "bodyMaterial",
                "measure", "productGroup", "lifeCycleState", "price");

        private final String extension;
        private final String[] columns;

        Format(String extension, String... columns) {
            this.extension = extension;
            this.columns = columns;
        }

        Map<String, String> encode(Map<RuleField, String> row) {
            Map<String, String> encoded = new LinkedHashMap<>();
            for (RuleField field : RuleField.values()) {
                if (row.containsKey(field)) {
                    encoded.put(columns[field.ordinal()], row.get(field));
                }
            }
            return encoded;
        }
    }
}
