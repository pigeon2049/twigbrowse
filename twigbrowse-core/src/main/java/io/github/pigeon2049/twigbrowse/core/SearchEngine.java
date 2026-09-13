package io.github.pigeon2049.twigbrowse.core;

import java.util.List;
import org.htmlunit.WebClient;

/** Implementations must be stateless/thread safe. The supplied browser belongs only to this attempt. */
public interface SearchEngine {
    String id();
    List<SearchResult> search(WebClient browser, String query, int limit) throws Exception;
    record SearchResult(String title, String url, String snippet) { }
}
