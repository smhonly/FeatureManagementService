package com.example.e2e;

import com.example.sdk.model.EvaluationResult;
import com.example.sdk.model.UserContext;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The "smoke test" of the entire e2e framework.
 *
 * <p>If this test passes, the infrastructure is sound:
 * two Spring contexts sharing H2, Redis seeding, SDK over loopback HTTP,
 * and the full create→rebuild→fetch→evaluate chain.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HappyPathTest extends E2eTestBase {

    private static final UserContext TEST_USER = new UserContext("user_42", Map.of("region", "us-east-1"));

    // ---------- flag helpers ----------

    private HttpEntity<String> jsonBody(String payload) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Env", "production");
        return new HttpEntity<>(payload, headers);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> createFlag(String key, String definitionJson) {
        String payload = String.format(
                "{\"key\":\"%s\",\"env\":\"production\",\"definition\":%s,\"critical\":false}",
                key, definitionJson);
        ResponseEntity<Map> resp = http.postForEntity(
                mgmtUrl + "/api/v1/flags", jsonBody(payload), Map.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "create flag failed: " + resp.getBody());
        return resp.getBody();
    }

    private void grantScope(String flagKey) {
        var headers = new HttpHeaders();
        headers.set("X-Env", "production");
        var req = new HttpEntity<>(headers);

        ResponseEntity<Void> resp = http.exchange(
                mgmtUrl + "/api/v1/flags/" + flagKey + "/apps/" + appId,
                HttpMethod.POST, req, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "grant scope failed: " + resp.getStatusCode());
    }

    // ---------- tests ----------

    @Test
    @Order(1)
    void booleanFlagInScopeEvaluatesTrue() {
        createFlag("e2e_bool", "{\"type\":\"boolean\"}");
        grantScope("e2e_bool");
        rebuildSnapshot();
        assertTrue(sdkRefresh(), "SDK refresh should succeed");

        assertTrue(sdkClient.isEnabled("e2e_bool", TEST_USER),
                "boolean flag in scope should evaluate to true");
    }

    @Test
    @Order(2)
    void booleanFlagNotInScopeReturnsMissing() {
        // Create a flag but do NOT grant scope → it won't appear in the snapshot
        createFlag("e2e_hidden", "{\"type\":\"boolean\"}");

        // But we need to rebuild so the snapshot is current (just without this flag).
        // Rebuild only sees flags joined to flag_app_scopes, so e2e_hidden is excluded.
        rebuildSnapshot();
        assertTrue(sdkRefresh());

        EvaluationResult r = sdkClient.evaluate("e2e_hidden", TEST_USER);
        assertFalse(r.value(), "flag not in snapshot should evaluate to false (missing)");
        assertTrue(r.reason().contains("not in snapshot"),
                "reason should mention 'not in snapshot', got: " + r.reason());
    }

    @Test
    @Order(3)
    void targetingFlagWithMatchingRuleReturnsTrue() {
        createFlag("e2e_targeting", """
                {"type":"targeting",
                 "rules":[{"attribute":"role","op":"eq","values":["admin"]}],
                 "default":false}
                """);
        grantScope("e2e_targeting");
        rebuildSnapshot();
        sdkRefresh(); // fresh ETag — snapshot version didn't change

        UserContext admin = new UserContext("u_admin", Map.of("role", "admin"));
        assertTrue(sdkClient.isEnabled("e2e_targeting", admin),
                "admin user should match targeting rule");

        UserContext regular = new UserContext("u_regular", Map.of("role", "user"));
        assertFalse(sdkClient.isEnabled("e2e_targeting", regular),
                "regular user should not match, default=false");
    }

    @Test
    @Order(4)
    void pctRolloutFlagDistributesCorrectly() {
        createFlag("e2e_pct", """
                {"type":"pct_rollout","pct":30,"salt":"abc"}
                """);
        grantScope("e2e_pct");
        rebuildSnapshot();
        sdkRefresh(); // fresh ETag — snapshot version didn't change

        // Hash-based bucketing: same user → same bucket every time.
        // With pct=30, roughly 30% of users get true.
        int n = 10_000;
        int trueCount = 0;
        for (int i = 0; i < n; i++) {
            if (sdkClient.isEnabled("e2e_pct", new UserContext("user_" + i, Map.of()))) {
                trueCount++;
            }
        }
        double rate = (double) trueCount / n;
        assertTrue(rate > 0.27 && rate < 0.33,
                String.format("expected ~30%%, got %.2f%% (%d/%d)", rate * 100, trueCount, n));
    }

    @Test
    @Order(5)
    void sdkLocalEvaluationIsFast() {
        // After refresh, isEnabled() should be sub-millisecond (local, no I/O).
        // We assert p50 < 1 ms across 1000 calls.
        long start = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            sdkClient.isEnabled("e2e_bool", TEST_USER);
        }
        long totalUs = (System.nanoTime() - start) / 1000;
        long avgUs = totalUs / 1000;
        assertTrue(avgUs < 50,
                "local evaluation avg should be < 50 µs, got " + avgUs + " µs");
    }

    @Test
    @Order(6)
    void mergePathWithoutPgSweep() {
        // Design B: use mergeFlag (Consumer path) instead of rebuildSnapshot (sweep path).
        // Create flag via HTTP, then merge directly — no PG scope query needed.
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Env", "production");
        String payload = """
                {"key":"e2e_merge","env":"production","definition":{"type":"boolean"},"critical":false}
                """;
        http.postForEntity(mgmtUrl + "/api/v1/flags",
                new HttpEntity<>(payload, headers), Map.class);

        // Grant scope + merge (no PG sweep)
        grantScope("e2e_merge");

        // Build a JsonNode definition to simulate the Kafka event payload
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode def = mapper.createObjectNode().put("type", "boolean");
        mergeFlag("e2e_merge", def, "created");

        // SDK refresh should now see the merged flag (merge bumps version, ETag changes)
        assertTrue(sdkRefresh());
        assertTrue(sdkClient.isEnabled("e2e_merge", TEST_USER),
                "flag merged via Design B path should be visible to SDK");
    }
}
