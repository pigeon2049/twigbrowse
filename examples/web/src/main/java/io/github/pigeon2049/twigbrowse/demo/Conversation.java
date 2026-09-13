package io.github.pigeon2049.twigbrowse.demo;

import java.util.*;
import io.github.pigeon2049.twigbrowse.core.*;
import org.springframework.ai.chat.messages.*;

/** Access to a conversation is serialized for the whole model/tool turn, not just one tool call. */
final class Conversation implements AutoCloseable {
    private final BrowserSessionManager manager;
    private BrowserSession browser;
    private List<Message> history = List.of();
    private boolean busy;
    private boolean closed;
    private long lastUsed = System.nanoTime();

    Conversation(BrowserSessionManager manager) { this.manager = manager; }
    synchronized boolean acquire() {
        if (busy || closed) return false;
        busy = true;
        return true;
    }
    synchronized void release() { busy = false; lastUsed = System.nanoTime(); }
    synchronized boolean idle(long now, long timeout) { return !busy && now - lastUsed >= timeout; }
    synchronized BrowserSession browser() {
        if (closed) throw new IllegalStateException("Conversation closed");
        if (browser == null || browser.isClosed()) {
            // References from a recycled browser must never be replayed to the model.
            history = history.stream().filter(m -> m instanceof UserMessage ||
                (m instanceof AssistantMessage a && !a.hasToolCalls())).toList();
            browser = manager.openSession();
        }
        return browser;
    }
    synchronized List<Message> history() { return List.copyOf(history); }
    synchronized void remember(List<Message> messages, String answer) {
        var saved = new ArrayList<Message>();
        messages.stream().filter(m -> !(m instanceof SystemMessage)).forEach(saved::add);
        saved.add(new AssistantMessage(answer));
        // Remove whole user turns; never split a tool-call / tool-result exchange.
        while (saved.stream().filter(UserMessage.class::isInstance).count() > 8 || size(saved) > 240_000) {
            int next = -1;
            for (int i = 1; i < saved.size(); i++) if (saved.get(i) instanceof UserMessage) { next = i; break; }
            if (next < 0) break;
            saved.subList(0, next).clear();
        }
        // A single very large turn keeps its question and final answer, without stale partial tool exchanges.
        if (size(saved) > 240_000) saved.removeIf(m -> m instanceof ToolResponseMessage ||
            (m instanceof AssistantMessage a && a.hasToolCalls()));
        history = List.copyOf(saved);
    }
    static int size(List<Message> messages) { return messages.stream().mapToInt(m -> m.toString().length()).sum(); }
    synchronized void resetBrowser() {
        if (browser != null) browser.close();
        browser = null;
    }
    @Override public synchronized void close() { closed = true; resetBrowser(); history = List.of(); }
}
