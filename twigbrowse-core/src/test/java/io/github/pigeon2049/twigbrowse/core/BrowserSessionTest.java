package io.github.pigeon2049.twigbrowse.core;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserSessionTest {
    HttpServer server;
    String base;
    BrowserSessionManager manager;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            String query = rawQuery == null ? null : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8);
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (query != null && query.startsWith("user=")) exchange.getResponseHeaders().add("Set-Cookie", query + "; Path=/");
            String html = "<html><head><title>Fixture</title></head><body><article>cookie=" + cookie + " query=" + query
                + "</article><form action='/form'><input name='q'><button>Submit</button></form>"
                + "<select name='language'><option value='en'>English</option><option value='zh'>Chinese</option></select><input type='checkbox' name='agree'><a href='/next'>Next</a><button onclick=\"document.querySelector('article').textContent='changed'\">Change</button></body></html>";
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
        BrowserSettings settings = new BrowserSettings(Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofSeconds(10), 2, 2, 2000, true, true);
        manager = new BrowserSessionManager(settings, new SearchService(List.of(HtmlSearchEngine.BING), settings));
    }
    @AfterEach void teardown() { if (manager != null) manager.close(); if (server != null) server.stop(0); }
    @Test void navigationTypingClickingAndStaleReferences() {
        try (var session = manager.openSession()) {
            var first = session.navigate(base);
            var input = first.elements().stream().filter(e -> e.tag().equals("input")).findFirst().orElseThrow();
            var typed = session.type(first.pageId(), input.ref(), "twig browse");
            assertEquals("STALE_REFERENCE", assertThrows(BrowserException.class, () -> session.type(first.pageId(), input.ref(), "again")).code());
            String submit = typed.elements().stream().filter(e -> e.label().equals("Submit")).findFirst().orElseThrow().ref();
            var result = session.click(first.pageId(), submit);
            assertTrue(result.url().contains("q=twig"));
            assertTrue(session.read(first.pageId()).text().contains("twig browse"));
            String change = result.elements().stream().filter(e -> e.label().equals("Change")).findFirst().orElseThrow().ref();
            assertTrue(session.click(first.pageId(), change).text().contains("changed"));
            session.closePage(first.pageId());
            assertEquals("UNKNOWN_PAGE", assertThrows(BrowserException.class, () -> session.read(first.pageId())).code());
        }
    }
    @Test void domSelectorsAttributesSelectionsAndWaits() {
        try (var session = manager.openSession()) {
            String pageId = session.navigate(base).pageId();
            var link = session.query(pageId, "a[href]").elements().get(0);
            assertEquals("/next", session.attribute(pageId, link.ref(), "href").value());
            assertFalse(session.attribute(pageId, link.ref(), "missing").present());
            assertTrue(session.query(pageId, ".does-not-exist").elements().isEmpty());
            var select = session.query(pageId, "select").elements().get(0);
            session.select(pageId, select.ref(), "zh");
            var selected = session.query(pageId, "option:checked").elements().get(0);
            assertEquals("zh", session.attribute(pageId, selected.ref(), "value").value());
            var checkbox = session.query(pageId, "input[type=checkbox]").elements().get(0);
            session.check(pageId, checkbox.ref(), true);
            assertEquals(1, session.query(pageId, "input:checked").elements().size());
            assertEquals(1, session.waitFor(pageId, "article", 100).elements().size());
            assertEquals("ELEMENT_TIMEOUT", assertThrows(BrowserException.class, () -> session.waitFor(pageId, "#missing", 50)).code());
            assertEquals("INVALID_SELECTOR", assertThrows(BrowserException.class, () -> session.query(pageId, "[[")).code());
        }
    }
    @Test void concurrentRequestsHaveSeparateCookiesAndPageCapabilities() throws Exception {
        try (var a = manager.openSession(); var b = manager.openSession()) {
            var futureA = CompletableFuture.supplyAsync(() -> a.navigate(base + "?user=alice"));
            var futureB = CompletableFuture.supplyAsync(() -> b.navigate(base + "?user=bob"));
            var pageA = futureA.get(10, TimeUnit.SECONDS); var pageB = futureB.get(10, TimeUnit.SECONDS);
            var nextA = a.navigate(base); var nextB = b.navigate(base);
            assertTrue(a.read(nextA.pageId()).text().contains("user=alice"));
            assertFalse(a.read(nextA.pageId()).text().contains("bob"));
            assertTrue(b.read(nextB.pageId()).text().contains("user=bob"));
            assertEquals("UNKNOWN_PAGE", assertThrows(BrowserException.class, () -> b.read(pageA.pageId())).code());
            assertEquals("STALE_REFERENCE", assertThrows(BrowserException.class, () -> b.click(pageB.pageId(), pageA.elements().get(0).ref())).code());
        }
    }
    @Test void lazyCapacityPageLimitAndCleanup() throws Exception {
        var a = manager.openSession(); var b = manager.openSession(); var c = manager.openSession();
        assertEquals(0, manager.activeSessions());
        var page = a.navigate(base); a.navigate(base); b.navigate(base);
        assertEquals("PAGE_LIMIT", assertThrows(BrowserException.class, () -> a.navigate(base)).code());
        assertEquals("CAPACITY", assertThrows(BrowserException.class, () -> c.navigate(base)).code());
        a.closePage(page.pageId()); a.navigate(base);
        a.close(); b.close(); c.close();
        awaitEmpty(manager);
        assertEquals("CLOSED", assertThrows(BrowserException.class, () -> a.navigate(base)).code());
    }
    @Test void defaultNetworkPolicyBlocksLocalNavigationAndUnsafeSchemes() throws Exception {
        var policy = new UrlPolicy(false);
        for (String url : List.of(base, "http://[::1]/", "http://100.100.100.200/", "http://169.254.169.254/", "file:/etc/passwd", "http://user:pass@example.com/"))
            assertThrows(java.io.IOException.class, () -> policy.check(new URL(url)));
        assertTrue(UrlPolicy.isPublic(InetAddress.getByName("8.8.8.8")));
        assertFalse(UrlPolicy.isPublic(InetAddress.getByName("198.18.0.1")));
        assertFalse(UrlPolicy.isPublic(InetAddress.getByName("fc00::1")));
    }

    @Test void idleSessionIsReapedAndCapacityIsReleased() throws Exception {
        var settings = new BrowserSettings(Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofSeconds(10), 1, 2, 2000, false, true);
        try (var shortLivedManager = new BrowserSessionManager(settings,
                new SearchService(List.of(HtmlSearchEngine.BING), settings), BrowserProfile.defaults(), Duration.ofMillis(120))) {
            var session = shortLivedManager.openSession();
            session.navigate(base);
            assertEquals(1, shortLivedManager.activeSessions());
            awaitEmpty(shortLivedManager);
            assertEquals("CLOSED", assertThrows(BrowserException.class, () -> session.read("missing")).code());
            var replacement = shortLivedManager.openSession();
            replacement.navigate(base);
            replacement.close();
        }
    }
    static void awaitEmpty(BrowserSessionManager manager) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (manager.activeSessions() != 0 && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(0, manager.activeSessions());
    }
}
