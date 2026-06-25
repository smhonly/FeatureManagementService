package com.example.sdk.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polling-based sync controller. Calls a refresh callback every
 * {@code intervalSeconds} to pull the latest snapshot.
 *
 * <p>This is "Channel 2: Poll" from design (Figure 3). The data path
 * is identical to push — only the wake-up mechanism differs.
 *
 * <p>Used internally by {@link com.example.sdk.FeatureFlagClient.Builder};
 * can also be used standalone with any refresh callback.
 */
public class PollingSyncController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PollingSyncController.class);

    private final Runnable refreshCallback;
    private final int intervalSeconds;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;
    private volatile Instant lastRefreshTime;
    private volatile long lastRefreshDurationMs;

    /** @param refreshCallback called on each tick. Must be thread-safe. */
    public PollingSyncController(Runnable refreshCallback, int intervalSeconds) {
        this.refreshCallback = refreshCallback;
        this.intervalSeconds = Math.max(intervalSeconds, 5);
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ff-poll-sync");
            t.setDaemon(true);
            return t;
        });
    }

    /** Start periodic polling. Safe to call multiple times (no-op after first). */
    public synchronized void start() {
        if (running) return;
        running = true;
        // Initial fetch on start
        scheduler.execute(this::doRefresh);
        scheduler.scheduleWithFixedDelay(this::doRefresh,
                intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    /** Stop the scheduler. */
    public synchronized void stop() {
        running = false;
        scheduler.shutdownNow();
    }

    @Override
    public void close() {
        stop();
    }

    public Instant lastRefreshTime() { return lastRefreshTime; }
    public long lastRefreshDurationMs() { return lastRefreshDurationMs; }

    private void doRefresh() {
        if (!running) return;
        long start = System.currentTimeMillis();
        try {
            refreshCallback.run();
            long elapsed = System.currentTimeMillis() - start;
            lastRefreshTime = Instant.now();
            lastRefreshDurationMs = elapsed;
        } catch (Exception e) {
            // One failed refresh must not kill the timer loop.
            log.error("poll refresh failed", e);
        }
    }
}