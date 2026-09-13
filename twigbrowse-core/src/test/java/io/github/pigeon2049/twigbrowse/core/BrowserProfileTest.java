package io.github.pigeon2049.twigbrowse.core;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.htmlunit.BrowserVersion;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserProfileTest {
    @Test void concurrentOverridesAffectHeadersAndNavigatorWithoutChangingDefaults() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = "<html><body><article>" + exchange.getRequestHeaders().getFirst("User-Agent") + "|"
                + exchange.getRequestHeaders().getFirst("Accept-Language") + "</article><p id='js'></p>"
                + "<script>document.getElementById('js').textContent=navigator.userAgent+'|'+navigator.language+'|'+navigator.platform+'|'+new Date('2026-01-01T00:00:00Z').getTimezoneOffset()</script></body></html>";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        BrowserSettings settings = new BrowserSettings(Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofSeconds(15), 2, 2, 4000, true, true);
        BrowserProfile defaults = BrowserProfile.builder().preset(BrowserProfile.Preset.CHROME).userAgent("TwigBrowse-Default")
            .language("en-US").acceptLanguage("en-US,en;q=0.9").timeZone("UTC").build();
        String originalUa = BrowserVersion.CHROME.getUserAgent();
        try (var manager = new BrowserSessionManager(settings, new SearchService(List.of(HtmlSearchEngine.BING), settings), defaults)) {
            try (var a = manager.openSession(); var b = manager.openSession(BrowserProfile.builder()
                    .userAgent("TwigBrowse-Request").language("zh-CN").acceptLanguage("zh-CN,zh;q=0.9")
                    .platform("Linux x86_64").timeZone("Asia/Shanghai").build())) {
                String url = "http://127.0.0.1:" + server.getAddress().getPort();
                var pa = CompletableFuture.supplyAsync(() -> a.navigate(url));
                var pb = CompletableFuture.supplyAsync(() -> b.navigate(url));
                String defaultText = pa.get(20, TimeUnit.SECONDS).text(), overrideText = pb.get(20, TimeUnit.SECONDS).text();
                assertTrue(defaultText.contains("TwigBrowse-Default|en-US,en;q=0.9"), defaultText);
                assertTrue(defaultText.contains("TwigBrowse-Default|en-US"), defaultText);
                assertFalse(defaultText.contains("TwigBrowse-Request"));
                assertTrue(overrideText.contains("TwigBrowse-Request|zh-CN,zh;q=0.9"), overrideText);
                assertTrue(overrideText.contains("TwigBrowse-Request|zh-CN|Linux x86_64|-480"), overrideText);
            }
            BrowserSessionTest.awaitEmpty(manager);
            try (var fresh = manager.openSession()) {
                assertTrue(fresh.navigate("http://127.0.0.1:" + server.getAddress().getPort()).text().contains("TwigBrowse-Default"));
            }
            assertEquals(originalUa, BrowserVersion.CHROME.getUserAgent());
        } finally { server.stop(0); }
    }
    @Test void presetsAndPartialOverridesAreValidated() {
        for (var preset : BrowserProfile.Preset.values()) assertNotNull(BrowserProfile.builder().preset(preset).build().toBrowserVersion());
        assertEquals(BrowserProfile.Preset.CHROME, BrowserProfile.defaults().overlay(BrowserProfile.builder().language("zh-CN").build()).preset());
        assertThrows(IllegalArgumentException.class, () -> BrowserProfile.builder().userAgent("ok\r\nInjected: value").build());
        assertThrows(java.time.DateTimeException.class, () -> BrowserProfile.builder().timeZone("not/a/timezone").build());
        assertThrows(IllegalArgumentException.class, () -> BrowserProfile.builder().language("").build());
    }
}
