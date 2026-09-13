package io.github.pigeon2049.twigbrowse.demo;

import java.util.*;
import java.util.concurrent.atomic.*;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.client.advisor.ToolCallingAdvisor;
import org.springframework.ai.chat.client.advisor.api.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.*;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import reactor.core.publisher.Flux;
import tools.jackson.databind.json.JsonMapper;

/** Request-local instrumentation and complete tool history, retained only by its owning conversation. */
final class Turn {
    volatile List<Message> latest = List.of();
    volatile String status = "正在思考";
    final String id = UUID.randomUUID().toString().substring(0, 8);
    final AtomicInteger calls = new AtomicInteger();
    final StringBuilder answer = new StringBuilder();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    final class History implements CallAdvisor, StreamAdvisor {
        public String getName() { return "DemoToolHistory"; }
        public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER + 1; }
        private void capture(ChatClientRequest request) { latest = List.copyOf(request.prompt().getInstructions()); }
        public ChatClientResponse adviseCall(ChatClientRequest r, CallAdvisorChain c) { capture(r); return c.nextCall(r); }
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest r, StreamAdvisorChain c) { capture(r); return c.nextStream(r); }
    }
    final class Trace implements CallAdvisor, StreamAdvisor {
        public String getName() { return "DemoBrowserProgress"; }
        public int getOrder() { return ToolCallingAdvisor.DEFAULT_ORDER - 2; }
        private ChatClientRequest wrap(ChatClientRequest r) {
            var options = (ToolCallingChatOptions) r.prompt().getOptions();
            var callbacks = options.getToolCallbacks().stream().map(delegate -> (ToolCallback) new ToolCallback() {
                public ToolDefinition getToolDefinition() { return delegate.getToolDefinition(); }
                public ToolMetadata getToolMetadata() { return delegate.getToolMetadata(); }
                public String call(String input) { return call(input, new ToolContext(Map.of())); }
                public String call(String input, ToolContext context) {
                    if (calls.incrementAndGet() > 48) throw new IllegalStateException("本轮浏览操作过多，请缩小问题范围");
                    String name = getToolDefinition().name();
                    status = switch (name) {
                        case "web_search" -> "正在搜索";
                        case "web_click", "web_navigate" -> "正在打开页面";
                        case "web_read" -> "正在阅读内容";
                        default -> "正在查看页面";
                    };
                    long start = System.nanoTime();
                    String outcome = "ERROR";
                    try {
                        String result = delegate.call(input, context);
                        var node = JSON.readTree(result);
                        outcome = node.path("success").asBoolean() ? "OK" : node.path("errorCode").asText("FAILED");
                        return result;
                    } finally {
                        LoggerFactory.getLogger(Turn.class).info("turn={} tool={} result={} elapsedMs={}",
                            id, name, outcome, (System.nanoTime() - start) / 1_000_000);
                        status = "正在整理已获取的内容";
                    }
                }
            }).toList();
            return r.mutate().prompt(new Prompt(r.prompt().getInstructions(), options.mutate().toolCallbacks(callbacks).build())).build();
        }
        public ChatClientResponse adviseCall(ChatClientRequest r, CallAdvisorChain c) { return c.nextCall(wrap(r)); }
        public Flux<ChatClientResponse> adviseStream(ChatClientRequest r, StreamAdvisorChain c) { return c.nextStream(wrap(r)); }
    }
}
