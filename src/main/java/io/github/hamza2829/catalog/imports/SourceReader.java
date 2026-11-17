package io.github.hamza2829.catalog.imports;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hamza2829.catalog.model.CatalogException;
import io.github.hamza2829.catalog.model.SourceRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.commons.csv.DuplicateHeaderMode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Decodes supported files into flat rows and keeps recoverable shape errors with their rows. */
@Component
public class SourceReader {
    private static final CSVFormat CSV = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setDuplicateHeaderMode(DuplicateHeaderMode.DISALLOW)
            .get();

    private final ObjectMapper json;
    private final ImportSettings settings;

    public SourceReader(ObjectMapper json, ImportSettings settings) {
        this.json = json;
        this.settings = settings;
    }

    public List<SourceRecord> read(String fileName, byte[] bytes) {
        List<SourceRecord> rows = new ArrayList<>();
        String extension = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try {
            switch (extension) {
                case "csv" -> readCsv(bytes, rows);
                case "json" -> readJson(bytes, rows);
                case "xml" -> readXml(bytes, rows);
                default -> throw new CatalogException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FORMAT",
                        "Upload a file with a .csv, .json or .xml extension.");
            }
        } catch (IOException | UncheckedIOException | SAXException | IllegalArgumentException error) {
            throw new CatalogException(HttpStatus.BAD_REQUEST, "MALFORMED_FILE",
                    "The file could not be decoded as " + extension.toUpperCase(Locale.ROOT) + ".");
        }
        if (rows.isEmpty()) {
            throw new CatalogException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "The file contains no product rows.");
        }
        return rows;
    }

    private void readCsv(byte[] bytes, List<SourceRecord> rows) throws IOException {
        try (InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(bytes),
                StandardCharsets.UTF_8.newDecoder());
             CSVParser csv = CSV.parse(reader)) {
            for (CSVRecord row : csv) {
                SourceRecord record = row.isConsistent() ? fields(row.toMap())
                        : SourceRecord.invalid("Column count differs from the CSV header");
                add(rows, record);
            }
        }
    }

    private void readJson(byte[] bytes, List<SourceRecord> rows) throws IOException {
        JsonNode root = json.readerFor(JsonNode.class)
                .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .readValue(bytes);
        JsonNode products = root.isObject() ? root.path("products") : root;
        if (!products.isArray()) {
            throw new IOException("Expected an array of products");
        }
        for (JsonNode item : products) {
            add(rows, jsonRow(item));
        }
    }

    private static SourceRecord jsonRow(JsonNode item) {
        if (!item.isObject()) {
            return SourceRecord.invalid("A JSON product row must be an object");
        }
        Map<String, String> fields = new HashMap<>();
        var entries = item.fields();
        while (entries.hasNext()) {
            var field = entries.next();
            JsonNode value = field.getValue();
            if (value.isContainerNode()) {
                return SourceRecord.invalid("JSON product fields must be scalar");
            }
            if (!value.isNull()) {
                fields.put(field.getKey(), value.asText());
            }
        }
        return fields(fields);
    }

    private void readXml(byte[] bytes, List<SourceRecord> rows) throws SAXException, IOException {
        DocumentBuilder builder = xmlBuilder();
        Element root = builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        if (!root.getTagName().equals("products")) {
            throw new SAXException("Expected a products root element");
        }
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                add(rows, xmlRow(element));
            } else if (hasText(node)) {
                throw new SAXException("Unexpected text outside a product");
            }
        }
    }

    private static DocumentBuilder xmlBuilder() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            return builder;
        } catch (ParserConfigurationException error) {
            throw new IllegalStateException("Could not initialise the XML reader", error);
        }
    }

    private static SourceRecord xmlRow(Element product) {
        if (!product.getTagName().equals("product")) {
            return SourceRecord.invalid("A product row must use a product element");
        }
        Map<String, String> fields = new HashMap<>();
        for (Node node = product.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element field) {
                if (field.getElementsByTagName("*").getLength() != 0
                        || fields.putIfAbsent(field.getTagName(), field.getTextContent()) != null) {
                    return SourceRecord.invalid("XML row fields must be unique and contain only text");
                }
            } else if (hasText(node)) {
                return SourceRecord.invalid("Put XML row values inside field elements");
            }
        }
        return fields(fields);
    }

    private static boolean hasText(Node node) {
        return (node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                && !node.getTextContent().isBlank();
    }

    private static SourceRecord fields(Map<String, String> values) {
        for (var field : values.entrySet()) {
            if (field.getKey().indexOf('\0') >= 0 || field.getValue().indexOf('\0') >= 0) {
                return SourceRecord.invalid("Row contains a null character");
            }
        }
        return SourceRecord.fields(values);
    }

    private void add(List<SourceRecord> rows, SourceRecord row) {
        if (rows.size() >= settings.maxRecords()) {
            throw new CatalogException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_MANY_RECORDS",
                    "This file exceeds the configured row limit.");
        }
        rows.add(row);
    }
}
