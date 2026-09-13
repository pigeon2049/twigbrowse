package io.github.pigeon2049.twigbrowse.core;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.htmlunit.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class BrowserScenariosTest {
    static HttpServer server;
    static ExecutorService httpWorkers;
    static String base;
    @BeforeAll static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpWorkers = Executors.newFixedThreadPool(4); server.setExecutor(httpWorkers);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = switch (path) {
                case "/dynamic" -> "<button id='load' onclick=\"fetch('/payload').then(r=>r.text()).then(t=>document.querySelector('article').innerHTML=t)\">Load</button><article></article>";
                case "/payload" -> "<p id='loaded'>Async content</p>";
                case "/storage" -> "<p id='value'></p><script>document.getElementById('value').textContent=(localStorage.getItem('user')||'empty')+'|'+(sessionStorage.getItem('user')||'empty')</script><button onclick=\"localStorage.setItem('user','alice');sessionStorage.setItem('user','alice')\">Store</button>";
                case "/detach" -> "<a id='item' href='/'>Item</a><script>setTimeout(()=>document.getElementById('item').remove(),800)</script>";
                case "/loop" -> "<script>while(true){}</script><p>Recovered</p>";
                case "/inputs" -> "<input type='file'><input type='text' disabled><input type='text' readonly><input type='radio' name='choice' value='a'><input type='radio' name='choice' value='b'><textarea name='note'></textarea>";
                case "/long" -> "<article>" + "x".repeat(1000) + "</article>";
                case "/large" -> "x".repeat(4 * 1024 * 1024 + 1);
                case "/json" -> "{\"ok\":true}";
                default -> "<article>Destination</article>";
            };
            if (path.equals("/redirect")) exchange.getResponseHeaders().set("Location", "/destination");
            byte[] bytes = ((path.equals("/json") || path.equals("/payload")) ? body : "<html><head><title>Scenario</title></head><body>" + body + "</body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", path.equals("/json") ? "application/json" : "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(path.equals("/redirect") ? 302 : path.equals("/404") ? 404 : 200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterAll static void stop() { server.stop(0); httpWorkers.shutdownNow(); }
    static BrowserSettings settings(boolean javascript) {
        return new BrowserSettings(Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofSeconds(12), 4, 3, 200, javascript, true);
    }
    static BrowserSessionManager manager(boolean javascript) {
        var settings = settings(javascript);
        return new BrowserSessionManager(settings, new SearchService(List.of(HtmlSearchEngine.BING), settings));
    }
    @Test void agentCanClickThenWaitForFetchPopulatedDom() {
        try (var manager = manager(true); var session = manager.openSession()) {
            var page = session.navigate(base + "/dynamic");
            session.click(page.pageId(), page.elements().get(0).ref());
            var loaded = session.waitFor(page.pageId(), "#loaded", 3000);
            assertEquals("Async content", loaded.elements().get(0).text());
            assertEquals("Async content", session.read(page.pageId()).text());
        }
    }
    @Test void redirectReportsFinalUrl() {
        try (var manager = manager(false); var session = manager.openSession()) {
            var page = session.navigate(base + "/redirect");
            assertTrue(page.url().endsWith("/destination"));
            assertEquals("Destination", session.read(page.pageId()).text());
        }
    }
    @Test void localAndSessionStoragePersistOnlyWithinOneRequest() {
        try (var manager = manager(true); var a = manager.openSession(); var b = manager.openSession()) {
            var first = a.navigate(base + "/storage");
            assertTrue(first.text().contains("empty|empty"));
            a.click(first.pageId(), first.elements().get(0).ref());
            var stored = a.navigate(base + "/storage");
            // localStorage is shared by origin, sessionStorage by top-level window.
            assertTrue(stored.text().contains("alice|empty"), stored.text());
            assertTrue(b.navigate(base + "/storage").text().contains("empty|empty"));
            a.closePage(first.pageId());
        }
    }
    @Test void asynchronousRemovalRejectsPreviouslyValidReference() throws Exception {
        try (var manager = manager(true); var session = manager.openSession()) {
            var page = session.navigate(base + "/detach");
            String ref = page.elements().get(0).ref();
            Thread.sleep(1000);
            assertEquals("STALE_REFERENCE", assertThrows(BrowserException.class, () -> session.attribute(page.pageId(), ref, "href")).code());
        }
    }
    @Test void scriptTimeoutAndJavascriptDisableHaveExplicitBoundaries() {
        try (var manager = manager(true); var session = manager.openSession()) {
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> assertTrue(session.navigate(base + "/loop").text().contains("Recovered")));
        }
        try (var manager = manager(false); var session = manager.openSession()) {
            var page = session.navigate(base + "/dynamic");
            session.click(page.pageId(), page.elements().get(0).ref());
            assertTrue(session.query(page.pageId(), "#loaded").elements().isEmpty());
        }
    }
    @Test void unsupportedInputsAndArgumentLimitsDoNotPerformActions() {
        try (var manager = manager(true); var session = manager.openSession()) {
            String id = session.navigate(base + "/inputs").pageId();
            var file = session.query(id, "input[type=file]").elements().get(0);
            assertEquals("NOT_EDITABLE", assertThrows(BrowserException.class, () -> session.type(id, file.ref(), "/etc/passwd")).code());
            for (String selector : List.of("input[disabled]", "input[readonly]")) {
                var blocked = session.query(id, selector).elements().get(0);
                assertEquals("NOT_EDITABLE", assertThrows(BrowserException.class, () -> session.type(id, blocked.ref(), "changed")).code());
            }
            assertThrows(BrowserException.class, () -> session.waitFor(id, "input", 5001));
            assertThrows(BrowserException.class, () -> session.query(id, "a".repeat(501)));
            var text = session.query(id, "textarea").elements().get(0);
            assertEquals("INVALID_ARGUMENT", assertThrows(BrowserException.class, () -> session.type(id, text.ref(), "x".repeat(4001))).code());
            session.type(id, text.ref(), "multiline\ntext");
            var radio = session.query(id, "input[type=radio][value=b]").elements().get(0);
            session.check(id, radio.ref(), true);
            var checked = session.query(id, "input:checked").elements().get(0);
            assertEquals("b", session.attribute(id, checked.ref(), "value").value());
        }
    }
    @Test void badResponsesDoNotLeakPageSlotsAndTextIsTruncated() {
        try (var manager = manager(false); var session = manager.openSession()) {
            for (String path : List.of("/404", "/json", "/large", "/404"))
                assertThrows(BrowserException.class, () -> session.navigate(base + path));
            var page = session.navigate(base + "/long");
            assertTrue(page.truncated()); assertEquals(200, page.text().length());
            var body = session.read(page.pageId());
            assertTrue(body.truncated()); assertEquals(200, body.text().length());
            assertEquals(0, body.offset()); assertNotNull(body.nextOffset());
            var next = session.read(page.pageId(), body.nextOffset());
            assertEquals(body.nextOffset(), next.offset()); assertTrue(next.text().length() > 0);
        }
    }
    @Test void timeoutClosesSessionAndReleasesCapacityOnlyAfterCleanup() throws Exception {
        var settings = new BrowserSettings(Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(2), 1, 1, 200, false, true);
        var engine = SearchServiceTest.engine("slow", (browser, query, limit) -> { Thread.sleep(10000); return List.of(); });
        try (var manager = new BrowserSessionManager(settings, new SearchService(List.of(engine), settings)); var session = manager.openSession()) {
            assertEquals("TIMEOUT", assertThrows(BrowserException.class, () -> session.search("slow", 1)).code());
            assertEquals("CLOSED", assertThrows(BrowserException.class, () -> session.navigate(base)).code());
            BrowserSessionTest.awaitEmpty(manager);
        }
    }
    @Test void managerShutdownRejectsExistingAndNewSessions() throws Exception {
        var manager = manager(false); var session = manager.openSession(); session.navigate(base);
        manager.close(); BrowserSessionTest.awaitEmpty(manager);
        assertEquals("CLOSED", assertThrows(BrowserException.class, manager::openSession).code());
        assertEquals("CLOSED", assertThrows(BrowserException.class, () -> session.navigate(base)).code());
    }
    @Test void connectionGuardAlsoRejectsDirectSubresourceRequests() throws Exception {
        try (WebClient browser = Browsers.create(BrowserSettings.defaults(), true, BrowserProfile.defaults())) {
            for (String url : List.of(base, "http://169.254.169.254/latest/meta-data/", "file:/etc/passwd"))
                assertThrows(java.io.IOException.class, () -> browser.getWebConnection().getResponse(new WebRequest(new URL(url))));
        }
    }
    @Test void serialWorkerBoundsOperationCount() {
        AtomicInteger calls = new AtomicInteger();
        var settings = settings(false);
        var engine = SearchServiceTest.engine("count", (browser, query, limit) -> { calls.incrementAndGet(); return List.of(new SearchEngine.SearchResult("x", "https://example.org", "")); });
        try (var manager = new BrowserSessionManager(settings, new SearchService(List.of(engine), settings)); var session = manager.openSession()) {
            String pageId = session.navigate(base).pageId();
            for (int i = 1; i < 128; i++) session.read(pageId);
            assertEquals("OPERATION_LIMIT", assertThrows(BrowserException.class, () -> session.search("x", 1)).code());
            assertEquals(0, calls.get());
        }
    }
}
