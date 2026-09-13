package io.github.pigeon2049.twigbrowse.example;

import java.time.Duration;
import java.util.*;
import io.github.pigeon2049.twigbrowse.autoconfigure.TwigBrowseContext;
import io.github.pigeon2049.twigbrowse.core.BrowserProfile;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.prompt.*;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.*;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/** Standalone console application. No example classes are dependencies of the library modules. */
@SpringBootApplication
public class BrowserCli {
    public static void main(String[] args) {
        try (var application = SpringApplication.run(BrowserCli.class, args)) { /* closes browser resources */ }
    }
    @Bean ChatModel model(Environment environment) {
        if (environment.getProperty("example.offline", Boolean.class, false)) {
            return new ChatModel() {
                public reactor.core.publisher.Flux<ChatResponse> stream(Prompt prompt) { return reactor.core.publisher.Flux.defer(() -> reactor.core.publisher.Flux.just(call(prompt))); }
                public ChatOptions getOptions() { return ToolCallingChatOptions.builder().build(); }
                public ChatResponse call(Prompt prompt) {
                    var options = (ToolCallingChatOptions) prompt.getOptions();
                    String names = options.getToolCallbacks().stream().map(t -> t.getToolDefinition().name()).sorted().toList().toString();
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("Offline wiring check; available tools: " + names).build())));
                }
            };
        }
        String key = environment.getProperty("example.api-key");
        if (key == null || key.isBlank()) throw new IllegalArgumentException("Set TWIGBROWSE_EXAMPLE_API_KEY, or run with --example.offline=true");
        String name = environment.getProperty("example.model", "deepseek-flash");
        return OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
            .baseUrl(environment.getProperty("example.base-url", "https://api.deepseek.com"))
            .apiKey(key).model(name).maxRetries(0).timeout(Duration.ofSeconds(45))
            .extraBody(name.startsWith("deepseek") ? Map.of("thinking", Map.of("type", "disabled")) : Map.of())
            .build()).build();
    }
    @Bean ApplicationRunner browse(ChatClient.Builder builder, Environment environment) {
        return args -> {
            ChatClient client = builder.build(); // TwigBrowse tools are already installed.
            String prompt = args.getNonOptionArgs().isEmpty()
                ? scenarioPrompt(environment.getProperty("example.scenario", "spring-ai"))
                : String.join(" ", args.getNonOptionArgs());
            var request = client.prompt().user(prompt);
            String language = environment.getProperty("example.language");
            if (language != null) request.toolContext(Map.of(TwigBrowseContext.BROWSER_PROFILE,
                BrowserProfile.builder().language(language).acceptLanguage(language).build()));
            if (environment.getProperty("example.stream", Boolean.class, false)) {
                request.stream().content().doOnNext(System.out::print).blockLast();
                System.out.println();
            } else System.out.println(request.call().content());
        };
    }
    private static String scenarioPrompt(String scenario) {
        return switch (scenario) {
            case "hacker-news", "hn-summary" -> "I missed Hacker News today. Could you catch me up on the five stories getting the most attention right now? For each one, give me the headline, a plain-English explanation of why it matters, and a link so I can read more.";
            case "hacker-news-zh", "hn-summary-zh" -> "帮我总结一下今天 Hacker News 上都在说什么，具体有哪些值得关注的内容？请列出主要文章，分别用几句话说明文章讲了什么、为什么值得关注，并附上原文链接。";
            case "hacker-news-follow-up", "hn-follow-up" -> "I'm interested in the first story on Hacker News right now. Please read the original article and the discussion, then tell me what the article actually says and where the commenters agree or disagree. Keep facts from the article separate from opinions in the comments, and include the link.";
            case "hacker-news-follow-up-zh", "hn-follow-up-zh" -> "刚才提到的第一篇具体内容是什么？原文的主要观点有哪些，评论区大家又在讨论什么？请把文章事实和评论观点分开说，并附上原文链接。";
            default -> "I'm integrating Spring AI and want to understand tool calling. Find the official documentation and explain the basic flow, with a link to the relevant page.";
        };
    }
}
