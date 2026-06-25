package com.example.managementapi.domain;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

public record AuditEntry(
        long id,
        String actor,
        String action,
        String target,
        JsonNode detail,
        String ipAddress,
        Instant createdAt
) {}