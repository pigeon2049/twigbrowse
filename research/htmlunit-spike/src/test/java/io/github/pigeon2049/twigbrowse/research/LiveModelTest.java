package io.github.pigeon2049.twigbrowse.research;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.htmlunit.*;
import org.htmlunit.html.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.*;
import org.springframework.ai.tool.annotation.Tool;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit opt-in: sends only a public documentation task to the configured model provider. */
@EnabledIfEnvironmentVariable(named="TWIGBROWSE_LIVE_TEST", matches="true")
class LiveModelTest {
    @Test void modelSearchesAndReadsThroughSpringAi() {
        var options = OpenAiChatOptions.builder()
            .baseUrl(System.getenv("TWIGBROWSE_TEST_BASE_URL"))
            .apiKey(System.getenv("TWIGBROWSE_TEST_API_KEY"))
            .model(System.getenv("TWIGBROWSE_TEST_MODEL"))
            .extraBody(System.getenv("TWIGBROWSE_TEST_MODEL").startsWith("deepseek")
                ? Map.of("thinking", Map.of("type", "disabled")) : Map.of())
            .maxTokens(900).maxRetries(0).timeout(Duration.ofSeconds(45)).build();
        var model = OpenAiChatModel.builder().options(options).build();
        try (WebClient browser = new WebClient(BrowserVersion.CHROME)) {
            browser.getOptions().setJavaScriptEnabled(false);
            browser.getOptions().setCssEnabled(false);
            browser.getOptions().setTimeout(15000);
            var tools = new WebTools(browser);
            String answer = ChatClient.create(model).prompt()
                .user("Search the web for Spring AI official tool calling documentation, then read one docs.spring.io result. Use web_search and web_read. Reply with two short sentences and the source URL. Do not rely on memory.")
                .tools(tools).call().content();
            assertTrue(tools.searches > 0, "Model must call web_search");
            assertTrue(tools.reads > 0, "Model must call web_read");
            assertNotNull(answer);
            assertTrue(answer.contains("docs.spring.io"));
            System.out.println("LIVE searches=" + tools.searches + " reads=" + tools.reads + " answer=" + answer);
        }
    }
    public static class WebTools {
        private final WebClient browser;
        int searches;
        int reads;
        private final Set<String> discovered = new HashSet<>();
        WebTools(WebClient browser) { this.browser = browser; }
        @Tool(name="web_search", description="Search public web pages and return titles and source URLs")
        public List<SearchResult> search(String query) throws Exception {
            if (++searches > 2 || query.length() > 200) throw new IllegalArgumentException("Research request limit");
            HtmlPage page = browser.getPage("https://html.duckduckgo.com/html/?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8));
            List<SearchResult> results = new ArrayList<>();
            for (DomNode n : page.querySelectorAll("a.result__a")) {
                DomElement link = (DomElement)n;
                String url = link.getAttribute("href");
                URI uri = page.getUrl().toURI().resolve(url);
                if (uri.getRawQuery() != null) {
                    for (String pair : uri.getRawQuery().split("&")) {
                        if (pair.startsWith("uddg=")) url = URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8);
                    }
                }
                discovered.add(url);
                results.add(new SearchResult(link.asNormalizedText(), url));
                if (results.size() == 5) break;
            }
            if (results.isEmpty()) throw new IllegalStateException("Search blocked or no extractable results");
            return results;
        }
        @Tool(name="web_read", description="Read an official docs.spring.io URL returned by web_search")
        public String read(String url) throws Exception {
            URI uri = URI.create(url);
            if (++reads > 2 || !discovered.contains(url) || !"https".equals(uri.getScheme()) || !"docs.spring.io".equals(uri.getHost())) {
                throw new IllegalArgumentException("Only discovered official documentation is allowed in this probe");
            }
            HtmlPage page = browser.getPage(url);
            DomNode content = page.querySelector("article");
            String body = (content == null ? page : content).asNormalizedText();
            return page.getUrl() + "\n" + page.getTitleText() + "\n" + body.substring(0, Math.min(body.length(), 6500));
        }
    }
    public record SearchResult(String title, String url) { }
}
