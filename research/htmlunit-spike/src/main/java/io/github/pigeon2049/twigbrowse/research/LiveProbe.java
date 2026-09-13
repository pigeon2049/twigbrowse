package io.github.pigeon2049.twigbrowse.research;

import java.util.logging.*;
import org.htmlunit.*;
import org.htmlunit.html.*;

/** Research probe only: arbitrary external pages must not be exposed as a production tool. */
public class LiveProbe {
    public static void main(String[] args) {
        Logger.getLogger("org.htmlunit").setLevel(Level.OFF);
        for (String url : args) {
            long start = System.nanoTime();
            try (WebClient c = new WebClient(BrowserVersion.CHROME)) {
                c.getOptions().setTimeout(15000);
                c.getOptions().setCssEnabled(false);
                c.getOptions().setJavaScriptEnabled(Boolean.getBoolean("probe.javascript"));
                c.getOptions().setThrowExceptionOnScriptError(false);
                c.getOptions().setThrowExceptionOnFailingStatusCode(false);
                c.setJavaScriptTimeout(2000);
                Page loaded = c.getPage(url);
                if (!(loaded instanceof HtmlPage page)) {
                    System.out.println("RESULT url=" + url + " status=" + loaded.getWebResponse().getStatusCode() + " type=" + loaded.getWebResponse().getContentType());
                    continue;
                }
                c.waitForBackgroundJavaScript(1500);
                String text = page.asNormalizedText();
                System.out.println("RESULT url=" + url + " final=" + page.getUrl() + " status=" + page.getWebResponse().getStatusCode() + " title=" + page.getTitleText() + " chars=" + text.length() + " anchors=" + page.getAnchors().size() + " seconds=" + ((System.nanoTime()-start)/1_000_000_000.0));
                String selector = url.contains("duckduckgo") ? "a.result__a" : url.contains("bing.com/search") ? "li.b_algo h2 a" : "main a[href]";
                var links = page.querySelectorAll(selector);
                System.out.println("RESULT matchedLinks=" + links.size());
                for (int i=0; i<Math.min(3,links.size()); i++) {
                    DomElement link = (DomElement) links.get(i);
                    System.out.println("LINK " + link.asNormalizedText() + " " + link.getAttribute("href"));
                }
            } catch (Exception e) { System.out.println("RESULT url=" + url + " error=" + e.getClass().getSimpleName() + " message=" + e.getMessage()); }
        }
    }
}
