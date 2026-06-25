package com.example.e2e;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * ETag-based caching (design §6.1).
 *
 * <p>After the snapshot version fix (Redis INCR), every rebuild bumps the
 * version monotonically.  Unchanged ETag → 304, new ETag → 200 + body.</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EtagCachingTest extends E2eTestBase {

    private static String lastEtag;

    @Test
    @Order(1)
    void initialFetchReturns200AndEtag() {
        // Create a flag so a snapshot exists
        createFlag("etag_1", "{\"type\":\"boolean\"}");
        grantScope("etag_1");
        rebuildSnapshot();

        ResponseEntity<String> resp = fetchSnapshot(null);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        lastEtag = resp.getHeaders().getETag();
        assertNotNull(lastEtag, "first fetch should return an ETag");
    }

    @Test
    @Order(2)
    void unchangedEtagReturns304() {
        ResponseEntity<String> resp = fetchSnapshot(lastEtag);
        assertEquals(HttpStatus.NOT_MODIFIED, resp.getStatusCode(),
                "same ETag should return 304");
    }

    @Test
    @Order(3)
    void changedEtagReturns200WithNewEtag() {
        // Add another flag → snapshot version increments
        createFlag("etag_2", "{\"type\":\"boolean\"}");
        grantScope("etag_2");
        rebuildSnapshot();

        ResponseEntity<String> resp = fetchSnapshot(lastEtag);
        assertEquals(HttpStatus.OK, resp.getStatusCode(),
                "different ETag (version incremented) should return 200");
        String newEtag = resp.getHeaders().getETag();
        assertNotNull(newEtag);
        // ETag values are monotonic integers as strings (after INCR fix)
        int oldVer = Integer.parseInt(lastEtag.replace("\"", ""));
        int newVer = Integer.parseInt(newEtag.replace("\"", ""));
        assertEquals(oldVer + 1, newVer,
                "version should increment by exactly 1");
        lastEtag = newEtag;
    }

    // ---------- helpers ----------

    private ResponseEntity<String> fetchSnapshot(String ifNoneMatch) {
        var headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + apiKey);
        headers.set("X-Env", "production");
        if (ifNoneMatch != null) {
            headers.set("If-None-Match", ifNoneMatch);
        }
        return http.exchange(
                snapUrl + "/api/v1/snapshot",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);
    }

    private void createFlag(String key, String definitionJson) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Env", "production");
        String payload = String.format(
                "{\"key\":\"%s\",\"env\":\"production\",\"definition\":%s,\"critical\":false}",
                key, definitionJson);
        http.postForEntity(mgmtUrl + "/api/v1/flags",
                new HttpEntity<>(payload, headers), java.util.Map.class);
    }

    private void grantScope(String flagKey) {
        var headers = new HttpHeaders();
        headers.set("X-Env", "production");
        ResponseEntity<Void> resp = http.exchange(
                mgmtUrl + "/api/v1/flags/" + flagKey + "/apps/" + appId,
                HttpMethod.POST, new HttpEntity<>(headers), Void.class);
        assertEquals(HttpStatus.NO_CONTENT, resp.getStatusCode());
    }
}
