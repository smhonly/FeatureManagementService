package com.example.e2e;

import com.example.managementapi.ManagementApiApplication;
import com.example.sdk.FeatureFlagClient;
import com.example.snapshotapi.SnapshotApiApplication;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestTemplate;
import redis.embedded.RedisServer;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Abstract base for all e2e tests.
 *
 * <p>Starts management-api and snapshot-api in the same JVM on random ports,
 * sharing an H2 in-memory database.  Seeds Redis so the snapshot-api can
 * resolve the app's api_key (management-api writes only to PG; this is a
 * known gap bridged here — no source changes needed).</p>
 *
 * <p>Level 1: no Docker, no Kafka, no Pub/Sub.  If no Redis is listening
 * on localhost:6379, an embedded Redis server is started automatically.</p>
 *
 * <p>Concrete test classes inherit appId, apiKey, sdkClient, and the two
 * base URLs.  Typical test flow:</p>
 * <pre>
 *   1. POST /api/v1/flags               (management-api)
 *   2. POST /api/v1/flags/{key}/apps/{id} (grant scope)
 *   3. rebuildForApp(env, appId)          (snapshot-api, triggered manually)
 *   4. sdkClient.refresh()
 *   5. assertTrue(sdkClient.isEnabled(key, user))
 * </pre>
 */
public abstract class E2eTestBase {

    // ---------- shared secrets ----------

    static final String SERVER_SECRET = "test-secret-32-bytes-of-pad!";

    // ---------- infrastructure ----------

    private static RedisServer embeddedRedis;
    private static int redisPort;
    /** Unique H2 DB name per test class — prevents cross-class data leaking. */
    private static String h2Url;

    // ---------- Spring contexts ----------

    protected static ConfigurableApplicationContext mgmtCtx;
    protected static ConfigurableApplicationContext snapCtx;

    protected static int mgmtPort;
    protected static int snapPort;
    protected static String mgmtUrl;   // "http://localhost:${mgmtPort}"
    protected static String snapUrl;   // "http://localhost:${snapPort}"

    // ---------- test data (shared per class) ----------

    protected static int appId;
    protected static String apiKey;

    // ---------- clients ----------

    protected static FeatureFlagClient sdkClient;

    /** RestTemplate that does NOT throw on 4xx/5xx — let the caller inspect the status. */
    protected static final RestTemplate http = new RestTemplate();
    static {
        http.setErrorHandler(new ResponseErrorHandler() {
            @Override public boolean hasError(ClientHttpResponse r) { return false; }
            @Override public void handleError(ClientHttpResponse r) {}
        });
    }

    // ---------- lifecycle ----------

    @BeforeAll
    static void startServices() {
        startRedisIfNeeded();

        // Unique DB per class so parallel or sequential test classes don't collide
        h2Url = "jdbc:h2:mem:" + java.util.UUID.randomUUID().toString().replace("-", "")
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

        // ── 1. Start management-api (Flyway creates the shared H2 tables) ──
        var mgmtApp = new SpringApplication(ManagementApiApplication.class);
        mgmtCtx = mgmtApp.run(
                "--server.port=0",
                "--spring.datasource.url=" + h2Url,
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.enabled=true",
                "--ff.server-secret=" + SERVER_SECRET,
                "--ff.auth.jwt-enabled=false",
                "--ff.auth.hmac-enabled=false",
                "--spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration"
        );
        mgmtPort = Integer.parseInt(mgmtCtx.getEnvironment().getProperty("local.server.port"));
        mgmtUrl = "http://localhost:" + mgmtPort;

        // ── 2. Start snapshot-api (reuses the H2 tables from step 1) ──
        var snapApp = new SpringApplication(SnapshotApiApplication.class);
        snapCtx = snapApp.run(
                "--server.port=0",
                "--spring.datasource.url=" + h2Url,
                "--spring.datasource.username=sa",
                "--spring.datasource.password=",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.flyway.enabled=false",
                "--spring.data.redis.host=localhost",
                "--spring.data.redis.port=" + redisPort,
                "--spring.kafka.bootstrap-servers=localhost:19999",
                "--spring.kafka.listener.auto-startup=false",
                "--ff.server-secret=" + SERVER_SECRET
        );
        snapPort = Integer.parseInt(snapCtx.getEnvironment().getProperty("local.server.port"));
        snapUrl = "http://localhost:" + snapPort;

        // ── 3. Register an application (returns raw api_key once) ──
        registerApp();

        // ── 4. Seed Redis: write app record so snapshot-api can resolve the api_key ──
        seedRedisAppRecord();

        // ── 5. Initialise SDK (polls snapUrl via loopback HTTP) ──
        sdkClient = FeatureFlagClient.builder()
                .baseUrl(snapUrl)
                .apiKey(apiKey)
                .env("production")
                .pollingSeconds(30)
                .build();

        System.out.printf("[e2e] management-api : %s%n", mgmtUrl);
        System.out.printf("[e2e] snapshot-api   : %s%n", snapUrl);
        System.out.printf("[e2e] app_id=%d  api_key_prefix=%s...  redis=embedded:%d%n",
                appId, apiKey.substring(0, 12), redisPort);
    }

