package io.github.pigeon2049.twigbrowse.core;

import java.net.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserBoundaryTest {
    @Test void urlPolicyRejectsUnsafeSchemesCredentialsAndPrivateRanges() throws Exception {
        UrlPolicy policy = new UrlPolicy(false);
        for (String value : List.of("javascript:alert(1)", "data:text/html,x", "jar:file:/tmp/x",
                "http://user:pass@example.org", "http://10.0.0.1", "http://172.16.0.1",
                "http://192.168.1.1", "http://[::1]", "http://[fc00::1]"))
            assertThrows(Exception.class, () -> policy.check(new URL(value)), value);
        assertTrue(UrlPolicy.isPublic(InetAddress.getByName("1.1.1.1")));
        assertFalse(UrlPolicy.isPublic(InetAddress.getByName("127.0.0.1")));
    }

    @Test void browserSettingsRejectInvalidLimitsAndTimeouts() {
        assertThrows(IllegalArgumentException.class, () -> new BrowserSettings(Duration.ZERO, Duration.ofMillis(1), Duration.ofMillis(1), 1, 1, 1, true, false));
        assertThrows(IllegalArgumentException.class, () -> new BrowserSettings(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1), 0, 1, 1, true, false));
        assertThrows(IllegalArgumentException.class, () -> new BrowserSettings(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1), 1, 0, 1, true, false));
        assertThrows(IllegalArgumentException.class, () -> new BrowserSettings(Duration.ofMillis(1), Duration.ofMillis(1), Duration.ofMillis(1), 1, 1, 0, true, false));
        assertThrows(IllegalArgumentException.class, () -> new BrowserSettings(Duration.ofDays(30), Duration.ofMillis(1), Duration.ofMillis(1), 1, 1, 1, true, false));
    }

    @Test void searchServiceRejectsNullAndDuplicateEngines() {
        assertThrows(IllegalArgumentException.class, () -> new SearchService(null, BrowserSettings.defaults()));
        SearchEngine duplicate = new SearchEngine() {
            public String id() { return "same"; }
            public List<SearchResult> search(org.htmlunit.WebClient b, String q, int l) { return List.of(); }
        };
        assertThrows(IllegalArgumentException.class, () -> new SearchService(List.of(duplicate, duplicate), BrowserSettings.defaults()));
    }
}
