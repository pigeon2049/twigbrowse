package io.github.pigeon2049.twigbrowse.core;

/** Immutable outbound proxy configuration. Credentials are kept out of diagnostic output. */
public record ProxySettings(String host, int port, String username, String password, boolean socks) {
    public ProxySettings {
        if (host == null || host.isBlank() || host.length() > 253 || host.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Proxy host must be a non-blank hostname");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Proxy port must be 1–65535");
        if (username != null && username.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid proxy username");
        if (password != null && password.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid proxy password");
    }
    public static ProxySettings http(String host, int port) { return new ProxySettings(host, port, null, null, false); }
    @Override public String toString() { return "ProxySettings[host=" + host + ", port=" + port + ", username=" + username + ", socks=" + socks + "]"; }
}
