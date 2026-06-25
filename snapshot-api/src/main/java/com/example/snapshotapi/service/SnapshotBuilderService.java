package com.example.snapshotapi.service;

import com.example.snapshotapi.store.SnapshotStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Rebuilds per-app snapshots from PostgreSQL and writes them atomically
 * to Redis.
 *
 *   Management API → PG → CDC → Kafka → FlagChangeConsumer → Redis → Pub/Sub → SDK
 *
 * The Kafka-driven path is the primary, sub-second one — see
 * {@link com.example.snapshotapi.event.FlagChangeConsumer}. This class's
 * {@link #scheduledRebuild} is the **fallback** sweep that guarantees
 * eventual consistency when Pub/Sub drops a notify or Kafka is briefly
 * unavailable. The 60s interval bounds the staleness window.
 *
 * After writing to Redis, publishes a Pub/Sub message so SDKs know to
 * refresh (the "push" channel from design §3).
 */
@Service
public class SnapshotBuilderService {

    private static final Logger log = LoggerFactory.getLogger(SnapshotBuilderService.class);

    private final JdbcTemplate jdbc;
    private final SnapshotStore snapshotStore;
    private final ObjectMapper json = new ObjectMapper();

    @Autowired(required = false)
    private SnapshotPublisher publisher;

    public SnapshotBuilderService(JdbcTemplate jdbc, SnapshotStore snapshotStore) {
        this.jdbc = jdbc;
        this.snapshotStore = snapshotStore;
    }

    /**
     * Rebuild snapshot for a single (env, appId).
     */
    public void rebuildForApp(String env, int appId) {
        List<FlagRow> rows = jdbc.query(
                """
                SELECT f.flag_key, f.definition, f.version
                FROM flags f
                JOIN flag_app_scopes s ON s.flag_key = f.flag_key AND s.env = f.env
                WHERE f.env = ? AND s.app_id = ? AND f.state = 'active'
                ORDER BY f.flag_key
                """,
                (rs, n) -> new FlagRow(
                        rs.getString("flag_key"),
                        rs.getString("definition"),
                        rs.getInt("version")
                ),
                env, appId
        );

        ArrayNode flagsArray = json.createArrayNode();
        int maxVersion = 0;
        for (FlagRow row : rows) {
            ObjectNode entry = json.createObjectNode();
            entry.put("key", row.key());
            try {
                entry.set("definition", json.readTree(row.definitionJson()));
            } catch (Exception e) {
                entry.putObject("definition").put("type", "boolean");
            }
            flagsArray.add(entry);
            if (row.version() > maxVersion) maxVersion = row.version();
        }

        ObjectNode body = json.createObjectNode();
        body.put("version", "v" + maxVersion);
        body.set("flags", flagsArray);

        snapshotStore.putAtomic(env, appId, maxVersion, body);

        // Notify SDKs via Pub/Sub (push channel — design §3)
        if (publisher != null) {
            publisher.notifySnapshotUpdated(env, appId);
        }
    }

    /**
     * Scheduled sweep — rebuilds all production snapshots every 60 s.
     * This is the poll fallback that guarantees eventual consistency even
     * if Pub/Sub messages are dropped (design §3).
     *
     * In production, this is supplemented by a Kafka consumer that triggers
     * rebuildForApp() on each CDC event for sub-second propagation.
     */
    @Scheduled(fixedRate = 60_000)
    public void scheduledRebuild() {
        try {
            rebuildAll("production");
        } catch (Exception e) {
            // Tables may not exist yet (e.g. before Flyway migration).
            // The next tick will retry.
        }
    }

    /**
     * Rebuild snapshots for all apps in an environment.
     */
    public void rebuildAll(String env) {
        List<Integer> appIds = jdbc.queryForList(
                "SELECT DISTINCT app_id FROM flag_app_scopes WHERE env = ?", Integer.class, env);
        for (int appId : appIds) {
            try {
                rebuildForApp(env, appId);
            } catch (Exception e) {
                log.error("rebuild failed env={} app={}: {}", env, appId, e.getMessage(), e);
            }
        }
    }

    private record FlagRow(String key, String definitionJson, int version) {}
}
