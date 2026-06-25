package com.example.sdk;

import com.example.sdk.evaluator.FlagEvaluator;
import com.example.sdk.model.FlagDefinition;
import com.example.sdk.model.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Cross-implementation conformance vectors (design §4).
 *
 * Each vector is a self-contained scenario: same input → same output
 * regardless of SDK language. Other language SDKs run the same vectors.
 */
class ConformanceVectorsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void allVectorsMatch() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/conformance-vectors.json")) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> vectors = (List<Map<String, Object>>)
                    json.readValue(in, Map.class).get("vectors");
            assertEquals(13, vectors.size(), "expected 13 vectors in conformance file");

            for (Map<String, Object> v : vectors) {
                String name = (String) v.get("name");
                @SuppressWarnings("unchecked")
                Map<String, Object> defMap = (Map<String, Object>) v.get("definition");
                String key = (String) defMap.get("key");
                FlagDefinition def = FlagDefinition.fromMap(key, defMap);

                @SuppressWarnings("unchecked")
                Map<String, Object> ctxMap = (Map<String, Object>) v.get("context");
                String userId = (String) ctxMap.get("userId");
                @SuppressWarnings("unchecked")
                Map<String, Object> attrs = (Map<String, Object>) ctxMap.get("attributes");
                UserContext ctx = new UserContext(userId, attrs);

                boolean expected = (boolean) v.get("expected");
                boolean actual = FlagEvaluator.evaluate(def, ctx).value();
                assertEquals(expected, actual,
                        "vector '" + name + "' failed");
            }
        }
    }
}