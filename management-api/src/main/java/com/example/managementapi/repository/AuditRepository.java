package com.example.managementapi.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditRepository {

    private final JdbcTemplate jdbc;

    public AuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String actor, String action, String target, JsonNode detail, String ipAddress) {
        jdbc.update(
                "INSERT INTO audit_log (actor, action, target, detail, ip_address) VALUES (?, ?, ?, ?, ?)",
                actor, action, target, detail == null ? null : detail.toString(), ipAddress);
    }
}
