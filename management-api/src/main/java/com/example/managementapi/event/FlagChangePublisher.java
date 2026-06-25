package com.example.managementapi.event;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Publishes {@link FlagChangeEventV1} to Kafka after flag mutations.
 *
 * <p>Design B — the Management API produces fully-self-contained events so
 * the snapshot-api Consumer never needs to query PostgreSQL.  Each event
 * carries the complete flag definition and the list of affected app IDs.</p>
 *
 * <p>{@code KafkaTemplate} is optional ({@code required = false}).  When it's
 * absent (e.g. tests, local dev) the publisher silently no-ops — the 60s
 * sweep in SnapshotBuilderService is the eventual-consistency floor.</p>
 */
@Component
public class FlagChangePublisher {

    private static final Logger log = LoggerFactory.getLogger(FlagChangePublisher.class);

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final String topic;

    public FlagChangePublisher(
            @Autowired(required = false) KafkaTemplate<String, Object> kafkaTemplate,
            @Value("${ff.kafka.topic-flag-changes:ff.flag.changes}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
    }

    /**
     * Publish a flag mutation event that carries the full definition and
     * the list of apps that this flag is scoped to.
     */
    public void publishFlagChange(String flagKey, String env, JsonNode definition,
                                  String op, List<Integer> scopedAppIds) {
        if (kafkaTemplate == null) return;
        try {
            var event = new FlagChangeEventV1(flagKey, env, op, definition, scopedAppIds);
            kafkaTemplate.send(topic, event);
            log.debug("published flag {} env={} op={} scopedApps={}", flagKey, env, op, scopedAppIds);
        } catch (Exception e) {
            // Best-effort — Kafka is fire-and-forget; the 60s sweep guarantees
            // eventual consistency if the publish fails.
            log.error("failed to publish flag change: {} {} {}", flagKey, env, op, e.getMessage());
        }
    }

    /**
     * Publish a scope mutation event (grant / revoke).  Since the scope
     * mutation itself is already committed to PG, we include the flag's
     * current definition so the Consumer doesn't need to look it up.
     */
    public void publishScopeChange(String flagKey, String env, int appId, String op,
                                   JsonNode definition) {
        if (kafkaTemplate == null) return;
        try {
            var event = new FlagChangeEventV1(flagKey, env, op, definition, List.of(appId));
            kafkaTemplate.send(topic, event);
            log.debug("published scope change flag={} env={} appId={} op={}", flagKey, env, appId, op);
        } catch (Exception e) {
            log.error("failed to publish scope change: {} {} {} {}", flagKey, env, appId, op, e.getMessage());
        }
    }

    /**
     * V1 event — kept internal so the wire format is independent of
     * snapshot-api's internal event model.
     */
    public record FlagChangeEventV1(
            String flagKey,
            String env,
            String op,
            JsonNode definition,
            List<Integer> scopedAppIds
    ) {}
}
