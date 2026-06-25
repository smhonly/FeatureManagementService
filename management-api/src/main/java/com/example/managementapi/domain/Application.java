package com.example.managementapi.domain;

import java.time.Instant;

public record Application(
        int id,
        String team,
        String name,
        String envScope,
        String apiKeyHash,
        String apiKeyPrefix,
        Instant revokedAt,
        String owner,
        Instant createdAt
) {}