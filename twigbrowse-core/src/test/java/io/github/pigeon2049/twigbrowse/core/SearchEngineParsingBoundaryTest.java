package io.github.pigeon2049.twigbrowse.core;

import org.htmlunit.WebClient;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchEngineParsingBoundaryTest {
    @Test void parsersIgnoreUnsafeAndCredentialLinks() throws Exception {
        try (WebClient client = new WebClient()) {
            HtmlPage page = client.loadHtmlCodeIntoCurrentWindow("<html><body><div class='result'><a class='result__a' href='javascript:alert(1)'>bad</a>"
                + "<a class='result__a' href='https://example.org/good'>good</a><a class='result__a' href='http://user:pass@example.org/x'>cred</a></div></body></html>");
            var results = HtmlSearchEngine.DUCKDUCKGO.parse(page, 10);
            assertEquals(1, results.size());
            assertEquals("https://example.org/good", results.get(0).url());
        }
    }

    @Test void parsersHonorLimitAndDeduplicateUrls() throws Exception {
        try (WebClient client = new WebClient()) {
            HtmlPage page = client.loadHtmlCodeIntoCurrentWindow("<html><body>"
                + "<a class='result__a' href='https://example.org/a'>A1</a>"
                + "<a class='result__a' href='https://example.org/a'>A2</a>"
                + "<a class='result__a' href='https://example.org/b'>B</a></body></html>");
            var results = HtmlSearchEngine.DUCKDUCKGO.parse(page, 1);
            assertEquals(1, results.size());
            assertEquals("https://example.org/a", results.get(0).url());
        }
    }
}
