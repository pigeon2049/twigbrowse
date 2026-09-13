package io.github.pigeon2049.twigbrowse.autoconfigure;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import io.github.pigeon2049.twigbrowse.core.BrowserSessionManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.*;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.chat.client.autoconfigure.ChatClientAutoConfiguration;
import org.springframework.ai.model.tool.autoconfigure.ToolCallingAutoConfiguration;
import org.springframework.ai.openai.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

/** Real-model workflow against Hacker News. It does not assert today's ranking, only the tool contract and source. */
@EnabledIfEnvironmentVariable(named="TWIGBROWSE_LIVE_TEST", matches="true")
class LiveHackerNewsAgentTest {
    @Test void agentOpensHackerNewsAndSummarizesTodaysStories() {
        String modelName = System.getenv("TWIGBROWSE_TEST_MODEL");
        var options = OpenAiChatOptions.builder().baseUrl(System.getenv("TWIGBROWSE_TEST_BASE_URL"))
            .apiKey(System.getenv("TWIGBROWSE_TEST_API_KEY")).model(modelName)
            .extraBody(modelName.startsWith("deepseek") ? Map.of("thinking", Map.of("type", "disabled")) : Map.of())
            .maxTokens(1300).maxRetries(0).timeout(Duration.ofSeconds(45)).build();
        var delegate = OpenAiChatModel.builder().options(options).build();
        AtomicInteger calls = new AtomicInteger(); Set<String> tools = new HashSet<>();
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() { return delegate.getOptions(); }
            public ChatResponse call(Prompt prompt) {
                if (calls.incrementAndGet() > 9) throw new IllegalStateException("Hacker News test call budget exceeded");
                for (var message : prompt.getInstructions()) if (message instanceof ToolResponseMessage response)
                    response.getResponses().forEach(item -> tools.add(item.name()));
                return delegate.call(prompt);
            }
        };
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class, TwigBrowseAutoConfiguration.class))
            .withBean(ChatModel.class, () -> model).withPropertyValues("twigbrowse.java-script-enabled=false")
            .run(context -> {
                assertNull(context.getStartupFailure());
                String answer = context.getBean(ChatClient.Builder.class).build().prompt()
                    .user("What are the five most interesting stories on Hacker News today? Give me the title, a one-sentence explanation, and the original Hacker News link for each. Start from https://news.ycombinator.com/ and use current page content rather than memory.")
                    .call().content();
                assertTrue(tools.contains("web_navigate"), tools.toString());
                assertTrue(tools.contains("web_read"), tools.toString());
                assertTrue(tools.contains("web_query") || tools.contains("web_snapshot"), tools.toString());
                assertTrue(tools.contains("web_close"), tools.toString());
                assertNotNull(answer); assertFalse(answer.isBlank());
                assertTrue(answer.toLowerCase(Locale.ROOT).contains("hacker") || answer.contains("news.ycombinator.com"), answer);
                TwigBrowseAutoConfigurationTest.awaitEmpty(context.getBean(BrowserSessionManager.class));
                System.out.println("LIVE_HACKER_NEWS calls=" + calls + " tools=" + tools + " answer=" + answer);
            });
    }

    @Test void agentHandlesFollowUpDetailsAndCommentQuestionWithFreshContext() {
        String modelName = System.getenv("TWIGBROWSE_TEST_MODEL");
        var options = OpenAiChatOptions.builder().baseUrl(System.getenv("TWIGBROWSE_TEST_BASE_URL"))
            .apiKey(System.getenv("TWIGBROWSE_TEST_API_KEY")).model(modelName)
            .extraBody(modelName.startsWith("deepseek") ? Map.of("thinking", Map.of("type", "disabled")) : Map.of())
            .maxTokens(1300).maxRetries(0).timeout(Duration.ofSeconds(45)).build();
        var delegate = OpenAiChatModel.builder().options(options).build();
        AtomicInteger calls = new AtomicInteger(); Set<String> tools = new HashSet<>();
        ChatModel model = new ChatModel() {
            public ChatOptions getOptions() { return delegate.getOptions(); }
            public ChatResponse call(Prompt prompt) {
                if (calls.incrementAndGet() > 12) throw new IllegalStateException("Hacker News follow-up call budget exceeded");
                for (var message : prompt.getInstructions()) if (message instanceof ToolResponseMessage response)
                    response.getResponses().forEach(item -> tools.add(item.name()));
                return delegate.call(prompt);
            }
        };
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(
                ToolCallingAutoConfiguration.class, ChatClientAutoConfiguration.class, TwigBrowseAutoConfiguration.class))
            .withBean(ChatModel.class, () -> model).withPropertyValues("twigbrowse.java-script-enabled=false")
            .run(context -> {
                assertNull(context.getStartupFailure());
                ChatClient client = context.getBean(ChatClient.Builder.class).build();
                String summary = client.prompt().user("What are the five most interesting stories on Hacker News today? Give me the title, a one-sentence explanation, and the original Hacker News link for each, based on https://news.ycombinator.com/.").call().content();
                assertNotNull(summary); assertFalse(summary.isBlank());
                String followUp = client.prompt().user("About the first story you just mentioned, could you look at the original article and the Hacker News discussion? Give me two useful details about the story and summarize the main viewpoints in the comments. If there are no comments, say so.\n\nEarlier answer:\n" + summary).call().content();
                assertNotNull(followUp); assertFalse(followUp.isBlank());
                assertTrue(tools.contains("web_navigate"), tools.toString());
                assertTrue(tools.contains("web_query") || tools.contains("web_snapshot"), tools.toString());
                assertTrue(tools.contains("web_read"), tools.toString());
                assertTrue(tools.contains("web_close"), tools.toString());
                TwigBrowseAutoConfigurationTest.awaitEmpty(context.getBean(BrowserSessionManager.class));
                System.out.println("LIVE_HACKER_NEWS_FOLLOWUP calls=" + calls + " tools=" + tools + " summary=" + summary + " followUp=" + followUp);
            });
    }
}
