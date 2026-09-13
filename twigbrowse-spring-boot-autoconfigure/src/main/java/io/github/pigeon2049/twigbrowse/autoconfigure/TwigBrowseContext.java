package io.github.pigeon2049.twigbrowse.autoconfigure;

/** Public keys for trusted application overrides passed to ChatClient.toolContext(). */
public final class TwigBrowseContext {
    private TwigBrowseContext() { }
    public static final String BROWSER_PROFILE = "twigbrowse.browserProfile";
    /** Trusted application-owned BrowserSession. The caller owns isolation, serialization and close. */
    public static final String BROWSER_SESSION = "twigbrowse.browserSession";
}
