package io.github.pigeon2049.twigbrowse.core;

import java.net.*;
import java.io.IOException;

/** Defense in depth; DNS is checked again for each HTTP request, but not pinned to the socket. */
public final class UrlPolicy {
    private final boolean allowPrivate;
    public UrlPolicy(boolean allowPrivate) { this.allowPrivate = allowPrivate; }
    public void check(URL url) throws IOException {
        if (!("https".equals(url.getProtocol()) || "http".equals(url.getProtocol()))
                || url.getUserInfo() != null || url.getHost().isBlank())
            throw new IOException("Only HTTP(S) URLs without credentials are allowed");
        if (!allowPrivate) {
            for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
                if (!isPublic(address)) throw new IOException("Private or reserved network address blocked");
            }
        }
    }
    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] b = address.getAddress();
        if (b.length == 4) {
            int a = b[0] & 255, c = b[1] & 255, d = b[2] & 255;
            return !(a == 0 || a >= 224 || a == 100 && c >= 64 && c <= 127
                || a == 192 && (c == 0 || c == 168 || c == 88 && d == 99)
                || a == 198 && (c == 18 || c == 19 || c == 51 && d == 100)
                || a == 203 && c == 0 && d == 113);
        }
        // Only global unicast; exclude documentation, Teredo and 6to4 transition ranges.
        return (b[0] & 0xe0) == 0x20 && !((b[0] & 255) == 0x20 && (b[1] & 255) == 1
            && ((b[2] & 255) < 2 || (b[2] & 255) == 0x0d && (b[3] & 255) == 0xb8))
            && !((b[0] & 255) == 0x20 && (b[1] & 255) == 2);
    }
}
