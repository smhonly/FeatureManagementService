package com.example.sdk.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * HTTP transport for /api/v1/snapshot.
 *
 * Sends If-None-Match on subsequent calls; if server replies 304,
 * returns the previous ETag (caller keeps the existing snapshot).
 *
 * Pure Java (java.net.http); no Spring, no external deps.
 */
public class SnapshotFetcher {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper json = new ObjectMapper();
    private final URI snapshotUri;
    private final String apiKey;
    private final String env;
    private String lastEtag = null;

    public SnapshotFetcher(String baseUrl, String apiKey, String env) {
        this.snapshotUri = URI.create(baseUrl + "/api/v1/snapshot");
        this.apiKey = apiKey;
        this.env = env;
    }

    public Result fetch() {
        try {
            //only fetch env+app flags
            HttpRequest.Builder req = HttpRequest.newBuilder(snapshotUri)
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-Env", env)
                    .GET();
            if (lastEtag != null) req.header("If-None-Match", lastEtag);

            HttpResponse<String> resp = http.send(req.build(), HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 304) {
                return Result.unchanged(lastEtag);
            }
            if (resp.statusCode() != 200) {
                return Result.error("HTTP " + resp.statusCode());
            }

            String etag = resp.headers().firstValue("ETag").orElse(null);
            lastEtag = etag;
            JsonNode body = json.readTree(resp.body());
            Map<String, JsonNode> byKey = new LinkedHashMap<>();
            JsonNode arr = body.get("flags");
            if (arr != null && arr.isArray()) {
                for (JsonNode f : arr) {
                    byKey.put(f.get("key").asText(), f);
                }
            }
            return Result.ok(etag, body, byKey);
        } catch (Exception e) {
            return Result.error(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    public Optional<String> lastEtag() { return Optional.ofNullable(lastEtag); }

    public static final class Result {
        public final boolean unchanged;
        public final String etag;
        public final String error;
        public final JsonNode raw;
        public final Map<String, JsonNode> flagsByKey;

        private Result(boolean unchanged, String etag, String error,
                       JsonNode raw, Map<String, JsonNode> flagsByKey) {
            this.unchanged = unchanged;
            this.etag = etag;
            this.error = error;
            this.raw = raw;
            this.flagsByKey = flagsByKey;
        }

        public static Result unchanged(String etag) {
            return new Result(true, etag, null, null, Map.of());
        }
        public static Result ok(String etag, JsonNode raw, Map<String, JsonNode> byKey) {
            return new Result(false, etag, null, raw, byKey);
        }
        public static Result error(String msg) {
            return new Result(false, null, msg, null, Map.of());
        }

        public boolean ok() { return error == null; }
    }
}