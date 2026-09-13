package io.github.pigeon2049.twigbrowse.demo;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import io.github.pigeon2049.twigbrowse.core.BrowserSessionManager;

@Component
final class Conversations implements AutoCloseable {
    private final Map<String, Conversation> entries = new HashMap<>();
    private final BrowserSessionManager manager;
    private final Duration idleTimeout;
    final Duration turnTimeout;
    private final int capacity;
    private final ScheduledExecutorService reaper;
    @org.springframework.beans.factory.annotation.Autowired
    Conversations(BrowserSessionManager manager, Environment env) {
        this(manager, env.getProperty("demo.conversation-idle-timeout", Duration.class, Duration.ofMinutes(5)),
            env.getProperty("demo.turn-timeout", Duration.class, Duration.ofMinutes(3)),
            env.getProperty("demo.max-conversations", Integer.class, 64));
    }
    Conversations(BrowserSessionManager manager, Duration idle, Duration turn, int capacity) {
        if (idle.isNegative() || idle.isZero() || turn.isNegative() || turn.isZero() || capacity < 1)
            throw new IllegalArgumentException("Conversation limits must be positive");
        this.manager = manager; this.idleTimeout = idle; this.turnTimeout = turn; this.capacity = capacity;
        reaper = Executors.newSingleThreadScheduledExecutor(r -> { var t = new Thread(r, "demo-conversation-reaper"); t.setDaemon(true); return t; });
        reaper.scheduleWithFixedDelay(this::reap, 15, 15, TimeUnit.SECONDS);
    }
    synchronized Conversation acquire(HttpSession session) {
        reap();
        String id = session.getId(); // Never accept a user-supplied conversation ID.
        Conversation c = entries.get(id);
        if (c == null) {
            if (entries.size() >= capacity) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "会话已满，请稍后重试");
            c = new Conversation(manager); entries.put(id, c);
        }
        if (!c.acquire()) throw new ResponseStatusException(HttpStatus.CONFLICT, "请等待当前回复完成");
        return c;
    }
    synchronized void reset(HttpSession session) {
        Conversation c = entries.get(session.getId());
        if (c != null) {
            if (!c.acquire()) throw new ResponseStatusException(HttpStatus.CONFLICT, "请等待当前回复完成");
            entries.remove(session.getId()); c.close();
        }
    }
    synchronized void reap() {
        long now = System.nanoTime();
        entries.values().removeIf(c -> { if (!c.idle(now, idleTimeout.toNanos())) return false; c.close(); return true; });
    }
    @PreDestroy @Override public synchronized void close() {
        reaper.shutdownNow(); entries.values().forEach(Conversation::close); entries.clear();
    }
}
