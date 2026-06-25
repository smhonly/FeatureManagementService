package com.example.sdk.model;

import java.util.List;
import java.util.Map;

/**
 * Parsed flag definition. Three shapes (mirrors design §4):
 *
 *   boolean:        {"type":"boolean","value":true}
 *   pct_rollout:    {"type":"pct_rollout","pct":50,"salt":"abc"}
 *   targeting:      {"type":"targeting","rules":[{...},{...}], "default":false}
 *
 * Rules are AND'd (every rule must match).
 */
public record FlagDefinition(
        String key,
        String type,
        Boolean booleanValue,
        Integer pct,
        String salt,
        List<Rule> rules,
        Boolean defaultValue
) {
    public boolean isBoolean()       { return "boolean".equals(type); }
    public boolean isPctRollout()    { return "pct_rollout".equals(type); }
    public boolean isTargeting()     { return "targeting".equals(type); }

    public record Rule(
            String attribute,
            String op,
            List<Object> values
    ) {}

    /** From raw Map (parsed from JSON). */
    @SuppressWarnings("unchecked")
    public static FlagDefinition fromMap(String key, Map<String, Object> m) {
        String type = (String) m.get("type");
        Boolean bool = (Boolean) m.get("value");
        Integer pct = m.get("pct") == null ? null : ((Number) m.get("pct")).intValue();
        String salt = (String) m.get("salt");
        Boolean def = (Boolean) m.get("default");
        List<Rule> rules = null;
        Object rawRules = m.get("rules");
        if (rawRules instanceof List<?> list) {
            rules = list.stream().map(r -> {
                Map<String, Object> rm = (Map<String, Object>) r;
                List<Object> vs = rm.get("values") instanceof List<?> vlist
                        ? (List<Object>) vlist : List.of();
                return new Rule(
                        (String) rm.get("attribute"),
                        (String) rm.get("op"),
                        vs
                );
            }).toList();
        }
        return new FlagDefinition(key, type, bool, pct, salt, rules, def);
    }
}