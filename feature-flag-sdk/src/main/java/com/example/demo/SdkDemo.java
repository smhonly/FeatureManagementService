package com.example.demo;

import com.example.sdk.FeatureFlagClient;
import com.example.sdk.model.EvaluationResult;
import com.example.sdk.model.UserContext;

import java.util.Map;

/**
 * Runnable demo showing the SDK lifecycle end-to-end.
 *
 * <p>The whole point of {@link FeatureFlagClient}'s builder is that app
 * code is three lines: build → use → close. Background polling, optional
 * pub/sub push, cache lifecycle, metrics — all encapsulated.
 *
 * <p>Usage:
 * <pre>
 *   mvn package -pl feature-flag-sdk
 *   java -DbaseUrl=http://localhost:8090 -DapiKey=sk_live_xxx -Denv=production \
 *        -jar feature-flag-sdk/target/feature-flag-sdk-0.1.0.jar
 * </pre>
 */
public class SdkDemo {

    private static final String[] FLAG_KEYS = { "new_checkout", "vip_discount", "admin_debug", "beta_feature" };

    public static void main(String[] args) {
        String baseUrl = System.getProperty("baseUrl", "http://localhost:8090");
        String apiKey  = System.getProperty("apiKey", "sk_live_demo_key");
        String env     = System.getProperty("env", "production");

        System.out.println("=== Feature Flag SDK Demo ===");
        System.out.printf("Snapshot API: %s%n", baseUrl);
        System.out.printf("Environment:  %s%n%n", env);

        // App code is exactly this: build, use, close.
        try (FeatureFlagClient client = FeatureFlagClient.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .env(env)
                .pollingSeconds(30)
                .build()) {

            // Give the background initial fetch a moment to populate the cache.
            try { Thread.sleep(500); } catch (InterruptedException ignore) {}

            System.out.printf("Cache: %d flags loaded%n%n", client.cache().size());

            System.out.println("=== Evaluation loop ===");
            for (int i = 0; i < 12; i++) {
                String flagKey = FLAG_KEYS[i % FLAG_KEYS.length];
                UserContext user = new UserContext("user_" + i, Map.of(
                        "region", i % 2 == 0 ? "us-east-1" : "eu-west-1",
                        "tenant", "enterprise",
                        "beta", i % 3 == 0
                ));
                EvaluationResult r = client.evaluate(flagKey, user);
                System.out.printf("  [%s] flag=%-20s enabled=%-5s reason=%s%n",
                        user.userId(), flagKey, r.value(), r.reason());
                try { Thread.sleep(500); } catch (InterruptedException ignore) {}
            }

            System.out.println("\n=== Metrics ===");
            client.metrics().snapshot().forEach((k, v) ->
                    System.out.printf("  %-28s = %s%n", k, v));
        }

        System.out.println("\nDemo finished.");
    }
}