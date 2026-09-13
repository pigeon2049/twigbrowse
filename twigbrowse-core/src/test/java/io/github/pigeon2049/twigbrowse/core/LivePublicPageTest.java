package io.github.pigeon2049.twigbrowse.core;

import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in smoke tests against stable public pages; these are not ranking or load tests. */
@EnabledIfEnvironmentVariable(named="TWIGBROWSE_LIVE_TEST", matches="true")
class LivePublicPageTest {
    private static final String DOCS = "https://docs.spring.io/spring-ai/reference/api/tools.html";

    @Test void navigatesQueriesAndReadsPublicSpringAiDocs() {
        var settings = new BrowserSettings(java.time.Duration.ofSeconds(8), java.time.Duration.ofSeconds(2),
                java.time.Duration.ofSeconds(35), 2, 4, 12000, false, false);
        try (var manager = new BrowserSessionManager(settings,
                new SearchService(List.of(HtmlSearchEngine.BING), settings));
             var session = manager.openSession()) {
            var page = session.navigate(DOCS);
            assertTrue(page.url().startsWith("https://docs.spring.io/"));
            assertFalse(page.text().isBlank());
            var article = session.query(page.pageId(), "article");
            assertFalse(article.elements().isEmpty());
            var heading = session.query(page.pageId(), "h1");
            assertFalse(heading.elements().isEmpty());
            var body = session.read(page.pageId());
            assertTrue(body.text().toLowerCase(Locale.ROOT).contains("tool"));
            assertTrue(body.url().contains("docs.spring.io"));
        }
    }

    @Test void searchesAChineseQueryAndReturnsInspectableSources() {
        var response = new SearchService(List.of(HtmlSearchEngine.BING, HtmlSearchEngine.DUCKDUCKGO), BrowserSettings.defaults())
                .search("Spring AI 工具调用 官方文档", 5);
        assertTrue(response.success(), response.toString());
        assertNotNull(response.engine());
        assertFalse(response.results().isEmpty());
        response.results().forEach(result -> {
            assertTrue(result.url().startsWith("http://") || result.url().startsWith("https://"));
            assertNull(URI.create(result.url()).getUserInfo());
        });
    }

    @Test void eachConfiguredHtmlEngineGetsAnIndependentRealAttempt() {
        for (var engine : HtmlSearchEngine.values()) {
            var response = new SearchService(List.of(engine), BrowserSettings.defaults())
                    .search("Spring AI reference", 3);
            System.out.println("LIVE_PUBLIC_ENGINE " + engine.id() + " => " + response);
            // Providers may challenge a CI address; a successful response must still be valid.
            if (response.success()) {
                assertEquals(engine.id(), response.engine());
                assertFalse(response.results().isEmpty());
            } else assertFalse(response.attempts().isEmpty());
        }
    }
}
