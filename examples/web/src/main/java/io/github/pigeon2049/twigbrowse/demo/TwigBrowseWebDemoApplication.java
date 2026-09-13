package io.github.pigeon2049.twigbrowse.demo;

import java.time.Duration;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.*;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

@SpringBootApplication
public class TwigBrowseWebDemoApplication {
    public static void main(String[] args) { SpringApplication.run(TwigBrowseWebDemoApplication.class, args); }
    @Bean ChatModel chatModel(Environment env) {
        return OpenAiChatModel.builder().options(OpenAiChatOptions.builder()
            .baseUrl(env.getProperty("spring.ai.openai.base-url", "https://api.deepseek.com"))
            .apiKey(env.getProperty("spring.ai.openai.api-key", ""))
            .model(env.getProperty("spring.ai.openai.chat.options.model", "deepseek-flash"))
            .maxRetries(0).timeout(Duration.ofSeconds(45)).build()).build();
    }
}
