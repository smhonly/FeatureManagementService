package com.example.snapshotapi.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Snapshot storage backed by Redis. Two keys per (env, app):
 *   snapshot:{env}:{app_id}:version   — integer version (monotonic)
 *   snapshot:{env}:{app_id}:data      — full JSON body
 */
@Component
public class SnapshotStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();

    public SnapshotStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public Optional<Entry> get(String env, int appId) {
        String versionKey = key(env, appId, "version");
        String dataKey = key(env, appId, "data");

        String versionStr = redis.opsForValue().get(versionKey);
        if (versionStr == null) return Optional.empty();
        String dataStr = redis.opsForValue().get(dataKey);
        if (dataStr == null) return Optional.empty();

        try {
            JsonNode data = json.readTree(dataStr);
            return Optional.of(new Entry(Integer.parseInt(versionStr), data));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Write version + data atomically using a Lua script.
     *
     * Without atomicity, a reader can see new-version + old-data (or vice versa)
     * if the two SET calls interleave. The Lua script guarantees both keys are
     * updated in the same Redis transaction.
     */
    public void putAtomic(String env, int appId, int version, JsonNode data) {
        String versionKey = key(env, appId, "version");
        String dataKey = key(env, appId, "data");

        String lua = "redis.call('SET', KEYS[1], ARGV[1]) " +
                     "redis.call('SET', KEYS[2], ARGV[2]) " +
                     "return 1";

        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            byte[] scriptBytes = lua.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] k1 = versionKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] k2 = dataKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] a1 = Integer.toString(version).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] a2 = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            // eval(script, returnType, numKeys, keysAndArgs...)
            connection.eval(scriptBytes,
                    org.springframework.data.redis.connection.ReturnType.INTEGER,
                    2, k1, k2, a1, a2);
            return null;
        });
    }

    /** Non-atomic put (kept for backward compat; prefer putAtomic). */
    public void put(String env, int appId, int version, JsonNode data) {
        String versionKey = key(env, appId, "version");
        String dataKey = key(env, appId, "data");
        redis.opsForValue().set(versionKey, Integer.toString(version));
        redis.opsForValue().set(dataKey, data.toString());
    }

    private static String key(String env, int appId, String suffix) {
        return "snapshot:" + env + ":" + appId + ":" + suffix;
    }

    public record Entry(int version, JsonNode data) {}
}