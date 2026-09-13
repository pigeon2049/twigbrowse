package io.github.pigeon2049.twigbrowse.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProxySettingsTest {
    @Test void validatesProxyEndpointAndCredentials() {
        assertEquals(3128, new ProxySettings("proxy.example", 3128, "u", "p", false).port());
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.http("", 8080));
        assertThrows(IllegalArgumentException.class, () -> ProxySettings.http("proxy", 0));
        assertThrows(IllegalArgumentException.class, () -> new ProxySettings("proxy", 8080, "u\n", "p", false));
    }

    @Test void appliesHttpProxyToHtmlUnitWithoutExposingPassword() {
        ProxySettings proxy = new ProxySettings("127.0.0.1", 18080, "alice", "secret", false);
        try (var client = Browsers.create(BrowserSettings.defaults(), false, BrowserProfile.defaults(), proxy)) {
            assertEquals("127.0.0.1", client.getOptions().getProxyConfig().getProxyHost());
            assertEquals(18080, client.getOptions().getProxyConfig().getProxyPort());
            assertFalse(proxy.toString().contains("secret"));
        }
    }
}
