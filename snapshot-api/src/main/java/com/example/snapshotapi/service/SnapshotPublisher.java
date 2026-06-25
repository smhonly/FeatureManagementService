package com.example.snapshotapi.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes Pub/Sub notifications to SDKs after a snapshot has been rebuilt.
 *
 * The design flow:
 *   Management API → PG → CDC → Kafka → Consumer → Redis → Pub/Sub → SDK
 *
 * SnapshotBuilderService (the "Consumer" role) calls {@link #notifySnapshotUpdated}
 * after writing the new snapshot to Redis. SDKs subscribed to this channel
 * then call GET /snapshot to pull the latest version.
 *
 * This is the "push" side of the push+poll dual channel (design §3).
 */
@Component
public class SnapshotPublisher {

    private static final Logger log = LoggerFactory.getLogger(SnapshotPublisher.class);

    private final StringRedisTemplate redis;

    public SnapshotPublisher(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * Notify SDKs that a new snapshot is available for (env, appId).
     * SDKs subscribed to "snapshot_updated:{env}" will wake up and
     * call GET /snapshot to fetch the latest data.
     */
    public void notifySnapshotUpdated(String env, int appId) {
        try {
            redis.convertAndSend("snapshot_updated:" + env, Integer.toString(appId));
        } catch (Exception e) {
            // Pub/Sub is best-effort; SDK polling timer guarantees eventual consistency
            log.error("Pub/Sub send failed: {}", e.getMessage(), e);
        }
    }
}
