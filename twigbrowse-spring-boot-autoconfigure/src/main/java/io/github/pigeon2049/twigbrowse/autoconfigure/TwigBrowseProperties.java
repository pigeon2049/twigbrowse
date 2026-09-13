package io.github.pigeon2049.twigbrowse.autoconfigure;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import io.github.pigeon2049.twigbrowse.core.BrowserSettings;
import io.github.pigeon2049.twigbrowse.core.BrowserProfile;
import io.github.pigeon2049.twigbrowse.core.ProxySettings;

@ConfigurationProperties("twigbrowse")
public class TwigBrowseProperties {
    private boolean enabled = true;
    private Duration networkTimeout = Duration.ofSeconds(8);
    private Duration scriptTimeout = Duration.ofSeconds(2);
    private Duration operationTimeout = Duration.ofSeconds(35);
    private Duration sessionIdleTimeout = Duration.ofMinutes(5);
    private int maxSessions = 16, maxPages = 8, maxTextChars = 12000;
    private boolean javaScriptEnabled = true, allowPrivateNetwork;
    private final Search search = new Search();
    private final Browser browser = new Browser();
    private final Proxy proxy = new Proxy();
    public Browser getBrowser() { return browser; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public Duration getNetworkTimeout() { return networkTimeout; }
    public void setNetworkTimeout(Duration value) { networkTimeout = value; }
    public Duration getScriptTimeout() { return scriptTimeout; }
    public void setScriptTimeout(Duration value) { scriptTimeout = value; }
    public Duration getOperationTimeout() { return operationTimeout; }
    public void setOperationTimeout(Duration value) { operationTimeout = value; }
    public Duration getSessionIdleTimeout() { return sessionIdleTimeout; }
    public void setSessionIdleTimeout(Duration value) { sessionIdleTimeout = value; }
    public int getMaxSessions() { return maxSessions; }
    public void setMaxSessions(int value) { maxSessions = value; }
    public int getMaxPages() { return maxPages; }
    public void setMaxPages(int value) { maxPages = value; }
    public int getMaxTextChars() { return maxTextChars; }
    public void setMaxTextChars(int value) { maxTextChars = value; }
    public boolean isJavaScriptEnabled() { return javaScriptEnabled; }
    public void setJavaScriptEnabled(boolean value) { javaScriptEnabled = value; }
    public boolean isAllowPrivateNetwork() { return allowPrivateNetwork; }
    public void setAllowPrivateNetwork(boolean value) { allowPrivateNetwork = value; }
    public Search getSearch() { return search; }
    public Proxy getProxy() { return proxy; }
    public ProxySettings proxySettings() {
        return proxy.isEnabled() ? new ProxySettings(proxy.getHost(), proxy.getPort(), proxy.getUsername(), proxy.getPassword(), proxy.isSocks()) : null;
    }
    public BrowserSettings browserSettings() {
        return new BrowserSettings(networkTimeout, scriptTimeout, operationTimeout,
            maxSessions, maxPages, maxTextChars, javaScriptEnabled, allowPrivateNetwork);
    }
    public static class Browser {
        private BrowserProfile.Preset preset = BrowserProfile.Preset.CHROME;
        private String userAgent, language, acceptLanguage, platform, timeZone;
        public BrowserProfile.Preset getPreset() { return preset; }
        public void setPreset(BrowserProfile.Preset value) { preset = value; }
        public String getUserAgent() { return userAgent; }
        public void setUserAgent(String value) { userAgent = value; }
        public String getLanguage() { return language; }
        public void setLanguage(String value) { language = value; }
        public String getAcceptLanguage() { return acceptLanguage; }
        public void setAcceptLanguage(String value) { acceptLanguage = value; }
        public String getPlatform() { return platform; }
        public void setPlatform(String value) { platform = value; }
        public String getTimeZone() { return timeZone; }
        public void setTimeZone(String value) { timeZone = value; }
        public BrowserProfile profile() { return new BrowserProfile(preset, userAgent, language, acceptLanguage, platform, timeZone); }
    }
    public static class Search {
        private List<String> engines = List.of("bing", "duckduckgo");
        public List<String> getEngines() { return engines; }
        public void setEngines(List<String> value) { engines = value; }
    }
    public static class Proxy {
        private boolean enabled;
        private String host;
        private int port = 8080;
        private String username;
        private String password;
        private boolean socks;
        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getHost() { return host; }
        public void setHost(String value) { host = value; }
        public int getPort() { return port; }
        public void setPort(int value) { port = value; }
        public String getUsername() { return username; }
        public void setUsername(String value) { username = value; }
        public String getPassword() { return password; }
        public void setPassword(String value) { password = value; }
        public boolean isSocks() { return socks; }
        public void setSocks(boolean value) { socks = value; }
    }
}
