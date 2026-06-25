package com.example.snapshotapi.event;

import com.example.snapshotapi.service.SnapshotBuilderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The "Consumer" in the design's data path:
 *
 *   Management API → PG → CDC → Kafka → [this] → Redis → Pub/Sub → SDK
 *
 * Receives flag/scope change events from Kafka and triggers a snapshot
 * rebuild for the affected (env, app) pair(s). This is the sub-second
 * path — the @Scheduled sweep in SnapshotBuilderService is the fallback
 * for when Pub/Sub drops a notify or Kafka is unavailable.
 */
@Component
public class FlagChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(FlagChangeConsumer.class);

    private final SnapshotBuilderService builder;
    private final JdbcTemplate jdbc;

    public FlagChangeConsumer(SnapshotBuilderService builder, JdbcTemplate jdbc) {
        this.builder = builder;
        this.jdbc = jdbc;
    }

    @KafkaListener(
            topics = "${ff.kafka.topic-flag-changes:ff.flag.changes}",
            groupId = "${ff.kafka.consumer-group:snapshot-builder}"
    )
    public void onFlagChange(FlagChangeEvent event) {
        if (event == null || event.flagKey() == null || event.env() == null) {
            log.warn("ignoring malformed event: {}", event);
            return;
        }
        try {
            if (event.appId() != null) {
                builder.rebuildForApp(event.env(), event.appId());
            } else {
                List<Integer> appIds = jdbc.queryForList(
                        "SELECT DISTINCT app_id FROM flag_app_scopes " +
                                "WHERE flag_key = ? AND env = ?",
                        Integer.class, event.flagKey(), event.env());
                for (int appId : appIds) {
                    builder.rebuildForApp(event.env(), appId);
                }
            }
        } catch (Exception e) {
            // Don't let a transient failure poison the consumer group.
            // The next event will retry, and the 60s scheduled sweep is
            // the eventual-consistency floor.
            log.error("rebuild failed for event={}: {}", event, e.getMessage(), e);
        }
    }
}
