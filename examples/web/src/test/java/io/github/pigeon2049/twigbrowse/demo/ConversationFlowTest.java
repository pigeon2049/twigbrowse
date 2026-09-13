package io.github.pigeon2049.twigbrowse.demo;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import io.github.pigeon2049.twigbrowse.core.*;
import io.github.pigeon2049.twigbrowse.autoconfigure.*;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class ConversationFlowTest {
    static final JsonMapper JSON = JsonMapper.builder().build();
    BrowserSessionManager manager;
    Conversations conversations;
    HttpServer server;
    String base;
    final List<String> requests = new ArrayList<>();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            synchronized (requests) { requests.add(path); }
            String html = switch(path) {
                case "/article" -> "<article>First article: agents and accountability.</article>";
                case "/comments" -> "<main>Readers debate operator responsibility and evaluation design.</main>";
                default -> "<a href='/article'>First story</a><a href='/comments'>12 comments</a>";
            };
            byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        var settings = new BrowserSettings(Duration.ofSeconds(2), Duration.ofMillis(200), Duration.ofSeconds(20), 8, 8, 12000, false, true);
        manager = new BrowserSessionManager(settings, new SearchService(List.of(HtmlSearchEngine.BING), settings));
        conversations = new Conversations(manager, Duration.ofMinutes(5), Duration.ofSeconds(40), 16);
    }
    @AfterEach void cleanup() { conversations.close(); manager.close(); server.stop(0); }
    ChatController controller(ChatModel model) {
        return new ChatController(ChatClient.builder(model).defaultTools(new TwigBrowseTools())
            .defaultAdvisors(new TwigBrowseSessionAdvisor(manager)), conversations);
    }
    static ChatResponse answer(String value) { return new ChatResponse(List.of(new Generation(new AssistantMessage(value)))); }
    static abstract class Model implements ChatModel {
        public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
    }
    @Test void followUpUsesPreviousSnapshotThenOriginalCommentsUrlWithoutSearching() {
        List<Prompt> prompts = new ArrayList<>(); AtomicInteger calls = new AtomicInteger();
        var model = new Model() {
            public ChatResponse call(Prompt prompt) {
                prompts.add(prompt);
                var messages = prompt.getInstructions();
                if (messages.get(messages.size()-1) instanceof ToolResponseMessage) return answer("已读到内容。\n\n来源：" + base);
                String question = prompt.getUserMessages().get(prompt.getUserMessages().size()-1).getText();
                String tool, args;
                if (question.contains("总结")) { tool = "web_navigate"; args = JSON.writeValueAsString(Map.of("url", base)); }
                else {
                    var original = messages.stream().filter(ToolResponseMessage.class::isInstance)
                        .map(ToolResponseMessage.class::cast).findFirst().orElseThrow();
                    var page = JSON.readTree(original.getResponses().get(0).responseData()).path("data");
                    assertFalse(page.path("pageId").asText().isBlank(), "Tool snapshot must survive the HTTP turn");
                    if (question.contains("评论")) {
                        tool = "web_navigate";
                        args = JSON.writeValueAsString(Map.of("url", page.path("elements").get(1).path("resolvedUrl").asText()));
                    } else {
                        tool = "web_click";
                        args = JSON.writeValueAsString(Map.of("pageId", page.path("pageId").asText(), "ref", page.path("elements").get(0).path("ref").asText()));
                    }
                }
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(
                    new AssistantMessage.ToolCall("call"+calls.incrementAndGet(), "function", tool, args))).build())));
            }
        };
        var controller = controller(model); var user = new MockHttpSession();
        for (String text : List.of("帮我总结今天都在说什么", "第一篇详情说了什么", "大家评论区在讨论什么")) {
            var result = controller.stream(new ChatController.ChatRequest(text), user).collectList().block(Duration.ofSeconds(45));
            assertTrue(result.stream().anyMatch(e -> "done".equals(e.event())), result.toString());
            assertFalse(result.stream().anyMatch(e -> "error".equals(e.event())), result.toString());
            var saved = conversations.acquire(user);
            try { assertTrue(saved.history().stream().anyMatch(ToolResponseMessage.class::isInstance), saved.history().stream().map(m -> m.getClass().getSimpleName()).toList().toString()); }
            finally { saved.release(); }
        }
        assertEquals(List.of("/", "/article", "/comments"), requests);
        assertEquals(3, calls.get());
        assertEquals(1, manager.activeSessions());
        var first = conversations.acquire(user); var original = first.browser(); first.release();
        var other = conversations.acquire(new MockHttpSession());
        assertTrue(other.history().isEmpty()); assertNotSame(original, other.browser()); other.release();
        original.close();
        var resumed = conversations.acquire(user);
        var replacement = resumed.browser();
        assertNotSame(original, replacement);
        assertFalse(resumed.history().stream().anyMatch(ToolResponseMessage.class::isInstance));
        assertEquals(3, resumed.history().stream().filter(UserMessage.class::isInstance).count());
        resumed.release();
        controller.reset(user); assertTrue(replacement.isClosed());
    }
    @Test void busyConversationCannotBeResetOrAcquiredAndIdleConversationIsReclaimed() {
        try (var shortLived = new Conversations(manager, Duration.ofNanos(1), Duration.ofSeconds(10), 1)) {
            var user = new MockHttpSession(); var first = shortLived.acquire(user); var browser = first.browser();
            browser.navigate(base); shortLived.reap(); assertFalse(browser.isClosed());
            assertThrows(ResponseStatusException.class, () -> shortLived.acquire(user));
            assertThrows(ResponseStatusException.class, () -> shortLived.reset(user));
            first.release(); shortLived.reap(); assertTrue(browser.isClosed());
            var replacement = shortLived.acquire(user); assertNotSame(first, replacement); replacement.release();
        }
    }
    @Test void streamDeadlineReportsErrorReleasesOwnershipAndClosesBrowser() {
        conversations.close(); conversations = new Conversations(manager, Duration.ofMinutes(1), Duration.ofMillis(200), 2);
        var user = new MockHttpSession(); var first = conversations.acquire(user); var browser = first.browser(); browser.navigate(base); first.release();
        var controller = controller(new Model() {
            public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
            public Flux<ChatResponse> stream(Prompt p) { return Flux.never(); }
        });
        var result = controller.stream(new ChatController.ChatRequest("详情呢"), user).collectList().block(Duration.ofSeconds(5));
        assertTrue(result.stream().anyMatch(e -> "error".equals(e.event())));
        assertFalse(result.stream().anyMatch(e -> "done".equals(e.event())));
        assertTrue(browser.isClosed());
        var next = conversations.acquire(user); assertNotSame(browser, next.browser()); next.release();
    }
    @Test void disconnectClosesBrowserAndReleasesConversation() throws Exception {
        var user = new MockHttpSession(); var c = conversations.acquire(user); var browser = c.browser();
        browser.navigate(base); c.release();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var controller = controller(new Model() {
            public ChatResponse call(Prompt p) { throw new UnsupportedOperationException(); }
            public Flux<ChatResponse> stream(Prompt p) { return Flux.defer(() -> { entered.countDown(); return Flux.never(); }); }
        });
        var subscription = controller.stream(new ChatController.ChatRequest("继续"), user).subscribe();
        assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
        subscription.dispose();
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!browser.isClosed() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(browser.isClosed());
        var next = conversations.acquire(user); next.release();
    }
    @Test void deltaEncodingPreservesSpacesAndNewlinesAndErrorsDoNotSavePartialTurns() {
        var user = new MockHttpSession(); String text = "a  b\n\n```java\n  value++;\n```\n";
        var controller = controller(new Model() { public ChatResponse call(Prompt p) { return answer(text); } });
        var result = controller.stream(new ChatController.ChatRequest("格式测试"), user).collectList().block(Duration.ofSeconds(5));
        String actual = result.stream().filter(e -> "delta".equals(e.event())).map(e -> JSON.readValue(e.data(), String.class)).reduce("", String::concat);
        assertEquals(text, actual);
        var state = conversations.acquire(user); assertEquals(2, state.history().size()); state.release();
        var failing = controller(new Model() { public ChatResponse call(Prompt p) { throw new IllegalStateException("fixture"); } });
        var failure = failing.stream(new ChatController.ChatRequest("下一条"), user).collectList().block(Duration.ofSeconds(5));
        assertTrue(failure.stream().anyMatch(e -> "error".equals(e.event())));
        state = conversations.acquire(user); assertEquals(2, state.history().size()); state.release();
    }
}
