package com.example.managementapi.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Repository for the flag_app_scopes table (design §7).
 *
 * This table governs per-app flag visibility: a (flag, env, app) row
 * here means the named app can see the named flag. The Snapshot API
 * JOINs this table to filter what each app receives.
 *
 * Side benefit (called out in design §7): a (flag, env, app) triple
 * is the natural unit of uniqueness, so two teams can independently
 * name a flag `new_ui` without colliding as long as their visibility
 * rows don't overlap.
 */
@Repository
public class FlagAppScopeRepository {

    private final JdbcTemplate jdbc;

    public FlagAppScopeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Grant an app visibility to a flag. Returns the number of rows
     * inserted (1 if newly granted, 0 if it already had visibility).
     */
    public int grant(String flagKey, String env, int appId) {
        return jdbc.update(
                "INSERT INTO flag_app_scopes (flag_key, env, app_id) VALUES (?, ?, ?)",
                flagKey, env, appId);
    }

    /**
     * Revoke an app's visibility. Returns rows removed (1 if previously
     * visible, 0 if there was nothing to remove).
     */
    public int revoke(String flagKey, String env, int appId) {
        return jdbc.update(
                "DELETE FROM flag_app_scopes WHERE flag_key = ? AND env = ? AND app_id = ?",
                flagKey, env, appId);
    }

    /**
     * List the apps that can see a given flag. Used by the admin UI to
     * show "which apps receive this flag?".
     */
    public List<Integer> listAppIdsForFlag(String flagKey, String env) {
        return jdbc.query(
                "SELECT app_id FROM flag_app_scopes WHERE flag_key = ? AND env = ? ORDER BY app_id",
                (rs, n) -> rs.getInt("app_id"),
                flagKey, env);
    }

    /**
     * List the flag keys visible to a given app in a given env. Used by
     * the admin UI to show "what does this app see?".
     */
    public List<String> listFlagKeysForApp(int appId, String env) {
        return jdbc.query(
                "SELECT flag_key FROM flag_app_scopes WHERE app_id = ? AND env = ? ORDER BY flag_key",
                (rs, n) -> rs.getString("flag_key"),
                appId, env);
    }
}
