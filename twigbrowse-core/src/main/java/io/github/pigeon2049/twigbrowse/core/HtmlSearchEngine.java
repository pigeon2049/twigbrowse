package io.github.pigeon2049.twigbrowse.core;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.htmlunit.WebClient;
import org.htmlunit.html.*;

/** HTML adapters need no API key. Markup changes and anti-bot pages are treated as failures. */
public enum HtmlSearchEngine implements SearchEngine {
    BING("bing", "https://cn.bing.com/search?q=", "li.b_algo h2 a", "li.b_algo"),
    DUCKDUCKGO("duckduckgo", "https://html.duckduckgo.com/html/?q=", "a.result__a", ".result");

    private final String id, endpoint, links, container;
    HtmlSearchEngine(String id, String endpoint, String links, String container) {
        this.id = id; this.endpoint = endpoint; this.links = links; this.container = container;
    }
    @Override public String id() { return id; }
    @Override public List<SearchResult> search(WebClient browser, String query, int limit) throws Exception {
        HtmlPage page = browser.getPage(endpoint + URLEncoder.encode(query, StandardCharsets.UTF_8));
        if (page.getWebResponse().getStatusCode() != 200)
            throw new BrowserException("HTTP_ERROR", "Search provider returned a non-200 response");
        return parse(page, limit);
    }
    List<SearchResult> parse(HtmlPage page, int limit) {
        if (page.querySelector("#anomaly-modal, form#challenge-form, #b_captcha, #captcha") != null)
            throw new BrowserException("CHALLENGE", "Search provider requires human verification");
        Map<String, SearchResult> results = new LinkedHashMap<>();
        for (DomNode node : page.querySelectorAll(links)) {
            DomElement link = (DomElement) node;
            try {
                URI uri = page.getUrl().toURI().resolve(link.getAttribute("href"));
                String raw = uri.getRawQuery();
                if (this == DUCKDUCKGO && raw != null) {
                    for (String pair : raw.split("&")) {
                        if (pair.startsWith("uddg=")) uri = URI.create(URLDecoder.decode(pair.substring(5), StandardCharsets.UTF_8));
                    }
                }
                if (this == BING && "/ck/a".equals(uri.getPath()) && raw != null) {
                    for (String pair : raw.split("&")) {
                        if (pair.startsWith("u=a1")) uri = URI.create(new String(Base64.getUrlDecoder()
                            .decode(URLDecoder.decode(pair.substring(4), StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
                    }
                }
                if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                        || uri.getHost() == null || uri.getUserInfo() != null) continue;
                DomElement parent = link;
                while (parent.getParentNode() instanceof DomElement element) {
                    parent = element;
                    if (parent.matches(container)) break;
                }
                DomNode snippet = parent.querySelector(".c-abstract, .content-right_8Zs40, .b_caption p, .result__snippet");
                String title = BrowserSession.truncate(link.asNormalizedText(), 300);
                if (!title.isBlank()) results.putIfAbsent(uri.toString(), new SearchResult(title, uri.toString(),
                    snippet == null ? "" : BrowserSession.truncate(snippet.asNormalizedText(), 500)));
                if (results.size() >= limit) break;
            } catch (IllegalArgumentException | URISyntaxException ignored) {
                // Ignore malformed individual links, never return executable or credential-bearing URLs.
            }
        }
        if (results.isEmpty()) throw new BrowserException("NO_RESULTS_OR_LAYOUT_CHANGE",
            "No extractable results; provider may be blocked, empty, or have changed markup");
        return List.copyOf(results.values());
    }
}
