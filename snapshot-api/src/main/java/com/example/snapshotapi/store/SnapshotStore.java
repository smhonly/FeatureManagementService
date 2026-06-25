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
     * Write data atomically with an auto-incremented version.
     *
     * <p>The Lua script does two things in one Redis transaction:</p>
     * <ol>
     *   <li>{@code INCR} on the version key — guarantees a monotonic version
     *       even when every flag in the snapshot is at version 1.</li>
     *   <li>{@code SET} on the data key.</li>
     * </ol>
     *
     * <p>Returns the new snapshot version so callers can log it or include
     * it in Pub/Sub notifications.</p>
     */
    public int putAtomic(String env, int appId, JsonNode data) {
        String versionKey = key(env, appId, "version");
        String dataKey = key(env, appId, "data");

        String lua = "local v = redis.call('INCR', KEYS[1]) " +
                     "redis.call('SET', KEYS[2], ARGV[1]) " +
                     "return v";

        Long newVersion = redis.execute(
                (org.springframework.data.redis.core.RedisCallback<Long>) connection -> {
            byte[] scriptBytes = lua.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] k1 = versionKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] k2 = dataKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] a1 = data.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return connection.eval(scriptBytes,
                    org.springframework.data.redis.connection.ReturnType.INTEGER,
                    2, k1, k2, a1);
        });
        return newVersion != null ? newVersion.intValue() : 0;
    }

    /** Non-atomic put (kept for backward compat; prefer putAtomic). */
    public void put(String env, int appId, int version, JsonNode data) {
        String versionKey = key(env, appId, "version");
        String dataKey = key(env, appId, "data");
        redis.opsForValue().set(versionKey, Integer.toString(version));
        redis.opsForValue().set(dataKey, data.toString());
    }

    /**
     * Merge a single flag into the per-app snapshot, then write back atomically.
     *
     * <p>Reads the current snapshot (if any), inserts / updates / removes the
     * target flag in the {@code flags} array, and calls {@link #putAtomic}
     * to bump the version and persist.  The read and write are NOT wrapped in
     * a transaction — two concurrent merges for different flags on the same app
     * may race, but each write is atomic and the snapshot converges.</p>
     *
     * @param env    environment
     * @param appId  application id
     * @param flagKey which flag changed
     * @param definition the new definition, or null to remove
     * @return the new snapshot version
     */
    public int mergeFlag(String env, int appId, String flagKey, JsonNode definition) {
        // Read current snapshot (may be empty — first flag for this app)
        Entry current = get(env, appId).orElse(null);
        com.fasterxml.jackson.databind.node.ObjectNode body;
        com.fasterxml.jackson.databind.node.ArrayNode flagsArray;

        if (current != null && current.data() != null) {
            // Deep-copy so we don't mutate a cached entry
            body = current.data().deepCopy();
            flagsArray = body.has("flags") && body.get("flags").isArray()
                    ? (com.fasterxml.jackson.databind.node.ArrayNode) body.get("flags")
                    : json.createArrayNode();
        } else {
            body = json.createObjectNode();
            flagsArray = json.createArrayNode();
            body.set("flags", flagsArray);
        }

        // Find and remove existing entry for this flag
        int existingIdx = -1;
        for (int i = 0; i < flagsArray.size(); i++) {
            if (flagKey.equals(flagsArray.get(i).get("key").asText())) {
                existingIdx = i;
                break;
            }
        }

        if (definition != null) {
            // Insert or update
            com.fasterxml.jackson.databind.node.ObjectNode entry = json.createObjectNode();
            entry.put("key", flagKey);
            entry.set("definition", definition);
            if (existingIdx >= 0) {
                flagsArray.set(existingIdx, entry);
            } else {
                flagsArray.add(entry);
            }
        } else {
            // Remove (archive)
            if (existingIdx >= 0) {
                flagsArray.remove(existingIdx);
            }
        }

        return putAtomic(env, appId, body);
    }

    private static String key(String env, int appId, String suffix) {
        return "snapshot:" + env + ":" + appId + ":" + suffix;
    }

    public record Entry(int version, JsonNode data) {}
}