package io.github.hamza2829.catalog.api;

import io.github.hamza2829.catalog.imports.ImportService;
import io.github.hamza2829.catalog.model.CatalogViews.ImportReport;
import io.github.hamza2829.catalog.model.CatalogViews.ProductDetails;
import io.github.hamza2829.catalog.model.CatalogViews.Rejection;
import io.github.hamza2829.catalog.model.CatalogViews.Results;
import io.github.hamza2829.catalog.model.CatalogViews.RetryReport;
import io.github.hamza2829.catalog.model.LifecycleStatus;
import io.github.hamza2829.catalog.model.ProductData;
import io.github.hamza2829.catalog.model.RejectionCode;
import io.github.hamza2829.catalog.persistence.CatalogQueries;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** REST endpoints for file ingestion, search, product history and dead-letter retries. */
@RestController
@RequestMapping("/api/v1")
public class CatalogController {
    private final ImportService imports;
    private final CatalogQueries catalog;

    public CatalogController(ImportService imports, CatalogQueries catalog) {
        this.imports = imports;
        this.catalog = catalog;
    }

    @Operation(summary = "Import a CSV, JSON or XML file, or return its existing report")
    @ApiResponse(responseCode = "201", description = "Import completed", useReturnTypeSchema = true)
    @ApiResponse(responseCode = "200", description = "Existing report returned", useReturnTypeSchema = true)
    @PostMapping(path = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ImportReport> upload(@RequestPart("file") MultipartFile file) throws IOException {
        ImportReport report = imports.importFile(file.getOriginalFilename(), file.getBytes());
        if (report.replayed()) {
            return ResponseEntity.ok(report);
        }
        return ResponseEntity.created(URI.create("/api/v1/imports/" + report.importId())).body(report);
    }

    @Operation(summary = "Get an import report and its committed row counts")
    @GetMapping("/imports/{id}")
    public ImportReport importReport(@PathVariable UUID id) {
        return catalog.importJob(id);
    }

    @Operation(summary = "List or search products by article, name, material or keywords")
    @GetMapping("/products")
    public Results<ProductData> products(
            @RequestParam(required = false) @Size(max = 120) String q,
            @RequestParam(required = false) @Size(max = 64) String category,
            @RequestParam(required = false) LifecycleStatus status,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalog.search(q, category, status, page, size);
    }

    @Operation(summary = "Get the current product and its change history, newest first")
    @GetMapping("/products/{article}")
    public ProductDetails product(
            @PathVariable @Size(min = 1, max = 64) String article,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int historyPage,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int historySize) {
        return catalog.product(article, historyPage, historySize);
    }

    @Operation(summary = "List rejected rows and their latest reasons")
    @GetMapping("/rejections")
    public Results<Rejection> rejections(
            @RequestParam(required = false) RejectionCode reason,
            @RequestParam(defaultValue = "false") boolean includeResolved,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return catalog.rejections(reason, includeResolved, page, size);
    }

    @Operation(summary = "Retry the original rejected row with the current rules")
    @PostMapping("/rejections/{id}/retry")
    public RetryReport retry(@PathVariable UUID id) {
        return imports.retry(id);
    }
}
