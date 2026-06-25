package com.example.snapshotapi.event;

/**
 * Event consumed off Kafka (design §2: CDC → Kafka → Consumer → Redis).
 *
 * In production the producer is a Debezium-style CDC stream reading the
 * PostgreSQL WAL — the consumer doesn't care about the source, it just
 * reacts to the JSON payload on the topic.
 *
 * appId semantics:
 *   null  → the change is to a flag itself; rebuild every app that has
 *           this flag in its scope (query flag_app_scopes)
 *   set   → the change is to a specific app's visibility, or the app
 *           was just registered/revoked; rebuild only that app
 *
 * op is informational; the consumer treats all events the same way
 * (rebuild) and lets the source filter if it cares about action types.
 */
public record FlagChangeEvent(
        String flagKey,
        String env,
        Integer appId,
        String op
) {}
