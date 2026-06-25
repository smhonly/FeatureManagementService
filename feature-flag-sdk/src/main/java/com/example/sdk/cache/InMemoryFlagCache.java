package com.example.sdk.cache;

import com.example.sdk.model.FlagDefinition;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory flag cache. The single source of truth for
 * "is this flag enabled for this user" — populated by SnapshotFetcher
 * after a snapshot poll, queried on every isEnabled() call.
 *
 * Stored as FlagDefinition objects (not booleans) so local evaluation
 * can replay against the same definition the server used.
 */
public class InMemoryFlagCache {

    private final Map<String, FlagDefinition> flags = new ConcurrentHashMap<>();

    /** Replace the whole snapshot atomically. */
    public void replaceAll(Map<String, FlagDefinition> snapshot) {
        flags.clear();
        flags.putAll(snapshot);
    }

    public Optional<FlagDefinition> get(String key) {
        return Optional.ofNullable(flags.get(key));
    }

    public int size() {
        return flags.size();
    }

    public void clear() {
        flags.clear();
    }
}