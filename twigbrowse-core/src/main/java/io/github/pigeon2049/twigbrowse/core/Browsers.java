package io.github.pigeon2049.twigbrowse.core;

import java.io.IOException;
import org.htmlunit.*;
import org.htmlunit.util.WebConnectionWrapper;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;

final class Browsers {
    private Browsers() { }
    static WebClient create(BrowserSettings settings, boolean javascript, BrowserProfile profile) {
        return create(settings, javascript, profile, null);
    }
    static WebClient create(BrowserSettings settings, boolean javascript, BrowserProfile profile, ProxySettings proxy) {
        WebClient client = new WebClient(profile.toBrowserVersion());
        var options = client.getOptions();
        options.setJavaScriptEnabled(javascript);
        options.setFetchPolyfillEnabled(javascript);
        options.setCssEnabled(false);
        options.setDownloadImages(false);
        options.setWebSocketEnabled(false);
        options.setGeolocationEnabled(false);
        options.setPopupBlockerEnabled(true);
        options.setFileProtocolForXMLHttpRequestsAllowed(false);
        options.setThrowExceptionOnScriptError(false);
        options.setPrintContentOnFailingStatusCode(false);
        options.setTimeout((int) settings.networkTimeout().toMillis());
        options.setHistorySizeLimit(2);
        options.setHistoryPageCacheLimit(1);
        options.setPageRefreshLimit(0);
        if (proxy != null) {
            options.setProxyConfig(new ProxyConfig(proxy.host(), proxy.port(), proxy.socks() ? "socks" : "http", proxy.socks()));
            if (proxy.username() != null) {
                var provider = new BasicCredentialsProvider();
                provider.setCredentials(new AuthScope(proxy.host(), proxy.port()), new UsernamePasswordCredentials(proxy.username(), proxy.password() == null ? "" : proxy.password()));
                client.setCredentialsProvider(provider);
            }
        }
        client.setJavaScriptTimeout(settings.scriptTimeout().toMillis());
        UrlPolicy policy = new UrlPolicy(settings.allowPrivateNetwork());
        new WebConnectionWrapper(client) {
            @Override public WebResponse getResponse(WebRequest request) throws IOException {
                policy.check(request.getUrl());
                WebResponse response = super.getResponse(request);
                if (response.getContentLength() > 4 * 1024 * 1024) {
                    response.cleanUp();
                    throw new IOException("Response exceeds 4 MiB parsing limit");
                }
                return response;
            }
        };
        return client;
    }
}
