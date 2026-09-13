package io.github.pigeon2049.twigbrowse.research;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class WiringStyleTest {
    @Test void defaultRegistrationPreservesUserTools() {
        CaptureModel model = new CaptureModel();
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
            .withBean(ChatModel.class, () -> model)
            .withBean(ChatClientBuilderCustomizer.class, () -> builder -> builder.defaultTools(new BrowserTools()))
            .run(context -> {
                assertNull(context.getStartupFailure());
                ChatClient client = context.getBean(ChatClient.Builder.class).defaultTools(new BusinessTools()).build();
                client.prompt("List available tools").call().content();
                assertEquals(Set.of("web_search", "business_lookup"), model.toolNames());
            });
    }
    @Test void manuallyCreatedBuilderBypassesGlobalCustomizer() {
        CaptureModel model = new CaptureModel();
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class))
            .withBean(ChatModel.class, () -> model)
            .withBean(ChatClientBuilderCustomizer.class, () -> builder -> builder.defaultTools(new BrowserTools()))
            .run(context -> {
                ChatClient.builder(context.getBean(ChatModel.class)).build().prompt("Hello").call().content();
                assertEquals(Set.of(), model.toolNames());
            });
    }
    @Test void clientMutationSelectsOneClientWithoutChangingSharedModelOrSibling() {
        CaptureModel model = new CaptureModel();
        ChatClient original = ChatClient.builder(model).defaultTools(new BusinessTools()).build();
        ChatClient enhanced = original.mutate().defaultTools(new BrowserTools()).build();
        enhanced.prompt("Hello").call().content();
        assertEquals(Set.of("web_search", "business_lookup"), model.toolNames());
        original.prompt("Hello").call().content();
        assertEquals(Set.of("business_lookup"), model.toolNames());
        assertNull(((ToolCallingChatOptions)model.getOptions()).getToolCallbacks());
    }
    static class CaptureModel implements ChatModel {
        Prompt last;
        @Override public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
        @Override public ChatResponse call(Prompt prompt) {
            last = prompt;
            return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("ok").build())));
        }
        Set<String> toolNames() {
            Set<String> names = new HashSet<>();
            if (last.getOptions() instanceof ToolCallingChatOptions options && options.getToolCallbacks() != null) {
                options.getToolCallbacks().forEach(t -> names.add(t.getToolDefinition().name()));
            }
            return names;
        }
    }
    public static class BrowserTools {
        @Tool(name="web_search", description="Search fixture") public String search(String query) { return query; }
    }
    public static class BusinessTools {
        @Tool(name="business_lookup", description="Business fixture") public String lookup() { return "fixture"; }
    }
}
