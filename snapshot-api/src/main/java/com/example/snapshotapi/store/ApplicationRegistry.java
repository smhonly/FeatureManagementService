package com.example.snapshotapi.store;

import com.example.snapshotapi.auth.ApiKeyHasher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Looks up an application by HMAC(api_key).
 *
 * The application record is stored in Redis at key "app:{api_key_hash}".
 * management-api writes this when it registers a new application, so
 * snapshot-api never has to talk to PostgreSQL for auth.
 */
@Component
public class ApplicationRegistry {

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final String secret;

    public ApplicationRegistry(StringRedisTemplate redis,
                               @Value("${ff.server-secret}") String secret) {
        this.redis = redis;
        this.secret = secret;
    }

    public Optional<JsonNode> resolve(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return Optional.empty();
        String hash = ApiKeyHasher.hmacSha256Hex(secret, apiKey);
        String raw = redis.opsForValue().get("app:" + hash);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(json.readTree(raw));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}