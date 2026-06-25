package com.example.managementapi.repository;

import com.example.managementapi.domain.Flag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class FlagRepository {

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RowMapper<Flag> rowMapper;

    public FlagRepository(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
        // rowMapper is initialized here (not in a field initializer) so that
        // the lambda can capture the constructor-injected `json` reference
        // — Java's definite-assignment rules forbid the field-initializer
        // form when capturing a field set by the constructor.
        this.rowMapper = (rs, n) -> {
            try {
                String def = rs.getString("definition");
                JsonNode defNode = def == null ? null : json.readTree(def);
                Timestamp ts = rs.getTimestamp("updated_at");
                return new Flag(
                        rs.getString("flag_key"),
                        rs.getString("env"),
                        rs.getInt("version"),
                        rs.getString("state"),
                        defNode,
                        rs.getBoolean("critical"),
                        rs.getString("owner"),
                        rs.getString("release"),
                        ts == null ? null : ts.toInstant()
                );
            } catch (Exception e) {
                throw new SQLException("row mapping failed", e);
            }
        };
    }

    public Optional<Flag> find(String key, String env) {
        try {
            Flag f = jdbc.queryForObject(
                    "SELECT flag_key, env, version, state, definition, critical, owner, release, updated_at " +
                            "FROM flags WHERE flag_key = ? AND env = ?",
                    rowMapper, key, env);
            return Optional.ofNullable(f);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * Cursor-based search (design §6.2: ?cursor=&limit=).
     * Returns flags with flag_key > cursor, ordered ASC, up to limit rows.
     * Pass null or empty cursor for the first page.
     */
    public List<Flag> search(String env, String cursor, int limit) {
        if (cursor == null || cursor.isEmpty()) {
            return jdbc.query(
                    "SELECT flag_key, env, version, state, definition, critical, owner, release, updated_at " +
                            "FROM flags WHERE env = ? ORDER BY flag_key LIMIT ?",
                    rowMapper, env, limit);
        }
        return jdbc.query(
                "SELECT flag_key, env, version, state, definition, critical, owner, release, updated_at " +
                        "FROM flags WHERE env = ? AND flag_key > ? ORDER BY flag_key LIMIT ?",
                rowMapper, env, cursor, limit);
    }

    /**
     * Insert new flag. Returns the created flag with version=1.
     *
     * Two round trips (INSERT, then SELECT find) for cross-database
     * compatibility: PostgreSQL and the H2 version used in tests both
     * accept this pattern, while H2's PostgreSQL mode does NOT accept
     * `INSERT ... RETURNING`. Production could swap to RETURNING for
     * a 1-RTT win, but the difference is at most ~1ms per create and
     * not worth a divergent SQL dialect.
     */
    public Flag insert(String key, String env, JsonNode definition,
                       boolean critical, String owner, String release) {
        String defJson = definition.toString();
        jdbc.update(
                "INSERT INTO flags (flag_key, env, version, state, definition, critical, owner, release) " +
                        "VALUES (?, ?, 1, 'active', ?, ?, ?, ?)",
                key, env, defJson, critical, owner, release);
        return find(key, env).orElseThrow();
    }

    /**
     * Optimistic-lock update. Returns the updated row count.
     * 0 rows affected → caller should respond with 412 Precondition Failed.
     *
     * Adds AND state = 'active' (beyond the design's SQL example) so that
     * archived flags can't be silently re-edited. The design's intent is
     * "soft delete ⇒ stop distributing", and a write to an archived flag
     * is at minimum surprising and at worst a footgun in the admin UI.
     */
    public int updateWithVersion(String key, String env, int expectedVersion, JsonNode definition) {
        String defJson = definition.toString();
        return jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "UPDATE flags SET version = version + 1, definition = ?, updated_at = now() " +
                            "WHERE flag_key = ? AND env = ? AND version = ? AND state = 'active'");
            ps.setString(1, defJson);
            ps.setString(2, key);
            ps.setString(3, env);
            ps.setInt(4, expectedVersion);
            return ps;
        });
    }

    /**
     * Soft delete. Bumps version + 1 and sets state = 'archived'.
     * Also restricted to state = 'active' so a re-archive returns 412
     * (caller mistakes like "DELETE with stale If-Match" should fail loudly).
     */
    public int archive(String key, String env, int expectedVersion) {
        return jdbc.update(
                "UPDATE flags SET state = 'archived', version = version + 1, updated_at = now() " +
                        "WHERE flag_key = ? AND env = ? AND version = ? AND state = 'active'",
                key, env, expectedVersion);
    }

    public void insertHistory(String flagKey, String env, int version, JsonNode definition, String changedBy) {
        jdbc.update(
                "INSERT INTO flags_history (flag_key, env, version, definition, changed_by) " +
                        "VALUES (?, ?, ?, ?, ?)",
                flagKey, env, version, definition.toString(), changedBy);
    }

    public List<JsonNode> findHistory(String flagKey, String env, int limit) {
        return jdbc.query(
                "SELECT definition FROM flags_history WHERE flag_key = ? AND env = ? " +
                        "ORDER BY version DESC LIMIT ?",
                (rs, n) -> {
                    try {
                        return json.readTree(rs.getString(1));
                    } catch (Exception e) {
                        throw new SQLException(e);
                    }
                }, flagKey, env, limit);
    }

    /**
     * Find the flag definition that was active at a given point in time.
     * For deterministic replay in the Explain API (design §6.6).
     *
     * Returns the latest history entry whose changed_at <= at, or empty if
     * the flag didn't exist at that time.
     */
    public Optional<HistorySnapshot> findHistoryAt(String flagKey, String env, java.time.Instant at) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT definition, version FROM flags_history " +
                            "WHERE flag_key = ? AND env = ? AND changed_at <= ? " +
                            "ORDER BY changed_at DESC LIMIT 1",
                    (rs, n) -> {
                        try {
                            JsonNode def = json.readTree(rs.getString("definition"));
                            int version = rs.getInt("version");
                            return new HistorySnapshot(def, version);
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    },
                    flagKey, env, java.sql.Timestamp.from(at)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public record HistorySnapshot(JsonNode definition, int version) {}
}
