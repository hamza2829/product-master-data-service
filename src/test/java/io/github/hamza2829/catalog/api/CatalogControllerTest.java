package io.github.hamza2829.catalog.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.hamza2829.catalog.imports.ImportService;
import io.github.hamza2829.catalog.model.CatalogException;
import io.github.hamza2829.catalog.model.CatalogViews.ImportReport;
import io.github.hamza2829.catalog.model.CatalogViews.ImportStatus;
import io.github.hamza2829.catalog.model.CatalogViews.Rejection;
import io.github.hamza2829.catalog.model.CatalogViews.Results;
import io.github.hamza2829.catalog.model.CatalogViews.RetryReport;
import io.github.hamza2829.catalog.model.LifecycleStatus;
import io.github.hamza2829.catalog.model.RejectionCode;
import io.github.hamza2829.catalog.persistence.CatalogQueries;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(CatalogController.class)
class CatalogControllerTest {
    private static final UUID ID = UUID.fromString("4c26a997-6155-4ed9-8755-c9236292cde8");
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ImportService imports;

    @MockitoBean
    private CatalogQueries catalog;

    @Test
    void uploadReturnsCreatedThenExistingReport() throws Exception {
        when(imports.importFile(eq("batch.json"), any())).thenReturn(report(false), report(true));

        mvc.perform(multipart("/api/v1/imports").file(file()))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/imports/" + ID))
                .andExpect(jsonPath("$.changed").value(1));
        mvc.perform(multipart("/api/v1/imports").file(file()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void passesProductFiltersAndReturnsStablePageShape() throws Exception {
        when(catalog.search("lumen", "relays", LifecycleStatus.ACTIVE, 1, 5))
                .thenReturn(new Results<>(List.of(), 1, 5, 0, 0));

        mvc.perform(get("/api/v1/products").param("q", "lumen").param("category", "relays")
                        .param("status", "ACTIVE").param("page", "1").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.totalElements").value(0));
        verify(catalog).search("lumen", "relays", LifecycleStatus.ACTIVE, 1, 5);
    }

    @Test
    void returnsImportReportById() throws Exception {
        when(catalog.importJob(ID)).thenReturn(report(false));
        mvc.perform(get("/api/v1/imports/{id}", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETE"));
    }

    @Test
    void validatesPaginationStatusUuidAndMissingMultipartPart() throws Exception {
        mvc.perform(get("/api/v1/products").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/products").param("status", "INVALID"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(get("/api/v1/imports/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
        mvc.perform(multipart("/api/v1/imports"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void preservesStableDomainErrors() throws Exception {
        when(catalog.product("IND-000001", 0, 20)).thenThrow(
                new CatalogException(HttpStatus.NOT_FOUND, "PRODUCT_NOT_FOUND", "No product has this article."));
        mvc.perform(get("/api/v1/products/IND-000001"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("PRODUCT_NOT_FOUND"));
    }

    @Test
    void exposesRejectionReasonAndRetryResult() throws Exception {
        Rejection rejection = new Rejection(ID, ID, 2, ID + ":2", "IND-000001", RejectionCode.OUT_OF_RANGE,
                RejectionCode.OUT_OF_RANGE, "Value outside allowed range for VOLTAGE", NOW, 1, NOW, null);
        when(catalog.rejections(RejectionCode.OUT_OF_RANGE, false, 0, 20))
                .thenReturn(new Results<>(List.of(rejection), 0, 20, 1, 1));
        when(imports.retry(ID)).thenReturn(new RetryReport(rejection, false));

        mvc.perform(get("/api/v1/rejections").param("reason", "OUT_OF_RANGE"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].recordNumber").value(2))
                .andExpect(jsonPath("$.items[0].reason").value("OUT_OF_RANGE"));
        mvc.perform(post("/api/v1/rejections/{id}/retry", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rejection.retryAttempts").value(1));
    }

    private static ImportReport report(boolean replayed) {
        return new ImportReport(ID, "batch.json", "digest", ImportStatus.COMPLETE, 1, 1, 1, 0,
                0, Map.of(), NOW, 5L, replayed);
    }

    private static MockMultipartFile file() {
        return new MockMultipartFile("file", "batch.json", "application/json", new byte[] {1});
    }
}
