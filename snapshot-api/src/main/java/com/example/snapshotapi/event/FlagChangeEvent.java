package com.example.snapshotapi.event;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * Event consumed off Kafka (design §2: CDC → Kafka → Consumer → Redis).
 *
 * <p>Design B — Kafka carries the full flag definition so the Consumer
 * can merge without touching PostgreSQL:</p>
 *
 * <pre>
 *   Management API → PG → CDC → Kafka (full payload) → Consumer → Redis
 * </pre>
 *
 * <p>Payload semantics:</p>
 * <dl>
 *   <dt>{@code definition} present</dt>
 *   <dd>The event carries the full flag definition.  Consumer can merge
 *       directly into the snapshot without querying PG.  This is the
 *       normal path for flag create / update / archive.</dd>
 *   <dt>{@code definition} absent (null)</dt>
 *   <dd>The event is a scope-only change or a legacy notification.
 *       Consumer falls back to the {@code rebuildForApp} sweep path
 *       which queries PG.</dd>
 * </dl>
 *
 * <p>{@code scopedAppIds}, when set, tells the Consumer exactly which
 * apps need a snapshot rebuild — no need to query {@code flag_app_scopes}.</p>
 */
public record FlagChangeEvent(
        String flagKey,
        String env,
        String op,
        JsonNode definition,
        List<Integer> scopedAppIds
) {}
