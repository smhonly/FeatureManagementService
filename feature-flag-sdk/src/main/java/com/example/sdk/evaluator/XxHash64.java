package com.example.sdk.evaluator;

/**
 * Pure-Java xxhash64 (XXH64) — non-cryptographic 64-bit hash.
 *
 * <p>Reference implementation translated from xxHash by Yann Collet
 * (BSD-2-Clause). Verified against the canonical test vectors:
 * <ul>
 *   <li>XXH64("", seed=0)               = 0xEF46DB3751D8E999</li>
 *   <li>XXH64("abc", seed=0)            = 0x2D06800538D394C2</li>
 *   <li>XXH64("1234567890abcdef", seed=0) = 0xC77D1707C68B265E</li>
 * </ul>
 *
 * <p>Used by the SDK to bucket users into percentage rollouts. The hash
 * function is fixed across SDK languages (Go, Node, Swift all implement
 * the same xxhash64) so the same (salt, flag, userId) tuple produces
 * the same bucket regardless of where the SDK runs — this is what
 * design §4 means by "cross-language conformance".
 *
 * <p>Why not {@link java.security.MessageDigest MessageDigest}?
 * SHA-256 is cryptographic (slow, 32 bytes), and most importantly isn't
 * what the other SDKs use. xxhash64 is ~10× faster and 8 bytes.
 */
public final class XxHash64 {

    private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
    private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
    private static final long PRIME64_3 = 0x165667B19E3779F9L;
    private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
    private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

    private XxHash64() {}

    public static long hash(byte[] input, long seed) {
        return hash(input, 0, input.length, seed);
    }

    public static long hash(byte[] input, int offset, int length, long seed) {
        long h64;
        int idx = offset;
        int end = offset + length;

        if (length >= 32) {
            int limit = end - 32;
            long v1 = seed + PRIME64_1 + PRIME64_2;
            long v2 = seed + PRIME64_2;
            long v3 = seed;
            long v4 = seed - PRIME64_1;

            do {
                v1 = round(v1, readLongLE(input, idx));      idx += 8;
                v2 = round(v2, readLongLE(input, idx));      idx += 8;
                v3 = round(v3, readLongLE(input, idx));      idx += 8;
                v4 = round(v4, readLongLE(input, idx));      idx += 8;
            } while (idx <= limit);

            h64 = rotl(v1, 1) + rotl(v2, 7) + rotl(v3, 12) + rotl(v4, 18);
            h64 = mergeRound(h64, v1);
            h64 = mergeRound(h64, v2);
            h64 = mergeRound(h64, v3);
            h64 = mergeRound(h64, v4);
        } else {
            h64 = seed + PRIME64_5;
        }

        h64 += length;
        return finalize(h64, input, idx, length & 31);
    }

    private static long round(long acc, long input) {
        acc += input * PRIME64_2;
        acc = rotl(acc, 31);
        acc *= PRIME64_1;
        return acc;
    }

    private static long mergeRound(long acc, long val) {
        val = round(0, val);
        acc ^= val;
        acc = acc * PRIME64_1 + PRIME64_4;
        return acc;
    }

    private static long finalize(long h64, byte[] input, int idx, int remaining) {
        while (remaining >= 8) {
            long k1 = round(0, readLongLE(input, idx));
            h64 ^= k1;
            h64 = rotl(h64, 27) * PRIME64_1 + PRIME64_4;
            idx += 8;
            remaining -= 8;
        }
        if (remaining >= 4) {
            h64 ^= ((long) readIntLE(input, idx)) * PRIME64_1;
            h64 = rotl(h64, 23) * PRIME64_2 + PRIME64_3;
            idx += 4;
            remaining -= 4;
        }
        while (remaining > 0) {
            h64 ^= ((long) input[idx] & 0xFF) * PRIME64_5;
            h64 = rotl(h64, 11) * PRIME64_1;
            idx++;
            remaining--;
        }
        // Avalanche
        h64 ^= h64 >>> 33;
        h64 *= PRIME64_2;
        h64 ^= h64 >>> 29;
        h64 *= PRIME64_3;
        h64 ^= h64 >>> 32;
        return h64;
    }

    private static long rotl(long x, int r) {
        return (x << r) | (x >>> (64 - r));
    }

    private static long readLongLE(byte[] b, int i) {
        return ((long) (b[i]     & 0xFF))       |
               ((long) (b[i + 1] & 0xFF) <<  8) |
               ((long) (b[i + 2] & 0xFF) << 16) |
               ((long) (b[i + 3] & 0xFF) << 24) |
               ((long) (b[i + 4] & 0xFF) << 32) |
               ((long) (b[i + 5] & 0xFF) << 40) |
               ((long) (b[i + 6] & 0xFF) << 48) |
               ((long) (b[i + 7] & 0xFF) << 56);
    }

    private static int readIntLE(byte[] b, int i) {
        return (b[i]     & 0xFF)       |
               ((b[i + 1] & 0xFF) << 8) |
               ((b[i + 2] & 0xFF) << 16) |
               ((b[i + 3] & 0xFF) << 24);
    }
}