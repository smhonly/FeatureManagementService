package com.example.managementapi.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * Domain record for a flag.
 * JSON property "key" matches the wire contract in docs/design.md §6.2
 * (clients see "key":"new_checkout"). Internal SQL column is "flag_key"
 * because KEY is reserved in H2 even with PostgreSQL compatibility mode.
 */
public record Flag(
        @JsonProperty("key") String flagKey,
        String env,
        int version,
        String state,
        JsonNode definition,
        boolean critical,
        String owner,
        String release,
        Instant updatedAt
) {}