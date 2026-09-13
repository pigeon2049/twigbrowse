package io.github.pigeon2049.twigbrowse.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TWIGBROWSE_LIVE_TEST", matches="true")
class LiveSearchTest {
    @Test void probeEachEngineAndVerifyFallback() {
        for (SearchEngine engine : HtmlSearchEngine.values()) {
            SearchEngine diagnostic = new SearchEngine() {
                public String id() { return engine.id(); }
                public List<SearchResult> search(org.htmlunit.WebClient browser, String query, int limit) throws Exception {
                    try { return engine.search(browser, query, limit); }
                    catch (Exception e) {
                        System.out.println("LIVE_FAILURE " + engine.id() + " type=" + e.getClass().getSimpleName() + " message=" + e.getMessage());
                        throw e;
                    }
                }
            };
            var result = new SearchService(List.of(diagnostic), BrowserSettings.defaults()).search("Spring AI tool calling documentation", 5);
            System.out.println("LIVE_ENGINE " + engine.id() + " success=" + result.success() + " attempts=" + result.attempts() + " results=" + result.results());
        }
        var result = new SearchService(List.of(HtmlSearchEngine.values()), BrowserSettings.defaults()).search("Spring AI tool calling documentation", 5);
        System.out.println("LIVE_FALLBACK " + result);
        assertTrue(result.success(), "At least one configured search engine must work on this network");
    }
}
