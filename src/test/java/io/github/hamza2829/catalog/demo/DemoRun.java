package io.github.hamza2829.catalog.demo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hamza2829.catalog.model.RejectionCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Drives the packaged service over HTTP and records measured CI results. */
public final class DemoRun {
    private static final URI BASE = URI.create("http://127.0.0.1:8080");
    private static final Path REPORTS = Path.of("target", "demo");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String API = "/api/v1";
    private static final String SAMPLE_PRODUCT = API + "/products/IND-000001";

    private final HttpClient client;

    private DemoRun(HttpClient client) {
        this.client = client;
    }

    public static void main(String[] arguments) throws Exception {
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            new DemoRun(client).run();
        }
    }

    private void run() throws Exception {
        waitForReadiness();
        List<Path> files = dataFiles();
        List<JsonNode> reports = new ArrayList<>();
        long started = System.nanoTime();
        for (Path file : files) {
            JsonNode report = upload(file, 201);
            require(report.path("status").asText().equals("COMPLETE"), "Unfinished import for " + file);
            require(report.path("recordsRead").asInt() == report.path("recordsProcessed").asInt(),
                    "Incomplete checkpoint for " + file);
            reports.add(report);
        }
        long wallTimeMs = Duration.ofNanos(System.nanoTime() - started).toMillis();
        int products = getJson(API + "/products?size=1").path("totalElements").asInt();
        JsonNode sample = getJson(SAMPLE_PRODUCT);
        var totals = new ImportTotals(reports);
        verifyCatalog(products, totals, sample);
        verifyReplays(files, reports, sample);

        String metrics = get("/actuator/prometheus");
        verifyMetrics(metrics, totals, files.size());
        verifyRejection();
        JsonNode paths = getJson("/v3/api-docs").path("paths");
        require(paths.has(API + "/imports") && paths.has(API + "/rejections/{id}/retry"), "OpenAPI paths missing");
        verifyLogCorrelation();

        CiResults checks = CiResults.read();
        Files.writeString(REPORTS.resolve("metrics.prom"), metrics);
        Files.writeString(REPORTS.resolve("summary.md"), summary(files.size(), products, wallTimeMs, totals, checks));
        JSON.writerWithDefaultPrettyPrinter().writeValue(REPORTS.resolve("report.json").toFile(), Map.ofEntries(
                Map.entry("workflowRun", System.getenv().getOrDefault("GITHUB_RUN_ID", "")),
                Map.entry("revision", System.getenv().getOrDefault("GITHUB_SHA", "")),
                Map.entry("seed", SyntheticData.SEED),
                Map.entry("filesImported", files.size()),
                Map.entry("productsStored", products),
                Map.entry("recordsProcessed", totals.processed),
                Map.entry("recordsChanged", totals.changed),
                Map.entry("recordsUnchanged", totals.unchanged),
                Map.entry("recordsRejected", totals.rejected),
                Map.entry("rejectedByReason", totals.reasons),
                Map.entry("httpWallTimeMs", wallTimeMs),
                Map.entry("serviceReportedDurationMs", totals.durationMs),
                Map.entry("ciResults", checks),
                Map.entry("replayedFilesVerified", files.size()),
                Map.entry("sampleProduct", sample),
                Map.entry("imports", reports)));
        System.out.println(Files.readString(REPORTS.resolve("summary.md")));
    }

    private List<Path> dataFiles() throws IOException {
        try (var paths = Files.walk(SyntheticData.OUTPUT)) {
            var files = paths.filter(Files::isRegularFile).sorted().toList();
            long updates = files.stream().filter(path -> path.getParent().endsWith("updates")).count();
            require(updates == SyntheticData.UPDATE_BATCH_COUNT && files.size() == updates + 3,
                    "Expected all baseline files and update batches");
            return files;
        }
    }

    private static void verifyCatalog(int products, ImportTotals totals, JsonNode sample) {
        require(products == SyntheticData.PRODUCT_COUNT, "Unexpected product count: " + products);
        require(totals.processed == totals.changed + totals.unchanged + totals.rejected,
                "Import counts do not reconcile");
        require(totals.rejected == totals.reasons.values().stream().mapToInt(Integer::intValue).sum(),
                "Rejection reasons do not reconcile");
        require(totals.changed > products && totals.unchanged > 0, "Expected updates and an unchanged row");
        require(totals.reasons.containsKey(RejectionCode.DUPLICATE_ARTICLE.name()), "Expected duplicate rejections");
        require(totals.reasons.containsKey(RejectionCode.ILLEGAL_LIFECYCLE_TRANSITION.name()),
                "Expected a rejected lifecycle transition");
        require(sample.path("history").path("totalElements").asInt() == 2, "Sample should have creation and update");
        JsonNode latest = sample.path("history").path("items").get(0);
        require(latest.path("after").path("status").asText().equals("DISCONTINUED"), "Expected discontinued sample");
        require(!latest.path("before").path("priceEur").equals(latest.path("after").path("priceEur")),
                "Expected a price change in history");
    }

    private void verifyReplays(List<Path> files, List<JsonNode> reports, JsonNode sample)
            throws IOException, InterruptedException {
        for (int index = 0; index < files.size(); index++) {
            JsonNode replay = upload(files.get(index), 200);
            require(replay.path("replayed").asBoolean()
                    && replay.path("importId").equals(reports.get(index).path("importId")),
                    "Upload replay produced a new import");
        }
        require(sample.equals(getJson(SAMPLE_PRODUCT)), "Product or history changed after replay");
    }

    private static void verifyMetrics(String metrics, ImportTotals totals, int files) {
        require(metric(metrics, "catalog_records_processed_total", "operation=\"import\"") == totals.processed,
                "Prometheus processed counter differs from committed reports");
        require(metric(metrics, "catalog_records_rejected_total", "operation=\"import\"") == totals.rejected,
                "Prometheus rejected counter differs from committed reports");
        require(metric(metrics, "catalog_import_duration_seconds_count", "outcome=\"complete\"") == files,
                "Prometheus import count differs from completed files");
        require(metric(metrics, "catalog_rule_execution_seconds_count", "rule=\"normalization\"") == totals.processed,
                "Prometheus normalization timer differs from attempted rows");
    }

    private static double metric(String output, String name, String label) {
        return output.lines().filter(line -> line.startsWith(name + "{") && line.contains(label))
                .mapToDouble(line -> Double.parseDouble(line.split("\\s+")[1])).sum();
    }

    private void verifyRejection() throws IOException, InterruptedException {
        var reason = RejectionCode.ILLEGAL_LIFECYCLE_TRANSITION;
        JsonNode rejections = getJson(API + "/rejections?reason=" + reason);
        require(rejections.path("totalElements").asInt() == 1, "Expected the rejected status update in the API");
        String id = rejections.path("items").get(0).path("id").asText();
        var request = request(API + "/rejections/" + id + "/retry").POST(HttpRequest.BodyPublishers.noBody()).build();
        JsonNode result = JSON.readTree(send(request, 200));
        require(result.path("rejection").path("reason").asText().equals(reason.name())
                && result.path("rejection").path("retryAttempts").asInt() == 1, "Retry did not record its outcome");
    }

    private static void verifyLogCorrelation() throws IOException {
        try (var lines = Files.lines(REPORTS.resolve("service.log"))) {
            String line = lines.filter(text -> text.contains("Product row processed") && text.contains("rejection_id"))
                    .findFirst().orElseThrow(() -> new IllegalStateException("No rejection log was written"));
            JsonNode event = JSON.readTree(line);
            require(event.path("traceId").asText().matches("[0-9a-f]{32}")
                    && event.path("spanId").asText().matches("[0-9a-f]{16}"), "Missing OpenTelemetry trace or span ID");
            require(event.path("record_id").asText().startsWith(event.path("import_id").asText() + ":"),
                    "Missing import/record correlation");
        }
    }

    private JsonNode upload(Path file, int expectedStatus) throws IOException, InterruptedException {
        String boundary = UUID.randomUUID().toString();
        String part = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                + file.getFileName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        HttpRequest request = request(API + "/imports")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(part),
                        HttpRequest.BodyPublishers.ofFile(file),
                        HttpRequest.BodyPublishers.ofString("\r\n--" + boundary + "--\r\n")))
                .build();
        return JSON.readTree(send(request, expectedStatus));
    }

    private void waitForReadiness() throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        IOException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try {
                if (getJson("/actuator/health/readiness").path("status").asText().equals("UP")) {
                    return;
                }
            } catch (IOException failure) {
                lastFailure = failure;
            }
            Thread.sleep(500);
        }
        throw new IOException("Service did not become ready", lastFailure);
    }

    private JsonNode getJson(String path) throws IOException, InterruptedException {
        return JSON.readTree(get(path));
    }

    private String get(String path) throws IOException, InterruptedException {
        return send(request(path).GET().build(), 200);
    }

    private String send(HttpRequest request, int expectedStatus) throws IOException, InterruptedException {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != expectedStatus) {
            throw new IOException(request.method() + " " + request.uri().getPath() + " returned "
                    + response.statusCode() + " with body " + response.body());
        }
        return response.body();
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(BASE.resolve(path)).timeout(Duration.ofSeconds(20));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String summary(int files, int products, long wallTimeMs, ImportTotals totals, CiResults checks) {
        StringBuilder text = new StringBuilder("""
                ## Java 21 catalog verification

                | Measurement | Result |
                | --- | ---: |
                """);
        measurement(text, "Files imported and replayed", files);
        measurement(text, "Products stored", products);
        measurement(text, "Records processed", totals.processed);
        measurement(text, "Changed / unchanged / rejected rows",
                totals.changed + " / " + totals.unchanged + " / " + totals.rejected);
        measurement(text, "Full HTTP import wall time", wallTimeMs + " ms");
        measurement(text, "Sum of service-reported import times", totals.durationMs + " ms");
        measurement(text, "Unit and MockMvc tests", checks.unitAndMvc().summary());
        measurement(text, "PostgreSQL integration tests", checks.postgresIntegration().summary());
        measurement(text, "JaCoCo line coverage", checks.lines().summary());
        measurement(text, "JaCoCo branch coverage", checks.branches().summary());
        text.append("\n### Rejections by reason\n\n| Reason | Records |\n| --- | ---: |\n");
        totals.reasons.forEach((reason, count) -> measurement(text, reason, count));
        return text.toString();
    }

    private static void measurement(StringBuilder text, String name, Object value) {
        text.append("| ").append(name).append(" | ").append(value).append(" |\n");
    }

    private static final class ImportTotals {
        private int processed;
        private int changed;
        private int unchanged;
        private int rejected;
        private long durationMs;
        private final Map<String, Integer> reasons = new TreeMap<>();

        ImportTotals(List<JsonNode> reports) {
            for (JsonNode report : reports) {
                processed += report.path("recordsProcessed").asInt();
                changed += report.path("changed").asInt();
                unchanged += report.path("unchanged").asInt();
                rejected += report.path("rejected").asInt();
                durationMs += report.path("durationMs").asLong();
                report.path("rejectedByReason").properties().forEach(
                        entry -> reasons.merge(entry.getKey(), entry.getValue().asInt(), Integer::sum));
            }
        }
    }
}
