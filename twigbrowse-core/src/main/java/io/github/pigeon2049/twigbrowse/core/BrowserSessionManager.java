package io.github.pigeon2049.twigbrowse.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public final class BrowserSessionManager implements AutoCloseable {
    private final BrowserSettings settings;
    private final SearchService search;
    private final BrowserProfile profile;
    private final Semaphore permits;
    private final Duration idleTimeout;
    private final ProxySettings proxy;
    private final ScheduledExecutorService reaper;
    private final Set<BrowserSession> active = ConcurrentHashMap.newKeySet();
    private boolean closed;
    public BrowserSessionManager(BrowserSettings settings, SearchService search) {
        this(settings, search, BrowserProfile.defaults(), Duration.ofMinutes(5), null);
    }
    public BrowserSessionManager(BrowserSettings settings, SearchService search, BrowserProfile profile) {
        this(settings, search, profile, Duration.ofMinutes(5), null);
    }
    public BrowserSessionManager(BrowserSettings settings, SearchService search, BrowserProfile profile, Duration idleTimeout) {
        this(settings, search, profile, idleTimeout, null);
    }
    public BrowserSessionManager(BrowserSettings settings, SearchService search, BrowserProfile profile, Duration idleTimeout, ProxySettings proxy) {
        if (idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative())
            throw new IllegalArgumentException("Idle timeout must be positive");
        this.profile = BrowserProfile.defaults().overlay(profile);
        this.settings = settings; this.search = search; this.idleTimeout = idleTimeout; this.proxy = proxy;
        this.permits = new Semaphore(settings.maxSessions());
        this.reaper = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "twigbrowse-session-reaper"); thread.setDaemon(true); return thread;
        });
        long interval = Math.max(25, Math.min(idleTimeout.toMillis() / 2, 60_000));
        this.reaper.scheduleWithFixedDelay(this::reapIdleSessions, interval, interval, TimeUnit.MILLISECONDS);
    }
    /** Allocates only a request handle; the worker and browser remain lazy. */
    public BrowserSession openSession() { return openSession(null); }
    public synchronized BrowserSession openSession(BrowserProfile override) {
        if (closed) throw new BrowserException("CLOSED", "Browser manager is closed");
        return new BrowserSession(this, settings, search, profile.overlay(override), proxy);
    }
    synchronized void acquire(BrowserSession session) {
        if (closed) throw new BrowserException("CLOSED", "Browser manager is closed");
        if (!permits.tryAcquire()) throw new BrowserException("CAPACITY", "All browser slots are busy");
        active.add(session);
    }
    void release(BrowserSession session) { if (active.remove(session)) permits.release(); }
    private void reapIdleSessions() {
        long now = System.nanoTime();
        for (BrowserSession session : active) if (session.isIdle(now, idleTimeout.toNanos())) session.close();
    }
    public int activeSessions() { return active.size(); }
    @Override public void close() {
        BrowserSession[] sessions;
        synchronized (this) { closed = true; sessions = active.toArray(BrowserSession[]::new); }
        reaper.shutdownNow();
        for (BrowserSession session : sessions) session.close();
    }
}
