package io.github.pigeon2049.twigbrowse.core;

import java.time.ZoneId;
import java.util.TimeZone;
import org.htmlunit.BrowserVersion;

/** Immutable partial override. Null fields inherit application defaults, then the HtmlUnit preset. */
public record BrowserProfile(Preset preset, String userAgent, String language, String acceptLanguage,
        String platform, String timeZone) {
    public enum Preset { CHROME, EDGE, FIREFOX, FIREFOX_ESR }
    public BrowserProfile {
        for (String value : new String[]{userAgent, language, acceptLanguage, platform, timeZone}) {
            if (value != null && (value.isBlank() || value.length() > 1024 || value.chars().anyMatch(Character::isISOControl)))
                throw new IllegalArgumentException("Browser profile fields must be nonblank, at most 1024 characters and contain no control characters");
        }
        if (timeZone != null) ZoneId.of(timeZone);
    }
    public static BrowserProfile defaults() { return builder().preset(Preset.CHROME).build(); }
    public BrowserProfile overlay(BrowserProfile override) {
        if (override == null) return this;
        return new BrowserProfile(override.preset == null ? preset : override.preset,
            choose(userAgent, override.userAgent), choose(language, override.language),
            choose(acceptLanguage, override.acceptLanguage), choose(platform, override.platform), choose(timeZone, override.timeZone));
    }
    private static String choose(String base, String override) { return override == null ? base : override; }
    public BrowserVersion toBrowserVersion() {
        BrowserVersion base = switch (preset == null ? Preset.CHROME : preset) {
            case CHROME -> BrowserVersion.CHROME;
            case EDGE -> BrowserVersion.EDGE;
            case FIREFOX -> BrowserVersion.FIREFOX;
            case FIREFOX_ESR -> BrowserVersion.FIREFOX_ESR;
        };
        var builder = new BrowserVersion.BrowserVersionBuilder(base);
        if (userAgent != null) builder.setUserAgent(userAgent);
        if (language != null) builder.setBrowserLanguage(language);
        if (acceptLanguage != null) builder.setAcceptLanguageHeader(acceptLanguage);
        if (platform != null) builder.setPlatform(platform);
        if (timeZone != null) builder.setSystemTimezone(TimeZone.getTimeZone(ZoneId.of(timeZone)));
        return builder.build();
    }
    public static Builder builder() { return new Builder(); }
    public static final class Builder {
        private Preset preset;
        private String userAgent, language, acceptLanguage, platform, timeZone;
        public Builder preset(Preset value) { preset = value; return this; }
        public Builder userAgent(String value) { userAgent = value; return this; }
        public Builder language(String value) { language = value; return this; }
        public Builder acceptLanguage(String value) { acceptLanguage = value; return this; }
        public Builder platform(String value) { platform = value; return this; }
        public Builder timeZone(String value) { timeZone = value; return this; }
        public BrowserProfile build() { return new BrowserProfile(preset, userAgent, language, acceptLanguage, platform, timeZone); }
    }
}
