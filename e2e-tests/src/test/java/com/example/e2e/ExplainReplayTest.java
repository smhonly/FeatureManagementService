package com.example.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic replay via {@code GET /api/v1/explain} (design §6.6).
 *
 * <p>The test creates a flag, updates it, then calls /explain to verify
 * the latest version is returned.  It also verifies correct handling of
 * missing flags and pre-creation timestamps.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExplainReplayTest extends E2eTestBase {

    private final ObjectMapper objectMapper = new ObjectMapper();

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
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());
        return resp.getBody();
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
                mgmtUrl + "/api/v1/flags/" + key, HttpMethod.PUT, req, Map.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        return resp.getBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> explain(String flagKey, String userId, Instant at, String userAttrsJson) {
        URI uri = explainUri(flagKey, userId, at.toString(), userAttrsJson);
        ResponseEntity<String> raw = http.getForEntity(uri, String.class);
        assertEquals(HttpStatus.OK, raw.getStatusCode(),
                "/explain failed: " + raw.getBody());
        try {
            return objectMapper.readValue(raw.getBody(), Map.class);
        } catch (Exception e) {
            throw new RuntimeException("failed to parse /explain response", e);
        }
    }

    private void explainExpecting(int expectedStatus,
                                  String flagKey, String userId, String at, String userAttrsJson) {
        URI uri = explainUri(flagKey, userId, at, userAttrsJson);
        ResponseEntity<Void> resp = http.getForEntity(uri, Void.class);
        assertEquals(expectedStatus, resp.getStatusCode().value(),
                "/explain expected " + expectedStatus + " but got " + resp.getStatusCode());
    }

    private URI explainUri(String flagKey, String userId, String at, String userAttrsJson) {
        String qs = "?flag=" + encode(flagKey)
                + "&user=" + encode(userId)
                + "&at=" + encode(at)
                + "&userAttrs=" + encode(userAttrsJson);
        return URI.create(mgmtUrl + "/api/v1/explain" + qs);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ---------- tests ----------

    @Test
    @Order(1)
    void replayAfterUpdateReturnsLatestVersion() throws Exception {
        // v1: boolean (always true)
        createFlag("exp_1", "{\"type\":\"boolean\"}");

        // v2: targeting — only superadmin
        updateFlag("exp_1", 1, """
                {"type":"targeting",
                 "rules":[{"attribute":"role","op":"eq","values":["superadmin"]}],
                 "default":false}
                """);
        Thread.sleep(300); // let DB timestamps settle

        // /explain now → should see v2 targeting definition
        Instant now = Instant.now();
        Map<String, Object> r = explain("exp_1", "u1", now, "{\"role\":\"admin\"}");
        assertEquals(2, r.get("flagVersion"), "should replay v2 definition");
        // "admin" does NOT match "superadmin" rule → default=false
        assertEquals(false, r.get("enabled"),
                "admin should NOT match superadmin rule (v2 targeting)");
    }

    @Test
    @Order(2)
    void explainAtTimeBeforeFlagExistedReturns404() {
        explainExpecting(404, "exp_1", "u1",
                Instant.EPOCH.toString(), "{}");
    }

    @Test
    @Order(3)
    void explainWithMissingFlagReturns404() {
        explainExpecting(404, "flag_never_created", "u1",
                Instant.now().toString(), "{}");
    }
}
