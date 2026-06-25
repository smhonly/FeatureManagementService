package com.example.e2e;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Snapshot API authentication — api_key → HMAC → Redis lookup.
 *
 * <p>The {@link E2eTestBase} already seeds Redis with our test app,
 * so the inherited {@code apiKey} works.  We also test invalid and
 * missing credentials.</p>
 */
class AuthFlowTest extends E2eTestBase {

    @Test
    void validApiKeyWithoutSnapshotPassesAuth() {
        // A valid api_key should pass auth.  Whether a snapshot exists
        // yet is immaterial — the key point is it's NOT 401.
        var headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("X-Env", "production");

        ResponseEntity<String> resp = http.exchange(
                snapUrl + "/api/v1/snapshot",
                HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertTrue(resp.getStatusCode().value() != 401,
                "valid api_key should not get 401, got: " + resp.getBody());
    }

    @Test
    void validApiKeyWithSnapshotReturns200() {
        // Create a flag
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Env", "production");
        String payload = """
                {"key":"auth_flag","env":"production","definition":{"type":"boolean"},"critical":false}
                """;
        http.postForEntity(mgmtUrl + "/api/v1/flags",
                new HttpEntity<>(payload, headers), java.util.Map.class);

        // Grant scope via HTTP (bug 1 fixed)
        var scopeHeaders = new HttpHeaders();
        scopeHeaders.set("X-Env", "production");
        http.exchange(mgmtUrl + "/api/v1/flags/auth_flag/apps/" + appId,
                HttpMethod.POST, new HttpEntity<>(scopeHeaders), Void.class);
        rebuildSnapshot();

        // Now GET /snapshot should return 200
        var authHeaders = new HttpHeaders();
        authHeaders.set("Authorization", "Bearer " + apiKey);
        authHeaders.set("X-Env", "production");
        ResponseEntity<Void> resp = http.exchange(
                snapUrl + "/api/v1/snapshot",
                HttpMethod.GET, new HttpEntity<>(authHeaders), Void.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "valid api_key with snapshot should return 200");
    }

    @Test
    void invalidApiKeyReturns401() {
        // This key was never registered — its hash won't match any Redis key
        var headers = new HttpHeaders();
        headers.set("Authorization", "Bearer sk_live_never_registered_key_xxxxx");
        headers.set("X-Env", "production");

        ResponseEntity<Void> resp = http.exchange(
                snapUrl + "/api/v1/snapshot",
                HttpMethod.GET, new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "unknown api_key should return 401");
    }

    @Test
    void missingAuthorizationHeaderReturns401() {
        var headers = new HttpHeaders();
        headers.set("X-Env", "production");

        ResponseEntity<Void> resp = http.exchange(
                snapUrl + "/api/v1/snapshot",
                HttpMethod.GET, new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode(),
                "missing Authorization header should return 401");
    }
}
