package com.example.snapshotapi.api;

import com.example.snapshotapi.store.ApplicationRegistry;
import com.example.snapshotapi.store.SnapshotStore;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.concurrent.TimeUnit;

/**
 * Per-app snapshot endpoint. Maps to docs/design.md §6.1.
 *
 *   GET /api/v1/snapshot
 *     Headers:
 *       Authorization: Bearer <api_key>
 *       X-Env: production
 *       If-None-Match: "<version>"   (optional; 304 if unchanged)
 */
@RestController
@RequestMapping("/api/v1/snapshot")
public class SnapshotController {

    private final ApplicationRegistry apps;
    private final SnapshotStore snapshots;

    public SnapshotController(ApplicationRegistry apps, SnapshotStore snapshots) {
        this.apps = apps;
        this.snapshots = snapshots;
    }

    @GetMapping
    public ResponseEntity<JsonNode> snapshot(
            @RequestHeader(value = "Authorization", required = false) String auth,
            @RequestHeader(value = "X-Env", defaultValue = "production") String env,
            @RequestHeader(value = "If-None-Match", required = false) String ifNoneMatch) {

        String apiKey = stripBearer(auth);
        JsonNode app = apps.resolve(apiKey)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "unknown api_key"));

        int appId = app.get("app_id").asInt();
        SnapshotStore.Entry entry = snapshots.get(env, appId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "no snapshot for env=" + env + " app=" + appId));

        String etag = "\"" + entry.version() + "\"";

        // CDN cache policy (design §6.1): cache per (env, app) for 60s.
        // max-age=0 for browsers (don't cache), s-maxage=60 for CDN edge.
        // At 1,000+ apps, layer by hotness: hot apps get CDN, cold apps go to origin.
        CacheControl cdnCache = CacheControl.maxAge(0, TimeUnit.SECONDS)
                .cachePublic()
                .sMaxAge(60, TimeUnit.SECONDS);

        if (ifNoneMatch != null && stripQuotes(ifNoneMatch).equals(Integer.toString(entry.version()))) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(cdnCache)
                    .build();
        }

        return ResponseEntity.ok()
                .eTag(etag)
                .cacheControl(cdnCache)
                .header("Surrogate-Control", "max-age=60")
                .body(entry.data());
    }

    private static String stripBearer(String auth) {
        if (auth == null) return null;
        String trimmed = auth.trim();
        if (trimmed.toLowerCase().startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return trimmed;
    }

    private static String stripQuotes(String s) {
        return s.replace("\"", "").trim();
    }
}
