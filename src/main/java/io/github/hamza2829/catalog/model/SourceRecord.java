package io.github.hamza2829.catalog.model;

import java.util.Map;

/** Fields from one source row, with an optional row-shape error found by the reader. */
public record SourceRecord(Map<String, String> fields, String issue) {
    public SourceRecord {
        fields = Map.copyOf(fields);
    }

    public static SourceRecord fields(Map<String, String> fields) {
        return new SourceRecord(fields, null);
    }

    public static SourceRecord invalid(String issue) {
        return new SourceRecord(Map.of(), issue);
    }
}