    @AfterAll
    static void stopServices() {
        if (sdkClient != null) sdkClient.close();
        if (snapCtx != null) snapCtx.close();
        if (mgmtCtx != null) mgmtCtx.close();
        if (embeddedRedis != null) {
            try { embeddedRedis.stop(); } catch (java.io.IOException ignored) {}
        }
    }

    // ---------- helpers ----------

    /** Rebuild the snapshot for our test app (full sweep path, queries PG). */
    protected static void rebuildSnapshot() {
        var builder = snapCtx.getBean(
                com.example.snapshotapi.service.SnapshotBuilderService.class);
        builder.rebuildForApp("production", appId);
    }

    /**
     * Merge a single flag into the snapshot (Design B path — no PG query).
     * Simulates the Kafka Consumer receiving an event with definition+scopedAppIds.
     */
    protected static int mergeFlag(String flagKey,
                                   com.fasterxml.jackson.databind.JsonNode definition,
                                   String op) {
        var builder = snapCtx.getBean(
                com.example.snapshotapi.service.SnapshotBuilderService.class);
        return builder.mergeFlagForApp("production", appId, flagKey, definition, op);
    }

    /** Force SDK to fetch from snapshot-api now. */
    protected static boolean sdkRefresh() {
        return sdkClient.refresh();
    }

    // ---------- internal ----------

    private static void startRedisIfNeeded() {
        try {
            redisPort = findFreePort();
            embeddedRedis = new RedisServer(redisPort);
            embeddedRedis.start();
            System.out.println("[e2e] Started embedded Redis on port " + redisPort);
        } catch (Exception e) {
            throw new RuntimeException(
                    "Failed to start embedded Redis: " + e.getMessage(), e);
        }
    }

    private static int findFreePort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            return s.getLocalPort();
        } catch (java.io.IOException e) {
            throw new RuntimeException("Failed to find a free port for Redis", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void registerApp() {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        String body = """
                {"team":"e2e-test","name":"e2e-svc","envScope":"production"}
                """;
        var req = new HttpEntity<>(body, headers);

        ResponseEntity<Map> resp = http.postForEntity(
                mgmtUrl + "/api/v1/admin/applications", req, Map.class);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode(),
                "app registration failed: " + resp.getBody());

        Map<String, Object> data = resp.getBody();
        appId = (int) data.get("appId");
        apiKey = (String) data.get("rawApiKey");
    }

    private static void seedRedisAppRecord() {
        String hash = hmacSha256Hex(SERVER_SECRET, apiKey);
        String appJson = String.format(
                "{\"app_id\":%d,\"team\":\"e2e-test\",\"name\":\"e2e-svc\",\"env_scope\":\"production\"}",
                appId);

        var redis = snapCtx.getBean(StringRedisTemplate.class);
        redis.opsForValue().set("app:" + hash, appJson);
    }

    /** HMAC-SHA256 → hex.  Must match
     *  {@code com.example.snapshotapi.auth.ApiKeyHasher}
     *  and {@code com.example.managementapi.crypto.HmacSha256}. */
    static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : raw) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
