package io.github.pigeon2049.twigbrowse.core;

import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import org.htmlunit.*;
import org.htmlunit.html.*;

/** All browser operations and cleanup run on this session's dedicated worker. */
public final class BrowserSession implements AutoCloseable {
    private final BrowserSessionManager manager;
    private final BrowserSettings settings;
    private final SearchService search;
    private final BrowserProfile profile;
    private final ProxySettings proxy;
    private final Map<String, PageState> pages = new LinkedHashMap<>();
    private ThreadPoolExecutor worker;
    private WebClient browser;
    private boolean closed;
    private int operations;
    private volatile long lastAccessNanos = System.nanoTime();
    BrowserSession(BrowserSessionManager manager, BrowserSettings settings, SearchService search, BrowserProfile profile, ProxySettings proxy) {
        this.manager = manager; this.settings = settings; this.search = search; this.profile = profile; this.proxy = proxy;
    }
    public SearchService.SearchResponse search(String query, int limit) { return execute(() -> search.search(query, limit, profile)); }
    public Snapshot navigate(String url) {
        return execute(() -> {
            if (pages.size() >= settings.maxPages()) throw new BrowserException("PAGE_LIMIT", "Close a page before opening another");
            URL target = new URL(url);
            new UrlPolicy(settings.allowPrivateNetwork()).check(target);
            WebClient client = browser();
            String id = UUID.randomUUID().toString();
            WebWindow window = client.openWindow(null, id);
            try {
                client.getPage(window, new WebRequest(target));
                PageState state = new PageState(window);
                pages.put(id, state);
                return snapshotOnWorker(id, state);
            } catch (Exception e) { closeWindow(window); pages.remove(id); throw e; }
        });
    }
    public Snapshot snapshot(String pageId) { return execute(() -> snapshotOnWorker(pageId, page(pageId))); }
    public DomResult query(String pageId, String selector) {
        return execute(() -> queryOnWorker(pageId, selector));
    }
    public DomResult waitFor(String pageId, String selector, int timeoutMillis) {
        if (timeoutMillis < 1 || timeoutMillis > 5000)
            throw new BrowserException("INVALID_ARGUMENT", "Element wait must be 1–5000 ms");
        return execute(() -> {
            long end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            DomResult result;
            do {
                result = queryOnWorker(pageId, selector);
                if (!result.elements().isEmpty()) return result;
                Thread.sleep(25);
            } while (System.nanoTime() < end);
            throw new BrowserException("ELEMENT_TIMEOUT", "Element did not appear before the wait deadline");
        });
    }
    public AttributeResult attribute(String pageId, String ref, String name) {
        return execute(() -> {
            if (name == null || !name.matches("[a-zA-Z_:][a-zA-Z0-9_:.-]{0,99}"))
                throw new BrowserException("INVALID_ARGUMENT", "Invalid DOM attribute name");
            HtmlElement element = element(page(pageId), ref);
            String value = element.getAttribute(name);
            return new AttributeResult(element.hasAttribute(name), truncate(value, settings.maxTextChars()), value.length() > settings.maxTextChars());
        });
    }
    public Snapshot select(String pageId, String ref, String value) {
        return execute(() -> {
            PageState state = page(pageId);
            HtmlElement element = element(state, ref);
            ensureEnabled(element);
            if (!(element instanceof HtmlSelect select)) throw new BrowserException("NOT_SELECT", "Reference is not a select element");
            if (value == null || value.length() > 4000) throw new BrowserException("INVALID_ARGUMENT", "Invalid option value");
            if (select.getOptions().stream().noneMatch(option -> option.getValueAttribute().equals(value)))
                throw new BrowserException("UNKNOWN_OPTION", "No option with this value");
            state.refs.clear(); browser().setCurrentWindow(state.window);
            select.setSelectedAttribute(value, true);
            return snapshotOnWorker(pageId, state);
        });
    }
    public Snapshot check(String pageId, String ref, boolean checked) {
        return execute(() -> {
            PageState state = page(pageId);
            HtmlElement element = element(state, ref);
            ensureEnabled(element);
            if (!(element instanceof HtmlCheckBoxInput) && !(element instanceof HtmlRadioButtonInput))
                throw new BrowserException("NOT_CHECKABLE", "Reference is not a checkbox or radio button");
            if (element instanceof HtmlRadioButtonInput && !checked)
                throw new BrowserException("INVALID_ARGUMENT", "Select another radio button to change the selection");
            state.refs.clear(); browser().setCurrentWindow(state.window);
            boolean current = element instanceof HtmlCheckBoxInput box ? box.isChecked() : ((HtmlRadioButtonInput)element).isChecked();
            if (current != checked) element.click();
            return snapshotOnWorker(pageId, state);
        });
    }
    public Snapshot click(String pageId, String ref) {
        return execute(() -> {
            PageState state = page(pageId);
            HtmlElement element = element(state, ref);
            if (element instanceof DisabledElement disabled && disabled.isDisabled())
                throw new BrowserException("DISABLED_ELEMENT", "Element is disabled");
            state.refs.clear();
            browser().setCurrentWindow(state.window);
            element.click();
            return snapshotOnWorker(pageId, state);
        });
    }
    public Snapshot type(String pageId, String ref, String text) {
        return execute(() -> {
            if (text == null || text.length() > 4000) throw new BrowserException("INVALID_ARGUMENT", "Input text exceeds 4000 characters");
            PageState state = page(pageId);
            HtmlElement element = element(state, ref);
            if (element instanceof DisabledElement disabled && disabled.isDisabled()
                    || element.hasAttribute("readonly"))
                throw new BrowserException("NOT_EDITABLE", "Element is disabled or read-only");
            browser().setCurrentWindow(state.window);
            state.refs.clear();
            if (element instanceof HtmlInput input && Set.of("text", "search", "email", "url", "tel", "password", "number").contains(input.getTypeAttribute())) {
                input.setValueAttribute(""); input.type(text);
            } else if (element instanceof HtmlTextArea area) { area.setText(""); area.type(text); }
            else throw new BrowserException("NOT_EDITABLE", "Reference is not a supported text input");
            return snapshotOnWorker(pageId, state);
        });
    }
    public ReadResult read(String pageId) {
        return read(pageId, 0);
    }
    /** Reads a bounded chunk of the page text. The nextOffset value can be passed back until null. */
    public ReadResult read(String pageId, Integer offset) {
        return execute(() -> {
            int start = offset == null ? 0 : offset;
            if (start < 0) throw new BrowserException("INVALID_ARGUMENT", "Read offset must be non-negative");
            HtmlPage html = html(page(pageId));
            DomNode article = html.querySelector("article, main");
            String text = (article == null ? html.getBody() : article).asNormalizedText();
            if (start > text.length()) throw new BrowserException("INVALID_ARGUMENT", "Read offset exceeds page text length");
            int end = Math.min(text.length(), start + settings.maxTextChars());
            return new ReadResult(pageId, html.getUrl().toString(), html.getTitleText(), text.substring(start, end),
                text.length() > end, start, end < text.length() ? end : null, text.length());
        });
    }
    public boolean closePage(String pageId) {
        return execute(() -> { PageState state = page(pageId); closeWindow(state.window); pages.remove(pageId); return true; });
    }
    private static void ensureEnabled(HtmlElement element) {
        if (element instanceof DisabledElement disabled && disabled.isDisabled())
            throw new BrowserException("DISABLED_ELEMENT", "Element is disabled");
    }
    private WebClient browser() {
        if (browser == null) browser = Browsers.create(settings, settings.javaScriptEnabled(), profile, proxy);
        return browser;
    }
    private DomResult queryOnWorker(String pageId, String selector) {
        if (selector == null || selector.isBlank() || selector.length() > 500)
            throw new BrowserException("INVALID_ARGUMENT", "CSS selector must be 1–500 characters");
        PageState state = page(pageId);
        HtmlPage html = html(state);
        List<DomNode> matches;
        try { matches = new ArrayList<>(html.querySelectorAll(selector)); }
        catch (RuntimeException exception) { throw new BrowserException("INVALID_SELECTOR", "CSS selector is invalid or unsupported"); }
        state.refs.clear();
        List<DomElementResult> results = new ArrayList<>();
        for (DomNode node : matches) {
            if (!(node instanceof HtmlElement element)) continue;
            String ref = UUID.randomUUID().toString(); state.refs.put(ref, element);
            Map<String, String> attributes = new LinkedHashMap<>();
            for (String name : List.of("id", "name", "type", "role", "aria-label", "href", "placeholder", "disabled"))
                if (element.hasAttribute(name)) attributes.put(name, truncate(element.getAttribute(name), 500));
            results.add(new DomElementResult(ref, element.getTagName(), truncate(element.asNormalizedText(), 500), Map.copyOf(attributes)));
            if (results.size() == 100) break;
        }
        return new DomResult(pageId, html.getUrl().toString(), List.copyOf(results), matches.size() > 100);
    }
    private Snapshot snapshotOnWorker(String id, PageState state) {
        HtmlPage html = html(state);
        state.refs.clear();
        List<ElementRef> refs = new ArrayList<>();
        for (DomNode node : html.querySelectorAll("a[href], button, input:not([type=hidden]), textarea, select")) {
            if (!(node instanceof HtmlElement element)) continue;
            String ref = UUID.randomUUID().toString();
            state.refs.put(ref, element);
            String label = element.getAttribute("aria-label");
            if (label.isBlank()) label = element.asNormalizedText();
            if (label.isBlank()) label = element.getAttribute("placeholder");
            if (label.isBlank()) label = element.getAttribute("name");
            refs.add(new ElementRef(ref, element.getTagName(), truncate(label, 200)));
            if (refs.size() == 100) break;
        }
        String text = html.getBody().asNormalizedText();
        return new Snapshot(id, html.getUrl().toString(), html.getTitleText(), truncate(text, settings.maxTextChars()),
            List.copyOf(refs), text.length() > settings.maxTextChars());
    }
    private PageState page(String id) {
        PageState state = pages.get(id);
        if (state == null) throw new BrowserException("UNKNOWN_PAGE", "Page does not belong to this request or is closed");
        return state;
    }
    private HtmlPage html(PageState state) {
        if (!(state.window.getEnclosedPage() instanceof HtmlPage html))
            throw new BrowserException("NOT_HTML", "Only HTML pages are supported");
        return html;
    }
    private HtmlElement element(PageState state, String ref) {
        HtmlElement element = state.refs.get(ref);
        if (element == null || element.getPage() != state.window.getEnclosedPage() || !element.isAttachedToPage())
            throw new BrowserException("STALE_REFERENCE", "Obtain a fresh snapshot and use its references");
        return element;
    }
    private <T> T execute(Callable<T> operation) {
        Future<T> future;
        synchronized (this) {
            if (closed) throw new BrowserException("CLOSED", "Browser request is closed");
            lastAccessNanos = System.nanoTime();
            if (++operations > 128) throw new BrowserException("OPERATION_LIMIT", "Browser request exceeds 128 operations");
            if (worker == null) {
                manager.acquire(this);
                worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16), task -> {
                    Thread thread = new Thread(task, "twigbrowse-session"); thread.setDaemon(true); return thread;
                });
            }
            try { future = worker.submit(operation); }
            catch (RejectedExecutionException e) { throw new BrowserException("BUSY", "Too many concurrent operations in this request"); }
        }
        try { return future.get(settings.operationTimeout().toMillis(), TimeUnit.MILLISECONDS); }
        catch (TimeoutException e) { future.cancel(true); close(); throw new BrowserException("TIMEOUT", "Browser operation timed out; request session closed"); }
        catch (InterruptedException e) { future.cancel(true); close(); Thread.currentThread().interrupt(); throw new BrowserException("CANCELLED", "Browser operation cancelled"); }
        catch (CancellationException e) { throw new BrowserException("CANCELLED", "Browser request closed before operation completed"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof BrowserException known) throw known;
            throw new BrowserException("BROWSER_ERROR", "Page request failed or is not supported");
        }
    }
    boolean isIdle(long now, long timeoutNanos) { return now - lastAccessNanos >= timeoutNanos; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (worker != null) {
            Runnable queued;
            while ((queued = worker.getQueue().poll()) != null) {
                if (queued instanceof Future<?> future) future.cancel(false);
            }
            worker.execute(() -> {
                try { if (browser != null) browser.close(); }
                finally { pages.clear(); manager.release(this); }
            });
            worker.shutdown();
        }
    }
    private static void closeWindow(WebWindow window) { if (window instanceof TopLevelWindow top) top.close(); }
    static String truncate(String text, int limit) { return text.substring(0, Math.min(text.length(), limit)); }
    private static final class PageState {
        final WebWindow window;
        final Map<String, HtmlElement> refs = new HashMap<>();
        PageState(WebWindow window) { this.window = window; }
    }
    public record ElementRef(String ref, String tag, String label) { }
    public record DomElementResult(String ref, String tag, String text, Map<String, String> attributes) { }
    public record DomResult(String pageId, String url, List<DomElementResult> elements, boolean truncated) { }
    public record AttributeResult(boolean present, String value, boolean truncated) { }
    public record Snapshot(String pageId, String url, String title, String text, List<ElementRef> elements, boolean truncated) { }
    public record ReadResult(String pageId, String url, String title, String text, boolean truncated,
                             int offset, Integer nextOffset, int totalChars) { }
}
