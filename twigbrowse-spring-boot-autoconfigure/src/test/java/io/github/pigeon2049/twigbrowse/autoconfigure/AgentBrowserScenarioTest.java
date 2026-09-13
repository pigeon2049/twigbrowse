package io.github.pigeon2049.twigbrowse.autoconfigure;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import io.github.pigeon2049.twigbrowse.core.BrowserSessionManager;
import org.junit.jupiter.api.*;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import static org.junit.jupiter.api.Assertions.*;

/** Executes actual tool JSON/schema/dispatch and HtmlUnit, using a deterministic model. */
class AgentBrowserScenarioTest {
    HttpServer server;
    String base;
    @BeforeEach void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String raw = exchange.getRequestURI().getRawQuery();
            String query = raw == null ? "" : URLDecoder.decode(raw, StandardCharsets.UTF_8);
            byte[] bytes = ("<html><body><article>submitted: " + query + "</article><form action='/result'><input name='q'><button id='submit'>Submit</button></form></body></html>").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start(); base = "http://127.0.0.1:" + server.getAddress().getPort();
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void agentNavigatesQueriesTypesSubmitsAndReadsInCallAndStream() {
        var model = new TwigBrowseAutoConfigurationTest.CaptureModel() {
            @Override public ChatResponse call(Prompt prompt) {
                var responses = responses(prompt);
                String last = responses.isEmpty() ? "" : responses.get(responses.size() - 1).responseData();
                if (!last.isEmpty()) assertFalse(last.contains("\"success\":false"), last);
                return switch (responses.size()) {
                    case 0 -> tool("web_navigate", "{\"url\":\"" + base + "\"}");
                    case 1 -> tool("web_query", "{\"pageId\":\"" + field(last, "pageId") + "\",\"selector\":\"input[name=q]\"}");
                    case 2 -> tool("web_type", "{\"pageId\":\"" + field(last, "pageId") + "\",\"ref\":\"" + field(last, "ref") + "\",\"text\":\"TwigBrowse\"}");
                    case 3 -> tool("web_query", "{\"pageId\":\"" + field(last, "pageId") + "\",\"selector\":\"#submit\"}");
                    case 4 -> tool("web_click", "{\"pageId\":\"" + field(last, "pageId") + "\",\"ref\":\"" + field(last, "ref") + "\"}");
                    case 5 -> tool("web_read", "{\"pageId\":\"" + field(last, "pageId") + "\"}");
                    default -> { assertTrue(last.contains("submitted: q=TwigBrowse"), last); yield TwigBrowseAutoConfigurationTest.answer("submitted"); }
                };
            }
            @Override public Flux<ChatResponse> stream(Prompt prompt) { return Flux.defer(() -> Flux.just(call(prompt))); }
        };
        new TwigBrowseAutoConfigurationTest().context(model).withPropertyValues("twigbrowse.allow-private-network=true")
            .run(ctx -> {
                ChatClient client = ctx.getBean(ChatClient.Builder.class).build();
                assertEquals("submitted", client.prompt("Fill the local test form").call().content());
                assertEquals("submitted", String.join("", client.prompt("Fill the local test form").stream().content().collectList().block()));
                TwigBrowseAutoConfigurationTest.awaitEmpty(ctx.getBean(BrowserSessionManager.class));
            });
    }
    @Test void invalidPageProducesToolErrorAndAgentCanRecoverWithinSameRequest() {
        var model = new TwigBrowseAutoConfigurationTest.CaptureModel() {
            @Override public ChatResponse call(Prompt prompt) {
                var responses = responses(prompt);
                return switch (responses.size()) {
                    case 0 -> tool("web_navigate", "{\"url\":\"" + base + "\"}");
                    case 1 -> tool("web_read", "{\"pageId\":\"another-user-page\"}");
                    case 2 -> {
                        assertTrue(responses.get(1).responseData().contains("UNKNOWN_PAGE"));
                        yield tool("web_read", "{\"pageId\":\"" + field(responses.get(0).responseData(), "pageId") + "\"}");
                    }
                    default -> {
                        assertTrue(responses.get(2).responseData().contains("submitted:"));
                        yield TwigBrowseAutoConfigurationTest.answer("recovered");
                    }
                };
            }
        };
        new TwigBrowseAutoConfigurationTest().context(model).withPropertyValues("twigbrowse.allow-private-network=true")
            .run(ctx -> assertEquals("recovered", ctx.getBean(ChatClient.Builder.class).build().prompt("Read").call().content()));
    }
    static List<ToolResponseMessage.ToolResponse> responses(Prompt prompt) {
        return prompt.getInstructions().stream().filter(ToolResponseMessage.class::isInstance)
            .map(ToolResponseMessage.class::cast).flatMap(message -> message.getResponses().stream()).toList();
    }
    static String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find(), "Missing " + name + " in tool response"); return matcher.group(1);
    }
    static ChatResponse tool(String name, String arguments) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder().toolCalls(List.of(
            new AssistantMessage.ToolCall(UUID.randomUUID().toString(), "function", name, arguments))).build())));
    }
}
