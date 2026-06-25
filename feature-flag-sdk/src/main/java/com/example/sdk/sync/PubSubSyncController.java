package com.example.sdk.sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Push-based sync controller using Redis Pub/Sub.
 *
 * <p>Subscribes to {@code snapshot_updated:*}; on any message, calls
 * the refresh callback to pull the latest snapshot.
 *
 * <p>This is "Channel 1: Push" from design (Figure 3). The data path
 * is identical to polling — only the wake-up mechanism differs.
 *
 * <p>Jedis is optional ({@code <optional>true</optional>} in pom.xml).
 * If Jedis is not on the classpath, {@link #start()} prints a warning
 * and returns; the polling controller still runs as fallback.
 */
public class PubSubSyncController implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PubSubSyncController.class);

    private final Runnable refreshCallback;
    private final String redisHost;
    private final int redisPort;
    private volatile boolean running;
    private Thread subscriberThread;
    private redis.clients.jedis.Jedis jedis;
    private redis.clients.jedis.JedisPubSub subscriber;

    /** @param refreshCallback called on each pub/sub message. Must be thread-safe. */
    public PubSubSyncController(Runnable refreshCallback, String redisHost, int redisPort) {
        this.refreshCallback = refreshCallback;
        this.redisHost = redisHost;
        this.redisPort = redisPort;
    }

    /** Start listening. Safe to call multiple times. */
    public synchronized void start() {
        if (running) return;

        // Check if Jedis is available on the classpath
        try {
            Class.forName("redis.clients.jedis.Jedis");
        } catch (ClassNotFoundException e) {
            log.warn("Jedis not on classpath — push channel disabled");
            return;
        }

        running = true;
        subscriberThread = new Thread(this::runSubscription, "ff-pubsub-sync");
        subscriberThread.setDaemon(true);
        subscriberThread.start();
    }

    private void runSubscription() {
        try {
            jedis = new redis.clients.jedis.Jedis(redisHost, redisPort);
            subscriber = new redis.clients.jedis.JedisPubSub() {
                @Override
                public void onPMessage(String pattern, String channel, String message) {
                    try {
                        refreshCallback.run();
                    } catch (Exception e) {
                        log.error("push refresh failed", e);
                    }
                }
            };
            // Blocks until unsubscribe()/punsubscribe() is called on `subscriber`.
            jedis.psubscribe(subscriber, "snapshot_updated:*");
        } catch (Exception e) {
            if (running) {
                log.error("Pub/Sub connection lost", e);
            }
        } finally {
            try {
                if (jedis != null) jedis.close();
            } catch (Exception ignore) {}
            jedis = null;
            subscriber = null;
        }
    }

    /**
     * Stop listening and close the Redis connection.
     *
     * <p>Jedis's {@code psubscribe} is blocking and does <em>not</em> respond
     * to thread interrupts, so we have to call {@code punsubscribe()} on the
     * subscriber object to make {@code psubscribe} return. That's what
     * cleanly unblocks the worker thread and lets {@code finally} close the
     * Jedis socket — otherwise the connection leaks.
     */
    public synchronized void stop() {
        running = false;
        redis.clients.jedis.JedisPubSub s = subscriber;
        if (s != null) {
            try { s.punsubscribe(); } catch (Exception ignore) {}
        }
        if (subscriberThread != null) {
            try {
                subscriberThread.join(2000);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }
}