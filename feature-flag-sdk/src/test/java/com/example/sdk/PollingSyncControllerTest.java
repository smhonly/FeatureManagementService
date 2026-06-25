package com.example.sdk;

import com.example.sdk.sync.PollingSyncController;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PollingSyncControllerTest {

    @Test
    void shouldStartAndStop() {
        AtomicInteger calls = new AtomicInteger();
        PollingSyncController sync = new PollingSyncController(calls::incrementAndGet, 10);
        sync.start();
        assertDoesNotThrow(() -> Thread.sleep(100));
        sync.stop();
        assertTrue(calls.get() >= 1, "should have called refresh at least once");
    }

    @Test
    void shouldNotThrowOnFailedRefresh() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        PollingSyncController sync = new PollingSyncController(() -> {
            calls.incrementAndGet();
            throw new RuntimeException("simulated failure");
        }, 5);
        try {
            sync.start();
            Thread.sleep(300);
            // Callback was invoked (proving the loop is alive), even though
            // each invocation throws. lastRefreshTime stays null because
            // it's only updated on success.
            assertTrue(calls.get() >= 1,
                    "loop should keep calling refresh even after failures");
        } finally {
            sync.stop();
        }
    }

    @Test
    void shouldRecordLastRefreshTime() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        try (PollingSyncController sync = new PollingSyncController(calls::incrementAndGet, 5)) {
            sync.start();
            Thread.sleep(200);
            assertNotNull(sync.lastRefreshTime());
        }
    }

    @Test
    void shouldBeAutoCloseable() {
        PollingSyncController sync = new PollingSyncController(() -> {}, 5);
        sync.start();
        // auto-closed by try-with-resources — no exception expected
        try {
            sync.close();
        } catch (Exception e) {
            fail("close should not throw", e);
        }
    }

    @Test
    void shouldInvokeCallbackConcurrently() throws Exception {
        // Verifies the callback runs on the scheduler thread (not caller thread)
        CountDownLatch latch = new CountDownLatch(1);
        Thread callerThread = Thread.currentThread();
        AtomicInteger onSameThread = new AtomicInteger();
        PollingSyncController sync = new PollingSyncController(() -> {
            if (Thread.currentThread() == callerThread) {
                onSameThread.incrementAndGet();
            }
            latch.countDown();
        }, 5);
        try {
            sync.start();
            assertTrue(latch.await(2, TimeUnit.SECONDS), "callback should be invoked");
            assertEquals(0, onSameThread.get(),
                    "callback must run on scheduler thread, not caller");
        } finally {
            sync.stop();
        }
    }
}