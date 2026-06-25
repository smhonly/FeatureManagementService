package com.example.sdk;

import com.example.sdk.metrics.FeatureFlagMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FeatureFlagMetricsTest {

    private FeatureFlagMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new FeatureFlagMetrics();
    }

    @Test
    void shouldIncrementTotalOnRecord() {
        metrics.recordEvaluation("flag_a", true, 42);
        metrics.recordEvaluation("flag_a", false, 18);

        Map<String, Object> snap = metrics.snapshot();
        assertEquals(2L, snap.get("eval_total"));
    }

    @Test
    void shouldTrackPerFlagCounts() {
        metrics.recordEvaluation("flag_a", true, 10);
        metrics.recordEvaluation("flag_a", false, 10);
        metrics.recordEvaluation("flag_b", true, 10);

        @SuppressWarnings("unchecked")
        Map<String, Long> byFlag = (Map<String, Long>) metrics.snapshot().get("eval_by_flag");
        assertEquals(2L, byFlag.get("flag_a"));
        assertEquals(1L, byFlag.get("flag_b"));
    }

    @Test
    void shouldComputeAverageDuration() {
        metrics.recordEvaluation("f1", true, 100);
        metrics.recordEvaluation("f1", false, 200);

        Map<String, Object> snap = metrics.snapshot();
        assertEquals(150L, snap.get("eval_avg_duration_us"));
    }

    @Test
    void shouldReturnZeroAvgWhenNoEvaluations() {
        Map<String, Object> snap = metrics.snapshot();
        assertEquals(0L, snap.get("eval_avg_duration_us"));
        assertEquals(0L, snap.get("eval_total"));
    }

    @Test
    void shouldRecordSuccessfulRefreshAsAgeZero() {
        // Right after a successful refresh, snapshot_age_seconds ≈ 0
        // (design §8 metric). Previously this was always 0 due to a math
        // bug; the new design computes age dynamically from last refresh.
        metrics.recordSuccessfulRefresh();
        long age = (long) metrics.snapshot().get("snapshot_age_seconds");
        assertTrue(age >= 0 && age <= 1,
                "expected ~0s after refresh, got " + age);
    }

    @Test
    void snapshotAgeIsNegativeOneBeforeFirstRefresh() {
        // Sentinel: -1 means "never refreshed yet" — distinct from "0s old"
        // (which would be misleading for a brand-new client).
        assertEquals(-1L, metrics.snapshot().get("snapshot_age_seconds"));
    }

    @Test
    void snapshotAgeGrowsBetweenRefreshes() throws Exception {
        metrics.recordSuccessfulRefresh();
        long t0 = (long) metrics.snapshot().get("snapshot_age_seconds");
        Thread.sleep(1100);   // > 1s
        long t1 = (long) metrics.snapshot().get("snapshot_age_seconds");
        assertTrue(t1 > t0, "age should grow over time, was " + t0 + " then " + t1);
    }

    @Test
    void shouldResetAllCounters() {
        metrics.recordEvaluation("f1", true, 50);
        metrics.recordSuccessfulRefresh();
        assertEquals(1L, metrics.snapshot().get("eval_total"));

        metrics.reset();
        assertEquals(0L, metrics.snapshot().get("eval_total"));
        assertEquals(-1L, metrics.snapshot().get("snapshot_age_seconds"));
    }

    @Test
    void shouldHandleConcurrentRecordings() throws Exception {
        int threads = 4;
        int perThread = 1000;
        Thread[] ts = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            final int tid = i;
            ts[i] = new Thread(() -> {
                for (int j = 0; j < perThread; j++) {
                    metrics.recordEvaluation("flag_" + (j % 10), j % 2 == 0, j);
                }
            });
            ts[i].start();
        }
        for (Thread t : ts) t.join();

        assertEquals((long) threads * perThread, metrics.snapshot().get("eval_total"));
    }
}
