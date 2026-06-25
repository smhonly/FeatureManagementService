package com.example.managementapi.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/**
 * HMAC-SHA256 helper used by both auth (HmacAuthFilter, verifying api_key)
 * and key generation (ApplicationService, hashing the raw key for storage).
 *
 * Lives in its own package so adding more primitives (e.g. base64url with
 * no padding, secure random) doesn't bloat the auth or service classes.
 */
public final class HmacSha256 {

    private static final String ALG = "HmacSHA256";

    private HmacSha256() {}

    public static String hex(String secret, String message) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALG));
            byte[] digest = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 failed", e);
        }
    }
}
