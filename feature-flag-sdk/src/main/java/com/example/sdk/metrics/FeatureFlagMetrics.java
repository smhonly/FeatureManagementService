package com.example.sdk.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe in-process metrics for the SDK.
 *
 * <p>Counters are accumulated locally; a Prometheus scraper or a periodic
 * log-dump reads {@link #snapshot()} on a schedule. No network call
 * happens on every {@code isEnabled()}.
 *
 * <p>Mirrors docs/design.md §8 observability table:
 * <ul>
 *   <li>{@code eval_total{flag, result}} — total evaluations, broken down per flag</li>
 *   <li>{@code eval_duration_us} — running sum for average-latency calculation</li>
 *   <li>{@code snapshot_age_seconds} — how stale the in-memory snapshot is (computed at read time)</li>
 * </ul>
 *
 * <p>Note on snapshot age: this class no longer stores the age as a number
 * (the previous implementation set {@code snapshotAgeSeconds = now - lastRefreshTime}
 * right after refresh, which was always 0). Instead it stores
 * {@code lastRefreshEpochMs}; readers compute age = now − lastRefreshEpochMs,
 * which is correct at any point in time.
 */
public class FeatureFlagMetrics {

    private final LongAdder evalTotal = new LongAdder();
    private final ConcurrentHashMap<String, AtomicLong> evalByFlag = new ConcurrentHashMap<>();
    private final LongAdder evalDurationMicros = new LongAdder();

    /**
     * Epoch millis of the last successful snapshot refresh. {@code 0} means
     * "never refreshed". Read by {@link #snapshot()} to compute the age at
     * observation time.
     */
    private volatile long lastRefreshEpochMs;

    /** Record one evaluation. Called from the hot path — must be cheap. */
    public void recordEvaluation(String flagKey, boolean result, long durationMicros) {
        evalTotal.increment();
        evalByFlag.computeIfAbsent(flagKey, k -> new AtomicLong()).incrementAndGet();
        evalDurationMicros.add(durationMicros);
    }

    /** Called by the sync controller after each successful refresh. */
    public void recordSuccessfulRefresh() {
        this.lastRefreshEpochMs = System.currentTimeMillis();
    }

    /** Return a flat snapshot suitable for logging or exposing over HTTP. */
    public Map<String, Object> snapshot() {
        long count = evalTotal.sum();
        long totalUs = evalDurationMicros.sum();
        long avgUs = count == 0 ? 0 : totalUs / count;
        long ageSec = lastRefreshEpochMs == 0
                ? -1
                : (System.currentTimeMillis() - lastRefreshEpochMs) / 1000;

        Map<String, Long> byFlag = new ConcurrentHashMap<>();
        evalByFlag.forEach((k, v) -> byFlag.put(k, v.get()));

        return Map.of(
                "eval_total", count,
                "eval_avg_duration_us", avgUs,
                "eval_by_flag", byFlag,
                "snapshot_age_seconds", ageSec
        );
    }

    /** Reset all counters (for testing). */
    public void reset() {
        evalTotal.reset();
        evalByFlag.clear();
        evalDurationMicros.reset();
        lastRefreshEpochMs = 0;
    }
}