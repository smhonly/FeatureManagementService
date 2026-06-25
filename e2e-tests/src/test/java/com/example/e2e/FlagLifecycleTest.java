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
 * Flag lifecycle: create → update → archive.
 *
 * <p>Verifies that changes made through the control plane propagate
 * to the data plane and eventually to the SDK's local cache.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FlagLifecycleTest extends E2eTestBase {

    private static final UserContext TEST_USER = new UserContext("u1", Map.of("role", "admin"));

    // ---------- helpers ----------

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
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> updateFlag(String key, int expectedVersion, String definitionJson) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Env", "production");
        headers.set("If-Match", "\"" + expectedVersion + "\"");

        String payload = String.format("{\"definition\":%s}", definitionJson);
        var req = new HttpEntity<>(payload, headers);

        ResponseEntity<Map> resp = http.exchange(
                mgmtUrl + "/api/v1/flags/" + key,
                HttpMethod.PUT, req, Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "update failed: " + resp.getBody());
        return resp.getBody();
    }

    private void updateExpect412(String key, int expectedVersion, String definitionJson) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Env", "production");
        headers.set("If-Match", "\"" + expectedVersion + "\"");

        String payload = String.format("{\"definition\":%s}", definitionJson);
        var req = new HttpEntity<>(payload, headers);

        ResponseEntity<Void> resp = http.exchange(
                mgmtUrl + "/api/v1/flags/" + key,
                HttpMethod.PUT, req, Void.class);
        assertEquals(HttpStatus.PRECONDITION_FAILED, resp.getStatusCode(),
                "expected 412, got " + resp.getStatusCode());
    }

    private void archiveFlag(String key, int expectedVersion) {
        var headers = new HttpHeaders();
        headers.set("X-Env", "production");
        headers.set("If-Match", "\"" + expectedVersion + "\"");
        var req = new HttpEntity<>(headers);

        ResponseEntity<Void> resp = http.exchange(
                mgmtUrl + "/api/v1/flags/" + key,
                HttpMethod.DELETE, req, Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode(),
                "archive failed: " + resp.getStatusCode());
    }

    // ---------- tests ----------

    @Test
    @Order(1)
    void updatePropagatesToSdk() {
        // Create boolean flag → SDK sees boolean (always true)
        createFlag("life_bool", "{\"type\":\"boolean\"}");
        grantScope("life_bool");
        rebuildSnapshot();
        assertTrue(sdkRefresh());
        assertTrue(sdkClient.isEnabled("life_bool", TEST_USER));

        // Update to targeting with rule that doesn't match this user
        updateFlag("life_bool", 1, """
                {"type":"targeting",
                 "rules":[{"attribute":"role","op":"eq","values":["superadmin"]}],
                 "default":false}
                """);
        rebuildSnapshot();
        assertTrue(sdkRefresh(), "SDK should see new snapshot version after update");

        // admin user should NOT match "superadmin" rule → falls to default=false
        assertFalse(sdkClient.isEnabled("life_bool", TEST_USER),
                "after update to superadmin-only targeting, admin user should get false");
    }

    @Test
    @Order(2)
    void archiveRemovesFlagFromSnapshot() {
        createFlag("life_doomed", "{\"type\":\"boolean\"}");
        grantScope("life_doomed");
        rebuildSnapshot();
        assertTrue(sdkRefresh());
        assertTrue(sdkClient.isEnabled("life_doomed", TEST_USER));

        // Archive it (version is now 1, archive bumps to 2)
        archiveFlag("life_doomed", 1);
        rebuildSnapshot();
        assertTrue(sdkRefresh());

        EvaluationResult r = sdkClient.evaluate("life_doomed", TEST_USER);
        assertFalse(r.value(), "archived flag should be absent from snapshot → missing");
        assertTrue(r.reason().contains("not in snapshot"),
                "reason: " + r.reason());
    }

    @Test
    @Order(3)
    void updateWithStaleVersionReturns412() {
        createFlag("life_stale", "{\"type\":\"boolean\"}");
        grantScope("life_stale");

        // Version is 1, but we pass 99 → should get 412
        updateExpect412("life_stale", 99, "{\"type\":\"boolean\"}");
    }

    @Test
    @Order(4)
    void updateWithCorrectVersionBumpsVersion() {
        createFlag("life_ver", "{\"type\":\"boolean\"}");
        grantScope("life_ver");

        // Update with correct version 1
        var result = updateFlag("life_ver", 1, """
                {"type":"targeting","rules":[],"default":false}
                """);
        assertEquals(2, result.get("version"), "version should bump from 1 to 2");

        // Subsequent update with stale version should fail
        updateExpect412("life_ver", 1, "{\"type\":\"boolean\"}");
    }
}
