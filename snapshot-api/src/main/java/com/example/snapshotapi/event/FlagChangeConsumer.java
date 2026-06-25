package com.example.snapshotapi.event;

import com.example.snapshotapi.service.SnapshotBuilderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The "Consumer" in design B's data path:
 *
 * <pre>
 *   Management API → PG + Kafka (full definition + scopedAppIds)
 *                          │
 *                          ▼
 *                    [this Consumer] ──mergeFlagForApp──► Redis → Pub/Sub → SDK
 * </pre>
 *
 * <p>The event is self-contained: it carries the complete flag definition
 * and the list of scoped app IDs.  The Consumer never queries PostgreSQL —
 * each event is merged directly into the per-app Redis snapshot.</p>
 *
 * <p>The @Scheduled sweep in SnapshotBuilderService is the fallback for
 * when Kafka is unavailable.</p>
 */
@Component
public class FlagChangeConsumer {

    private static final Logger log = LoggerFactory.getLogger(FlagChangeConsumer.class);

    private final SnapshotBuilderService builder;

    public FlagChangeConsumer(SnapshotBuilderService builder) {
        this.builder = builder;
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
        if (event.scopedAppIds() == null || event.scopedAppIds().isEmpty()) {
            log.warn("ignoring event without scopedAppIds: {}", event);
            return;
        }
        try {
            for (int appId : event.scopedAppIds()) {
                builder.mergeFlagForApp(event.env(), appId,
                        event.flagKey(), event.definition(), event.op());
            }
        } catch (Exception e) {
            // Don't let a transient failure poison the consumer group.
            // The next event will retry, and the 60s scheduled sweep is
            // the eventual-consistency floor.
            log.error("merge failed for event={}: {}", event, e.getMessage(), e);
        }
    }
}
