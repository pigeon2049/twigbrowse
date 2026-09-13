package io.github.pigeon2049.twigbrowse.research;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientBuilderCustomizer;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.model.chat.client.autoconfigure.*;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.concurrent.*;
import org.htmlunit.*;
import org.htmlunit.html.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.chat.model.ToolContext;
import static org.junit.jupiter.api.Assertions.*;

class HtmlUnitCapabilityTest {
    private HttpServer server;
    private String origin;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            String type = "text/html; charset=utf-8";
            if (path.equals("/json")) { body = "{\"value\":\"ajax-ready\"}"; type = "application/json"; }
            else if (path.equals("/echo")) { body = "<title>Result</title><p id='result'>" + exchange.getRequestURI().getRawQuery() + "</p>"; }
            else if (path.equals("/module")) { body = "<p id='module'>not-loaded</p><script type='module'>import {value} from '/module.js';document.getElementById('module').textContent=value;</script>"; }
            else if (path.equals("/module.js")) { body = "export const value='module-loaded';"; type = "text/javascript"; }
            else if (path.equals("/cookie")) { body = "<p>" + exchange.getRequestHeaders().getFirst("Cookie") + "</p>"; }
            else { body = """
                <!doctype html><html><head><title>TwigBrowse fixture</title></head><body>
                <a id='next' href='/echo?q=navigation'>Next</a>
                <form action='/echo'><input name='q' id='query'><button id='submit'>Submit</button></form>
                <p id='value'>initial</p><p id='async'>waiting</p>
                <button id='update' onclick="document.getElementById('value').textContent='clicked'">Update</button>
                <button id='xhr' onclick="var x=new XMLHttpRequest();x.open('GET','/json');x.onload=function(){document.getElementById('async').textContent=JSON.parse(x.responseText).value};x.send()">XHR</button>
                </body></html>
                """;
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    static WebClient client() {
        WebClient c = new WebClient(BrowserVersion.CHROME);
        c.getOptions().setTimeout(3000);
        c.setJavaScriptTimeout(1500);
        return c;
    }
    @Test void navigationAndReadableText() throws Exception {
        try (WebClient c = client()) {
            HtmlPage page = c.getPage(origin);
            assertEquals("TwigBrowse fixture", page.getTitleText());
            HtmlPage next = ((HtmlAnchor) page.getElementById("next")).click();
            assertEquals("/echo", next.getUrl().getPath());
            assertTrue(next.asNormalizedText().contains("q=navigation"));
        }
    }
    @Test void typingAndFormSubmission() throws Exception {
        try (WebClient c = client()) {
            HtmlPage page = c.getPage(origin);
            ((HtmlTextInput) page.getElementById("query")).type("spring ai");
            HtmlPage result = ((HtmlButton) page.getElementById("submit")).click();
            assertEquals("q=spring+ai", result.getUrl().getQuery());
        }
    }
    @Test void clickExecutesJavascriptAndAjaxUpdatesDom() throws Exception {
        try (WebClient c = client()) {
            HtmlPage page = c.getPage(origin);
            ((HtmlButton) page.getElementById("update")).click();
            assertEquals("clicked", page.getElementById("value").asNormalizedText());
            ((HtmlButton) page.getElementById("xhr")).click();
            c.waitForBackgroundJavaScript(2000);
            assertEquals("ajax-ready", page.getElementById("async").asNormalizedText());
        }
    }
    @Test void simultaneousClientsIsolateCookiesStorageAndDom() throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var barrier = new CyclicBarrier(2);
        try {
            Future<String> a = pool.submit(() -> isolatedSession("alice", barrier));
            Future<String> b = pool.submit(() -> isolatedSession("bob", barrier));
            assertEquals("alice", a.get(15, TimeUnit.SECONDS));
            assertEquals("bob", b.get(15, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
        try (WebClient fresh = client()) {
            HtmlPage page = fresh.getPage(origin);
            assertEquals("", page.executeJavaScript("document.cookie").getJavaScriptResult());
            assertEquals("empty", page.executeJavaScript("localStorage.getItem('owner') || 'empty'").getJavaScriptResult());
        }
    }
    private String isolatedSession(String owner, CyclicBarrier barrier) throws Exception {
        try (WebClient c = client()) {
            HtmlPage page = c.getPage(origin);
            page.executeJavaScript("document.cookie='owner=" + owner + "; path=/';localStorage.setItem('owner','" + owner + "');sessionStorage.setItem('owner','" + owner + "');document.getElementById('value').textContent='" + owner + "'");
            barrier.await(5, TimeUnit.SECONDS);
            assertEquals(owner, page.getElementById("value").asNormalizedText());
            assertEquals(owner, page.executeJavaScript("localStorage.getItem('owner')").getJavaScriptResult());
            assertEquals(owner, page.executeJavaScript("sessionStorage.getItem('owner')").getJavaScriptResult());
            HtmlPage echoed = c.getPage(origin + "/cookie");
            assertEquals("owner=" + owner, echoed.asNormalizedText());
            HtmlPage returned = c.getPage(origin);
            assertEquals(owner, returned.executeJavaScript("localStorage.getItem('owner')").getJavaScriptResult());
            return owner;
        }
    }
    @Test void springAiToolCallbackCanReadWithPrivateContext() throws Exception {
        try (WebClient c = client()) {
            var callback = ToolCallbacks.from(new ReadTool(c, origin))[0];
            assertEquals("web_read", callback.getToolDefinition().name());
            assertFalse(callback.getToolDefinition().inputSchema().contains("tenant"));
            assertFalse(callback.getToolDefinition().inputSchema().contains("context"));
            String result = callback.call("{}", new ToolContext(Map.of("tenant", "alice")));
            assertTrue(result.contains("TwigBrowse fixture"));
            assertThrows(RuntimeException.class, () -> callback.call("{}", new ToolContext(Map.of())));
        }
    }
    @Test void optInFetchPolyfillUpdatesDom() throws Exception {
        try (WebClient c = client()) {
            c.getOptions().setFetchPolyfillEnabled(true);
            HtmlPage page = c.getPage(origin);
            page.executeJavaScript("fetch('/json').then(function(r){return r.json()}).then(function(data){document.getElementById('async').textContent=data.value})");
            c.waitForBackgroundJavaScript(2000);
            assertEquals("ajax-ready", page.getElementById("async").asNormalizedText());
        }
    }
    @Test void scriptTimeoutStopsSimpleInfiniteLoop() throws Exception {
        try (WebClient c = client()) {
            c.setJavaScriptTimeout(200);
            HtmlPage page = c.getPage(origin);
            long start = System.nanoTime();
            assertThrows(RuntimeException.class, () -> page.executeJavaScript("while(true){}"));
            assertTrue(System.nanoTime() - start < TimeUnit.SECONDS.toNanos(5));
        }
    }
    @Test void observeModernJavascriptCapabilities() throws Exception {
        try (WebClient c = client()) {
            HtmlPage page = c.getPage(origin);
            for (String expression : new String[] {"typeof fetch", "typeof Promise", "typeof WebSocket", "({a:{b:7}})?.a?.b ?? 0"}) {
                try { System.out.println("CAPABILITY " + expression + " = " + page.executeJavaScript(expression).getJavaScriptResult()); }
                catch (Exception e) { System.out.println("CAPABILITY " + expression + " = ERROR " + e.getClass().getSimpleName()); }
            }
            HtmlPage module = c.getPage(origin + "/module");
            c.waitForBackgroundJavaScript(1000);
            System.out.println("CAPABILITY module-script = " + module.getElementById("module").asNormalizedText());
        }
    }
    @Test void boot411AutoConfiguredBuilderExecutesAi201ToolLoop() throws Exception {
        try (WebClient browser = client()) {
            AtomicInteger calls = new AtomicInteger();
            ChatModel model = new ChatModel() {
                @Override public org.springframework.ai.chat.prompt.ChatOptions getOptions() {
                    return org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build();
                }
                @Override public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                int n = calls.incrementAndGet();
                assertTrue(n <= 2, "Tool loop must terminate");
                if (n == 1) {
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                        .toolCalls(List.of(new AssistantMessage.ToolCall("read-1", "function", "web_read", "{}")))
                        .build())));
                }
                boolean receivedPage = prompt.getInstructions().stream()
                    .filter(m -> m instanceof ToolResponseMessage)
                    .map(m -> (ToolResponseMessage)m)
                    .flatMap(m -> m.getResponses().stream())
                    .anyMatch(r -> r.responseData().contains("TwigBrowse fixture"));
                assertTrue(receivedPage, "Browser content must return to the model");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("fixture read").build())));
                }
            };
            new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
                .withBean(ChatModel.class, () -> model)
                .withBean(ChatClientBuilderCustomizer.class, () -> builder -> builder.defaultTools(new ReadTool(browser, origin)))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    String answer = context.getBean(ChatClient.Builder.class).build().prompt()
                        .user("Read the fixture page")
                        .toolContext(Map.of("tenant", "alice"))
                        .call().content();
                    assertEquals("fixture read", answer);
                    assertEquals(2, calls.get());
                });
        }
    }
    public static class ReadTool {
        private final WebClient client;
        private final String origin;
        ReadTool(WebClient client, String origin) { this.client = client; this.origin = origin; }
        @Tool(name="web_read", description="Read the current research fixture page")
        public String read(ToolContext context) throws Exception {
            if (!"alice".equals(context.getContext().get("tenant"))) throw new IllegalArgumentException("Missing trusted identity");
            HtmlPage page = client.getPage(origin);
            return page.getTitleText() + "\n" + page.asNormalizedText();
        }
    }
}
