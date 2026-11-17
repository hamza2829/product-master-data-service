package io.github.hamza2829.catalog.imports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hamza2829.catalog.model.CatalogException;
import io.github.hamza2829.catalog.model.SourceRecord;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class SourceReaderTest {
    private final SourceReader reader = new SourceReader(new ObjectMapper(), new ImportSettings(2, 5));

    @Test
    void readsCsvQuotedFieldsAndReportsAColumnMismatch() {
        var rows = read("items.csv", """
                sku,description,price
                IND-000001,"Connector, silver","1,20"
                IND-000002,cable
                """);
        assertThat(rows).hasSize(2);
        assertThat(rows.getFirst().fields()).containsEntry("description", "Connector, silver")
                .containsEntry("price", "1,20");
        assertThat(rows.getLast().issue()).contains("Column count");
    }

    @Test
    void readsJsonScalarFieldsAndRetainsInvalidRows() {
        var rows = read("items.json", """
                {"products":[{"sku":"IND-000001","voltage":24},null,{"name":["not scalar"]}]}
                """);
        assertThat(rows).hasSize(3);
        assertThat(rows.getFirst().fields()).containsEntry("voltage", "24");
        assertThat(rows.subList(1, 3)).allSatisfy(row -> assertThat(row.issue()).isNotBlank());
    }

    @Test
    void readsXmlFieldsWithEscapedTextAndRejectsDuplicateFields() {
        var rows = read("items.xml", """
                <products><product><label>Relay &amp; case</label></product>
                <product><label>First</label><label>Second</label></product></products>
                """);
        assertThat(rows.getFirst().fields()).containsEntry("label", "Relay & case");
        assertThat(rows.getLast().issue()).contains("unique");
    }

    @Test
    void distinguishesMalformedFilesUnsupportedFormatsAndEmptyFiles() {
        assertCode("items.json", "{\"products\": [", "MALFORMED_FILE");
        assertCode("items.json", "[] []", "MALFORMED_FILE");
        assertCode("items.xml", "<products>", "MALFORMED_FILE");
        assertCode("items.csv", "sku,unit\n", "EMPTY_FILE");
        assertCode("items.zip", "products", "UNSUPPORTED_FORMAT");
        assertCode("items.json", "[{}, {}, {}, {}, {}, {}]", "TOO_MANY_RECORDS");
    }

    private List<SourceRecord> read(String name, String text) {
        return reader.read(name, text.getBytes(StandardCharsets.UTF_8));
    }

    private void assertCode(String name, String content, String code) {
        assertThatThrownBy(() -> read(name, content)).isInstanceOfSatisfying(
                CatalogException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
}
