package io.github.pigeon2049.twigbrowse.autoconfigure;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import io.github.pigeon2049.twigbrowse.core.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

class TwigBrowseAutoConfigurationTest {
    ApplicationContextRunner context(ChatModel model) {
        return new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class, TwigBrowseAutoConfiguration.class))
            .withBean(ChatModel.class, () -> model);
    }
    @Test void defaultsRegisterTwelveToolsPreserveBusinessToolsAndStayLazy() {
        var model = new CaptureModel();
        context(model).run(ctx -> {
            assertNull(ctx.getStartupFailure());
            var manager = ctx.getBean(BrowserSessionManager.class);
            assertEquals(0, manager.activeSessions());
            ctx.getBean(ChatClient.Builder.class).defaultTools(new BusinessTools()).build().prompt("hello").call().content();
            assertEquals(Set.of("web_search", "web_navigate", "web_snapshot", "web_click", "web_type", "web_read", "web_close", "web_query", "web_attribute", "web_select", "web_check", "web_wait", "business_lookup"), model.names);
            assertEquals(0, manager.activeSessions());
            assertNull(((ToolCallingChatOptions) model.getOptions()).getToolContext());
            for (var callback : ToolCallbacks.from(ctx.getBean(TwigBrowseTools.class))) {
                String schema = callback.getToolDefinition().inputSchema();
                assertFalse(schema.contains("ToolContext"));
                assertFalse(schema.contains("session"));
            }
        });
    }
    @Test void disabledStarterDoesNotRegisterToolsOrSessionManager() {
        var model = new CaptureModel();
        context(model).withPropertyValues("twigbrowse.enabled=false").run(ctx -> {
            assertTrue(ctx.getBeansOfType(BrowserSessionManager.class).isEmpty());
            assertTrue(ctx.getBeansOfType(TwigBrowseTools.class).isEmpty());
            ctx.getBean(ChatClient.Builder.class).build().prompt("hello").call().content();
            assertEquals(Set.of(), model.names);
        });
    }
    @Test void customSearchEngineAndToolLoopWorkForCallStreamAndConcurrentRequests() {
        Set<Object> sessions = ConcurrentHashMap.newKeySet();
        ChatModel model = new CaptureModel() {
            @Override public ChatResponse call(Prompt prompt) {
                sessions.add(((ToolCallingChatOptions)prompt.getOptions()).getToolContext().get(TwigBrowseSessionAdvisor.SESSION_KEY));
                if (prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance)) return answer("found");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(
                    new AssistantMessage.ToolCall("search1", "function", "web_search", "{\"query\":\"Spring AI\",\"limit\":3}"))).build())));
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        };
        context(model).withBean(SearchEngine.class, () -> fixtureEngine("fixture"))
            .withPropertyValues("twigbrowse.search.engines=fixture").run(ctx -> {
                var manager = ctx.getBean(BrowserSessionManager.class);
                ChatClient client = ctx.getBean(ChatClient.Builder.class).build();
                assertEquals("found", client.prompt("search").call().content());
                assertEquals(1, sessions.size(), "One session throughout the tool loop");
                assertEquals("found", client.prompt("search").stream().content().collectList().block().get(0));
                assertEquals(2, sessions.size(), "New session for stream");
                var a = CompletableFuture.supplyAsync(() -> client.prompt("search a").call().content());
                var b = CompletableFuture.supplyAsync(() -> client.prompt("search b").call().content());
                assertEquals("found", a.get(10, TimeUnit.SECONDS)); assertEquals("found", b.get(10, TimeUnit.SECONDS));
                assertEquals(4, sessions.size(), "Same ChatClient must isolate parallel calls");
                awaitEmpty(manager);
            });
    }
    @Test void modelFailureAndStreamCancellationCloseSessions() {
        AtomicReference<BrowserSession> last = new AtomicReference<>();
        ChatModel model = new CaptureModel() {
            void acquire(Prompt prompt) {
                BrowserSession session = (BrowserSession)((ToolCallingChatOptions)prompt.getOptions()).getToolContext().get(TwigBrowseSessionAdvisor.SESSION_KEY);
                last.set(session); session.search("fixture", 1);
            }
            @Override public ChatResponse call(Prompt prompt) { acquire(prompt); throw new IllegalStateException("model failed"); }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> { acquire(prompt); return Flux.never(); }); }
        };
        context(model).withBean(SearchEngine.class, () -> fixtureEngine("fixture"))
            .withPropertyValues("twigbrowse.search.engines=fixture").run(ctx -> {
                var manager = ctx.getBean(BrowserSessionManager.class);
                ChatClient client = ctx.getBean(ChatClient.Builder.class).build();
                assertThrows(Exception.class, () -> client.prompt("search").call().content());
                awaitEmpty(manager);
                assertEquals("CLOSED", assertThrows(BrowserException.class, () -> last.get().search("x", 1)).code());
                var subscription = client.prompt("search").stream().content().subscribe();
                long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (manager.activeSessions() == 0 && System.nanoTime() < end) Thread.sleep(10);
                assertEquals(1, manager.activeSessions());
                subscription.dispose(); awaitEmpty(manager);
                assertEquals("CLOSED", assertThrows(BrowserException.class, () -> last.get().search("x", 1)).code());
            });
    }
    @Test void staticBuilderBypassesCustomizationAndMissingSessionIsExplicit() {
        var model = new CaptureModel();
        context(model).run(ctx -> {
            ChatClient.create(model).prompt("hello").call().content();
            assertTrue(model.names.isEmpty());
            assertEquals("MISSING_SESSION", ctx.getBean(TwigBrowseTools.class).search("x", 1, new ToolContext(Map.of())).errorCode());
        });
    }
    @Test void invalidEngineOrderOrResourceLimitsFailAtStartup() {
        for (String property : List.of("twigbrowse.search.engines=unknown", "twigbrowse.search.engines=bing,bing", "twigbrowse.max-sessions=0", "twigbrowse.network-timeout=0ms"))
            context(new CaptureModel()).withPropertyValues(property).run(ctx -> assertNotNull(ctx.getStartupFailure(), property));
    }
    @Test void browserProfileBindsDefaultsAndSupportsRequestOverridesForSearch() {
        Set<String> seen = new HashSet<>();
        var engine = new SearchEngine() {
            public String id() { return "profile"; }
            public List<SearchResult> search(org.htmlunit.WebClient browser, String query, int limit) {
                var version = browser.getBrowserVersion();
                seen.add(version.getUserAgent() + "|" + version.getBrowserLanguage());
                return List.of(new SearchResult("Fixture", "https://example.org", ""));
            }
        };
        var model = new CaptureModel() {
            @Override public ChatResponse call(Prompt prompt) {
                if (prompt.getInstructions().stream().anyMatch(ToolResponseMessage.class::isInstance)) return answer("ok");
                return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(
                    new AssistantMessage.ToolCall("search", "function", "web_search", "{\"query\":\"x\",\"limit\":1}"))).build())));
            }
        };
        context(model).withBean(SearchEngine.class, () -> engine)
            .withPropertyValues("twigbrowse.search.engines=profile", "twigbrowse.browser.user-agent=AppDefault", "twigbrowse.browser.language=en-US")
            .run(ctx -> {
                ChatClient client = ctx.getBean(ChatClient.Builder.class).build();
                client.prompt("x").call().content();
                client.prompt("x").toolContext(Map.of(TwigBrowseContext.BROWSER_PROFILE,
                    BrowserProfile.builder().userAgent("PerRequest").build())).call().content();
                assertEquals(Set.of("AppDefault|en-US", "PerRequest|en-US"), seen);
                client.prompt("x").call().content();
                assertEquals(2, seen.size());
                assertThrows(IllegalArgumentException.class, () -> client.prompt("x")
                    .toolContext(Map.of(TwigBrowseContext.BROWSER_PROFILE, "invalid")).call().content());
            });
    }
    @Test void configuredOrderAndCustomOverrideAreRespected() {
        context(new CaptureModel()).withBean(SearchEngine.class, () -> fixtureEngine("bing"))
            .withPropertyValues("twigbrowse.search.engines=bing").run(ctx -> {
                var response = ctx.getBean(SearchService.class).search("x", 1);
                assertEquals("bing", response.engine()); assertEquals("Fixture", response.results().get(0).title());
            });
    }
    static SearchEngine fixtureEngine(String id) {
        return new SearchEngine() {
            public String id() { return id; }
            public List<SearchResult> search(org.htmlunit.WebClient browser, String query, int limit) {
                return List.of(new SearchResult("Fixture", "https://example.org", "test"));
            }
        };
    }
    static void awaitEmpty(BrowserSessionManager manager) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (manager.activeSessions() != 0 && System.nanoTime() < end) Thread.sleep(10);
        assertEquals(0, manager.activeSessions());
    }
    static ChatResponse answer(String text) { return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content(text).build()))); }
    static class CaptureModel implements ChatModel {
        Set<String> names = new HashSet<>();
        @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        @Override public ChatResponse call(Prompt prompt) {
            names.clear();
            if (prompt.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null)
                options.getToolCallbacks().forEach(t -> names.add(t.getToolDefinition().name()));
            return answer("ok");
        }
    }
    public static class BusinessTools {
        @Tool(name="business_lookup", description="Business fixture") public String lookup() { return "fixture"; }
    }
}
