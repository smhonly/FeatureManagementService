package com.example.sdk;

import com.example.sdk.cache.InMemoryFlagCache;
import com.example.sdk.evaluator.FlagEvaluator;
import com.example.sdk.metrics.FeatureFlagMetrics;
import com.example.sdk.model.EvaluationResult;
import com.example.sdk.model.FlagDefinition;
import com.example.sdk.model.UserContext;
import com.example.sdk.sync.PollingSyncController;
import com.example.sdk.sync.PubSubSyncController;
import com.example.sdk.transport.SnapshotFetcher;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;

/**
 * SDK entry point. One instance per process.
 *
 * <p>Wires up everything an app needs:
 * <ul>
 *   <li>local cache of flag definitions</li>
 *   <li>background polling (default 30 s, design §3 "Channel 2")</li>
 *   <li>optional Redis Pub/Sub push (design §3 "Channel 1", sub-second)</li>
 *   <li>in-process metrics</li>
 * </ul>
 *
 * <p>App code only sees {@code isEnabled()}; everything else is encapsulated:
 *
 * <pre>{@code
 *   try (FeatureFlagClient client = FeatureFlagClient.builder()
 *           .baseUrl("https://ff.example.com")
 *           .apiKey("sk_live_xxx")
 *           .env("production")
 *           .build()) {
 *       if (client.isEnabled("new_checkout", user)) { ... }
 *   }
 * }</pre>
 *
 * <p>{@link #close()} (or try-with-resources) shuts down the polling timer
 * and the pub/sub subscriber.
 */
public class FeatureFlagClient implements AutoCloseable {

    private final InMemoryFlagCache cache;
    private final SnapshotFetcher fetcher;
    private final FeatureFlagMetrics metrics = new FeatureFlagMetrics();
    private final ObjectMapper json = new ObjectMapper();
    private final Object refreshLock = new Object();

    // Non-final so the Builder can wire them up after construction (the
    // controllers need `client::refresh`, but `client` doesn't exist until
    // the constructor returns). Set once via attachControllers(), never
    // mutated after that.
    private PollingSyncController polling;
    private PubSubSyncController pubsub;
    private volatile boolean closed = false;

    private FeatureFlagClient(InMemoryFlagCache cache, SnapshotFetcher fetcher) {
        this.cache = cache;
        this.fetcher = fetcher;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** Builder-only: wire the controllers after the client exists so the
     *  controllers can capture {@code client::refresh}. */
    private void attachControllers(PollingSyncController polling, PubSubSyncController pubsub) {
        this.polling = polling;
        this.pubsub = pubsub;
    }

    /**
     * One-shot manual refresh. Thread-safe. Normally you don't need to call
     * this — the polling controller calls it on a schedule. Useful in tests
     * or to force a fetch right after a config change.
     */
    public boolean refresh() {
        synchronized (refreshLock) {
            SnapshotFetcher.Result r = fetcher.fetch();
            if (!r.ok()) return false;
            if (r.unchanged) return true;
            Map<String, FlagDefinition> next = new HashMap<>();
            r.flagsByKey.forEach((key, node) -> {
                Map<String, Object> def = json.convertValue(node.get("definition"), Map.class);
                next.put(key, FlagDefinition.fromMap(key, def));
            });
            cache.replaceAll(next);
            metrics.recordSuccessfulRefresh();
            return true;
        }
    }

    /** Local evaluation. Never blocks on I/O. */
    public boolean isEnabled(String flagKey, UserContext ctx) {
        return evaluate(flagKey, ctx).value();
    }

    public EvaluationResult evaluate(String flagKey, UserContext ctx) {
        long start = System.nanoTime();
        EvaluationResult r = cache.get(flagKey)
                .map(def -> FlagEvaluator.evaluate(def, ctx))
                .orElse(EvaluationResult.MISSING);
        long elapsedUs = (System.nanoTime() - start) / 1000;
        metrics.recordEvaluation(flagKey, r.value(), elapsedUs);
        return r;
    }

    public InMemoryFlagCache cache() { return cache; }
    public SnapshotFetcher fetcher() { return fetcher; }
    public FeatureFlagMetrics metrics() { return metrics; }

    /**
     * Stop the background polling timer and pub/sub subscriber.
     * Safe to call multiple times. try-with-resources calls this automatically.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (polling != null) polling.stop();
        if (pubsub != null) pubsub.close();
    }

    public static class Builder {
        private String baseUrl;
        private String apiKey;
        private String env = "production";
        private InMemoryFlagCache cache = new InMemoryFlagCache();

        /** Polling interval in seconds. Default 30. Set to 0 to disable. */
        private int pollingSeconds = 30;
        /** If non-null, enables the Redis Pub/Sub push channel. */
        private String pubsubHost = null;
        private int pubsubPort = 6379;
        /** If true, build() blocks on the first snapshot fetch before returning. */
        private boolean initialRefreshSync = false;

        public Builder baseUrl(String url) { this.baseUrl = url; return this; }
        public Builder apiKey(String k)    { this.apiKey = k; return this; }
        public Builder env(String e)       { this.env = e; return this; }
        public Builder cache(InMemoryFlagCache c) { this.cache = c; return this; }

        /** Override polling interval (seconds). 0 disables polling. */
        public Builder pollingSeconds(int s) { this.pollingSeconds = s; return this; }
        /** Disable polling entirely (push channel only, or manual refresh). */
        public Builder disablePolling() { this.pollingSeconds = 0; return this; }

        /** Enable Redis Pub/Sub push channel. Falls back silently if Jedis
         *  isn't on the classpath — polling still runs as the safety net. */
        public Builder pubsub(String host, int port) {
            this.pubsubHost = host;
            this.pubsubPort = port;
            return this;
        }

        /**
         * If true, {@link #build()} blocks until the first snapshot fetch
         * completes (success or failure). Default false: build() returns
         * immediately, polling fills the cache in the background.
         */
        public Builder initialRefreshSync() {
            this.initialRefreshSync = true;
            return this;
        }

        public FeatureFlagClient build() {
            if (baseUrl == null || apiKey == null) {
                throw new IllegalStateException("baseUrl and apiKey are required");
            }
            SnapshotFetcher fetcher = new SnapshotFetcher(baseUrl, apiKey, env);
            FeatureFlagClient client = new FeatureFlagClient(cache, fetcher);

            PollingSyncController polling = pollingSeconds > 0
                    ? new PollingSyncController(client::refresh, pollingSeconds)
                    : null;
            PubSubSyncController pubsub = pubsubHost != null
                    ? new PubSubSyncController(client::refresh, pubsubHost, pubsubPort)
                    : null;
            client.attachControllers(polling, pubsub);

            // Initial fetch: sync blocks startup, async (default) doesn't.
            if (initialRefreshSync) {
                client.refresh();
            }
            // Always kick off a background initial fetch too — if sync failed
            // or wasn't requested, polling will get us the first snapshot.
            if (polling != null) polling.start();
            if (pubsub != null) pubsub.start();

            return client;
        }
    }
}