package com.example.sdk.model;

import java.util.Collections;
import java.util.Map;

/**
 * Caller's user context — the attributes a rule can match against.
 *
 * Immutable. userId is the canonical stable identifier (used for
 * percentage bucketing). All other attributes are key/value pairs.
 */
public final class UserContext {
    private final String userId;
    private final Map<String, Object> attributes;

    public UserContext(String userId, Map<String, Object> attributes) {
        this.userId = userId;
        this.attributes = attributes == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(attributes);
    }

    public static UserContext of(String userId) {
        return new UserContext(userId, Collections.emptyMap());
    }

    public String userId() { return userId; }
    public Map<String, Object> attributes() { return attributes; }

    public Object attr(String name) { return attributes.get(name); }
}