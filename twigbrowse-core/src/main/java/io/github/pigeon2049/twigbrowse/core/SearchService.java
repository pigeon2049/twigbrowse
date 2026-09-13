package io.github.pigeon2049.twigbrowse.core;

import java.util.*;
import org.htmlunit.WebClient;

/** Ordered fallback. No shared cookies, cached queries or user-specific circuit-breaker state. */
public final class SearchService {
    private final List<SearchEngine> engines;
    private final BrowserSettings settings;
    private final BrowserProfile profile;
    private final ProxySettings proxy;
    public SearchService(List<SearchEngine> engines, BrowserSettings settings) {
        this(engines, settings, BrowserProfile.defaults());
    }
    public SearchService(List<SearchEngine> engines, BrowserSettings settings, BrowserProfile profile) {
        this(engines, settings, profile, null);
    }
    public SearchService(List<SearchEngine> engines, BrowserSettings settings, BrowserProfile profile, ProxySettings proxy) {
        this.profile = BrowserProfile.defaults().overlay(profile);
        if (engines == null || engines.isEmpty()) throw new IllegalArgumentException("At least one search engine is required");
        Set<String> ids = new HashSet<>();
        for (SearchEngine engine : engines) {
            if (engine.id() == null || engine.id().isBlank() || !ids.add(engine.id()))
                throw new IllegalArgumentException("Search engine IDs must be nonblank and unique");
        }
        this.engines = List.copyOf(engines); this.settings = settings; this.proxy = proxy;
    }
    public SearchResponse search(String query, int limit) { return search(query, limit, profile); }
    public SearchResponse search(String query, int limit, BrowserProfile override) {
        if (query == null || query.isBlank() || query.length() > 500 || limit < 1 || limit > 10)
            throw new BrowserException("INVALID_ARGUMENT", "Query must be 1–500 characters and limit 1–10");
        List<Attempt> attempts = new ArrayList<>();
        for (SearchEngine engine : engines) {
            if (Thread.currentThread().isInterrupted()) throw new BrowserException("CANCELLED", "Search cancelled");
            try (WebClient browser = Browsers.create(settings, false, profile.overlay(override), proxy)) {
                List<SearchEngine.SearchResult> results = engine.search(browser, query, limit);
                if (results == null || results.isEmpty())
                    throw new BrowserException("NO_RESULTS_OR_LAYOUT_CHANGE", "No extractable results");
                attempts.add(new Attempt(engine.id(), "OK"));
                return new SearchResponse(true, engine.id(), List.copyOf(results.subList(0, Math.min(limit, results.size()))), List.copyOf(attempts));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new BrowserException("CANCELLED", "Search cancelled");
            } catch (Exception exception) {
                attempts.add(new Attempt(engine.id(), exception instanceof BrowserException e ? e.code()
                    : exception instanceof java.net.SocketTimeoutException ? "TIMEOUT" : "PROVIDER_UNAVAILABLE"));
            }
        }
        return new SearchResponse(false, null, List.of(), List.copyOf(attempts));
    }
    public record Attempt(String engine, String status) { }
    public record SearchResponse(boolean success, String engine, List<SearchEngine.SearchResult> results, List<Attempt> attempts) { }
}
