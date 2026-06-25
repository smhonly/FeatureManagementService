package com.example.sdk;

import com.example.sdk.evaluator.HashBucketer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HashBucketerTest {

    @Test
    void bucketIsInRange() {
        for (int i = 0; i < 1000; i++) {
            int b = HashBucketer.bucket("salt", "flag", "user-" + i);
            assertTrue(b >= 0 && b < 100, "bucket out of range: " + b);
        }
    }

    @Test
    void bucketIsDeterministic() {
        int a = HashBucketer.bucket("salt", "flag", "alice");
        int b = HashBucketer.bucket("salt", "flag", "alice");
        assertEquals(a, b);
    }

    @Test
    void changingSaltChangesDistribution() {
        // Not necessarily different for one user, but distributions over
        // many users must differ. Check that *some* users moved.
        int moved = 0;
        for (int i = 0; i < 200; i++) {
            int a = HashBucketer.bucket("saltA", "flag", "user-" + i);
            int b = HashBucketer.bucket("saltB", "flag", "user-" + i);
            if (Math.abs(a - b) > 50) moved++;  // crossed the 0/100 midpoint
        }
        assertTrue(moved > 20, "salt change should re-shuffle a meaningful fraction");
    }

    @Test
    void emptyUserIdThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> HashBucketer.bucket("salt", "flag", ""));
    }

    @Test
    void emptyFlagKeyThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> HashBucketer.bucket("salt", "", "alice"));
    }

    @Test
    void distributionIsReasonablyUniform() {
        int[] hits = new int[10];
        for (int i = 0; i < 10000; i++) {
            int b = HashBucketer.bucket("salt", "flag", "user-" + i);
            hits[b / 10]++;
        }
        // Each bucket ~1000. Allow ±20%.
        for (int h : hits) {
            assertTrue(h > 800 && h < 1200, "uneven distribution: " + h);
        }
    }

    @Test
    void matchesPythonXxhash64Reference() {
        // Cross-language conformance: these exact values are produced by
        // Python's xxhash library for the same inputs. The Java SDK must
        // produce the same bucket numbers so a user's evaluation is
        // identical regardless of which language SDK runs.
        //   xxhash.xxh64('checkout:checkout_v2:alice@example.com', 0) % 100 == 94
        //   xxhash.xxh64('checkout:checkout_v2:bob@example.com',   0) % 100 == 6
        assertEquals(94, HashBucketer.bucket("checkout", "checkout_v2", "alice@example.com"));
        assertEquals(6,  HashBucketer.bucket("checkout", "checkout_v2", "bob@example.com"));
    }
}