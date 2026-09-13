package io.github.pigeon2049.twigbrowse.core;

import java.net.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.htmlunit.*;
import org.htmlunit.html.HtmlPage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchServiceTest {
    @Test void adaptersExtractOnlyRealResultsAndUnwrapRedirects() throws Exception {
        String destination = "https://example.org/docs";
        String bing = "https://cn.bing.com/ck/a?u=a1" + Base64.getUrlEncoder().withoutPadding().encodeToString(destination.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<HtmlSearchEngine, String> fixtures = Map.of(
            HtmlSearchEngine.BING, "<li class='b_algo'><h2><a href='" + bing + "'>Bing result</a></h2><div class='b_caption'><p>Summary</p></div></li>",
            HtmlSearchEngine.DUCKDUCKGO, "<div class='result'><a class='result__a' href='//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.org%2Fdocs'>DDG result</a><a class='result__snippet'>Summary</a></div>");
        for (var fixture : fixtures.entrySet()) {
            try (WebClient client = new WebClient()) {
                client.getOptions().setJavaScriptEnabled(false);
                HtmlPage page = client.loadHtmlCodeIntoCurrentWindow("<html><body><a href='https://ads.example'>Ad</a>" + fixture.getValue() + "</body></html>");
                var results = fixture.getKey().parse(page, 5);
                assertEquals(1, results.size());
                assertFalse(results.get(0).snippet().isBlank());
                assertEquals(destination, results.get(0).url());
            }
        }
    }
    @Test void timeoutThenChallengeFallsBackAndStopsAfterSuccess() {
        var calls = new ArrayList<String>();
        var service = new SearchService(List.of(
            engine("timeout", (browser, query, limit) -> { calls.add("timeout"); throw new SocketTimeoutException(); }),
            engine("challenge", (browser, query, limit) -> { calls.add("challenge"); throw new BrowserException("CHALLENGE", "captcha"); }),
            engine("working", (browser, query, limit) -> { calls.add("working"); return List.of(new SearchEngine.SearchResult("Title", "https://example.org", "Summary")); }),
            engine("unused", (browser, query, limit) -> { fail("Must stop after success"); return List.of(); })
        ), BrowserSettings.defaults());
        var result = service.search("spring ai", 5);
        assertTrue(result.success()); assertEquals("working", result.engine());
        assertEquals(List.of("timeout", "challenge", "working"), calls);
        assertEquals("CHALLENGE", result.attempts().get(1).status());
    }
    @Test void allFailuresAreExplicitAndQueriesAreValidated() {
        AtomicInteger calls = new AtomicInteger();
        var service = new SearchService(List.of(engine("empty", (b, q, l) -> { calls.incrementAndGet(); return List.of(); })), BrowserSettings.defaults());
        var response = service.search("x", 1);
        assertFalse(response.success()); assertNull(response.engine()); assertEquals(1, response.attempts().size());
        for (String query : List.of("", " ", "a".repeat(501))) assertThrows(BrowserException.class, () -> service.search(query, 5));
        assertThrows(BrowserException.class, () -> service.search("x", 0));
        assertThrows(BrowserException.class, () -> service.search("x", 11));
        assertEquals(1, calls.get());
    }
    @Test void eachEngineAttemptGetsFreshCookies() {
        var service = new SearchService(List.of(
            engine("one", (b, q, l) -> { b.getCookieManager().addCookie(new org.htmlunit.http.Cookie("example.org", "user", "alice")); return List.of(); }),
            engine("two", (b, q, l) -> { assertTrue(b.getCookieManager().getCookies().isEmpty()); return List.of(new SearchEngine.SearchResult("x", "https://example.org", "")); })
        ), BrowserSettings.defaults());
        assertTrue(service.search("x", 1).success());
    }
    @Test void captchaAndMarkupChangesDoNotPretendToBeSearchResults() throws Exception {
        try (WebClient browser = new WebClient()) {
            browser.getOptions().setJavaScriptEnabled(false);
            HtmlPage challenge = browser.loadHtmlCodeIntoCurrentWindow("<html><body><div id='b_captcha'>verify</div></body></html>");
            assertEquals("CHALLENGE", assertThrows(BrowserException.class, () -> HtmlSearchEngine.BING.parse(challenge, 5)).code());
            HtmlPage changed = browser.loadHtmlCodeIntoCurrentWindow("<html><body><a href='https://example.org'>unrelated link</a></body></html>");
            assertEquals("NO_RESULTS_OR_LAYOUT_CHANGE", assertThrows(BrowserException.class, () -> HtmlSearchEngine.BING.parse(changed, 5)).code());
        }
    }
    static SearchEngine engine(String id, SearchFunction function) {
        return new SearchEngine() {
            public String id() { return id; }
            public List<SearchResult> search(WebClient browser, String query, int limit) throws Exception { return function.search(browser, query, limit); }
        };
    }
    interface SearchFunction { List<SearchEngine.SearchResult> search(WebClient browser, String query, int limit) throws Exception; }
}
