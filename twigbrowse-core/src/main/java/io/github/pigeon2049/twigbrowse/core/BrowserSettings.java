package io.github.pigeon2049.twigbrowse.core;

import java.time.Duration;

/** Resource limits are per request, except maxSessions (per application). */
public record BrowserSettings(Duration networkTimeout, Duration scriptTimeout, Duration operationTimeout,
        int maxSessions, int maxPages, int maxTextChars, boolean javaScriptEnabled, boolean allowPrivateNetwork) {
    public BrowserSettings {
        for (Duration value : new Duration[]{networkTimeout, scriptTimeout, operationTimeout}) {
            if (value == null || value.toMillis() < 1 || value.toMillis() > Integer.MAX_VALUE)
                throw new IllegalArgumentException("Timeout must be between 1ms and 2147483647ms");
        }
        if (maxSessions < 1 || maxPages < 1 || maxTextChars < 1)
            throw new IllegalArgumentException("Resource limits must be positive");
    }
    public static BrowserSettings defaults() {
        return new BrowserSettings(Duration.ofSeconds(8), Duration.ofSeconds(2), Duration.ofSeconds(35),
            16, 8, 12000, true, false);
    }
}
