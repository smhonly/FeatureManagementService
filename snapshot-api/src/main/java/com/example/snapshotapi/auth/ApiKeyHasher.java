package com.example.snapshotapi.auth;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * HMAC-SHA256 over the api_key. Same algorithm used by management-api to
 * hash api_key before storing it; this is the inverse used to resolve
 * an incoming Bearer token to its Redis key.
 *
 * Deterministic, no salt beyond the server-side secret.
 */
public final class ApiKeyHasher {

    private ApiKeyHasher() {}

    public static String hmacSha256Hex(String secret, String apiKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(out.length * 2);
            for (byte b : out) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}