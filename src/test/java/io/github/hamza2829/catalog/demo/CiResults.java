package io.github.hamza2829.catalog.demo;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

record CiResults(Tests unitAndMvc, Tests postgresIntegration, Coverage lines, Coverage branches) {

    static CiResults read() throws IOException, ParserConfigurationException, SAXException {
        Path target = Path.of("target");
        Tests unit = tests(target.resolve("surefire-reports"));
        Tests integration = tests(target.resolve("failsafe-reports"));
        if (unit.passed() == 0 || integration.passed() == 0) {
            throw new IllegalStateException("Expected passing unit/API tests and PostgreSQL integration tests");
        }
        Element coverage = root(target.resolve("site/jacoco/jacoco.xml"));
        return new CiResults(unit, integration, counter(coverage, "LINE"), counter(coverage, "BRANCH"));
    }

    private static Tests tests(Path directory) throws IOException, ParserConfigurationException, SAXException {
        int total = 0;
        int failures = 0;
        int errors = 0;
        int skipped = 0;
        try (var files = Files.newDirectoryStream(directory, "TEST-*.xml")) {
            for (Path file : files) {
                Element suite = root(file);
                total += number(suite, "tests");
                failures += number(suite, "failures");
                errors += number(suite, "errors");
                skipped += number(suite, "skipped");
            }
        }
        return new Tests(total, total - failures - errors - skipped, failures, errors, skipped);
    }

    private static Coverage counter(Element root, String type) {
        for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element counter && counter.getTagName().equals("counter")
                    && type.equals(counter.getAttribute("type"))) {
                int covered = number(counter, "covered");
                return new Coverage(covered, covered + number(counter, "missed"));
            }
        }
        throw new IllegalStateException("Missing aggregate JaCoCo counter for " + type);
    }

    private static Element root(Path file) throws IOException, ParserConfigurationException, SAXException {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newDefaultInstance();
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
    }

    private static int number(Element element, String attribute) {
        return Integer.parseInt(element.getAttribute(attribute));
    }

    record Tests(int total, int passed, int failures, int errors, int skipped) {
        String summary() {
            return "%d / %d passed, %d failed, %d errors, %d skipped"
                    .formatted(passed, total, failures, errors, skipped);
        }
    }

    record Coverage(int covered, int total) {
        String summary() {
            return String.format(Locale.ROOT, "%.2f%% (%d / %d)", 100.0 * covered / total, covered, total);
        }
    }
}
