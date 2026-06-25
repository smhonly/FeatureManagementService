package com.example.sdk.evaluator;

import java.nio.charset.StandardCharsets;

/**
 * Hash bucketing for percentage rollouts.
 *
 * <p>Bucket input is the concatenation {@code salt + ":" + flag_key + ":" + user_id}
 * (per design §5 example "salt:checkout:U123"). Hashed with xxhash64 — the
 * same algorithm used by every cross-language SDK, so a user's bucket is
 * the same in Java, Go, Node, and Swift for the same flag.
 *
 * <p>{@code bucket = xxhash64(salt:flag_key:user_id, seed=0) mod 100}
 *
 * <p>Bucket input must include the flag key (not just user id) so two flags
 * with different salts still produce independent distributions. The salt
 * itself is per-flag, so changing the salt reshuffles only that flag.
 */
public final class HashBucketer {

    private HashBucketer() {}

    /** Returns a value in [0, 100). */
    public static int bucket(String salt, String flagKey, String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId required for bucketing");
        }
        if (flagKey == null || flagKey.isEmpty()) {
            throw new IllegalArgumentException("flagKey required for bucketing");
        }
        String effectiveSalt = (salt == null || salt.isEmpty()) ? "" : salt;
        byte[] input = (effectiveSalt + ":" + flagKey + ":" + userId)
                .getBytes(StandardCharsets.UTF_8);
        long h = XxHash64.hash(input, 0L);
        // xxhash64 returns an unsigned 64-bit value. Java's `long` is signed,
        // so for hashes with the high bit set (≥ 2^63) h is negative as a
        // signed long, and `h % 100` is negative — but the bucket math in
        // every other SDK (Go, Python, Swift, ...) operates on the unsigned
        // bit pattern. Cross-language conformance means we have to match
        // that unsigned interpretation here.
        //
        // (Note: `h & 0xFFFFFFFFFFFFFFFFL` is a no-op because Java parses
        // 0xFFFFFFFFFFFFFFFFL as -1L since `long` is signed. The right tool
        // is Long.remainderUnsigned, which treats both operands as unsigned.)
        return (int) Long.remainderUnsigned(h, 100L);
    }
}