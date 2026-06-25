package com.example.managementapi.repository;

import com.example.managementapi.domain.Application;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;

@Repository
public class ApplicationRepository {

    private final JdbcTemplate jdbc;

    public ApplicationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final String SELECT_COLS =
            "id, team, name, env_scope, api_key_hash, api_key_prefix, revoked_at, owner, created_at";

    private final RowMapper<Application> rowMapper = (rs, n) -> {
        Timestamp revoked = rs.getTimestamp("revoked_at");
        Timestamp created = rs.getTimestamp("created_at");
        return new Application(
                rs.getInt("id"),
                rs.getString("team"),
                rs.getString("name"),
                rs.getString("env_scope"),
                rs.getString("api_key_hash"),
                rs.getString("api_key_prefix"),
                revoked == null ? null : revoked.toInstant(),
                rs.getString("owner"),
                created == null ? null : created.toInstant()
        );
    };

    /** Insert with api_key_prefix (design §7). Returns the created application. */
    public Application insert(String team, String name, String envScope, String apiKeyHash,
                               String apiKeyPrefix, String owner) {
        var keyHolder = new GeneratedKeyHolder();
        jdbc.update(con -> {
            var ps = con.prepareStatement(
                    "INSERT INTO applications (team, name, env_scope, api_key_hash, api_key_prefix, owner) " +
                            "VALUES (?, ?, ?, ?, ?, ?)",
                    new String[]{"id"});
            ps.setString(1, team);
            ps.setString(2, name);
            ps.setString(3, envScope);
            ps.setString(4, apiKeyHash);
            ps.setString(5, apiKeyPrefix);
            ps.setString(6, owner);
            return ps;
        }, keyHolder);
        int id = keyHolder.getKey().intValue();
        return findById(id).orElseThrow();
    }

    public Optional<Application> findByKeyHash(String apiKeyHash) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + SELECT_COLS + " FROM applications WHERE api_key_hash = ?",
                    rowMapper, apiKeyHash));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<Application> findById(int id) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(
                    "SELECT " + SELECT_COLS + " FROM applications WHERE id = ?",
                    rowMapper, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public int revoke(int id) {
        return jdbc.update("UPDATE applications SET revoked_at = now() WHERE id = ? AND revoked_at IS NULL", id);
    }
}
