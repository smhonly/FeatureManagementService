-- V1__init.sql — Feature Management Service initial schema
-- Mirrors docs/design.md §7: 6 tables, all PK/FK/CHECK constraints.
--
-- Single-file version (this project has no deployment history yet, so the
-- usual append-only V1/V2/... pattern is overkill). When a real second
-- schema change is needed, this file gets renamed to V1__init.sql and the
-- new change goes in V2__add_xxx.sql.
--
-- NOTE: column "flag_key" instead of "key" because KEY is reserved in H2 (PG mode).
-- NOTE: applications is created BEFORE flag_app_scopes because the FK needs the parent table.
-- NOTE: applications.api_key_prefix is the first-12-chars of the raw api_key per
--       design §7 (helps ops identify keys without exposing the full secret).
--       The UNIQUE index lets us look up an app by prefix in O(1).

CREATE TABLE applications (
    id            SERIAL PRIMARY KEY,
    team          VARCHAR(64) NOT NULL,
    name          VARCHAR(64) NOT NULL,
    env_scope     VARCHAR(16) NOT NULL DEFAULT 'production',
    api_key_hash  VARCHAR(64) NOT NULL UNIQUE,                             -- HMAC-SHA256 hex digest
    api_key_prefix VARCHAR(16) NOT NULL UNIQUE,                           -- first 12 chars of raw key
    revoked_at    TIMESTAMP,
    owner         VARCHAR(64),
    created_at    TIMESTAMP NOT NULL DEFAULT now(),
    UNIQUE (team, name),
    CHECK (env_scope IN ('dev','staging','production','preprod'))
);

CREATE TABLE flags (
    flag_key    VARCHAR(128),
    env         VARCHAR(16) NOT NULL DEFAULT 'production',
    version     INTEGER NOT NULL,                                          -- optimistic lock
    state       VARCHAR(16) NOT NULL DEFAULT 'active',                     -- active | archived
    definition  VARCHAR(2000) NOT NULL,                                    -- JSON-as-text (H2/PG compat)
    critical    BOOLEAN NOT NULL DEFAULT false,
    owner       VARCHAR(64),
    release     VARCHAR(64),
    updated_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (flag_key, env),
    CHECK (env IN ('dev','staging','production','preprod')),
    CHECK (state IN ('active','archived'))
);

CREATE TABLE flags_history (
    flag_key    VARCHAR(128) NOT NULL,
    env         VARCHAR(16) NOT NULL,
    version     INTEGER NOT NULL,
    definition  VARCHAR(2000) NOT NULL,
    changed_by  VARCHAR(64),
    changed_at  TIMESTAMP NOT NULL DEFAULT now(),
    PRIMARY KEY (flag_key, env, version),
    FOREIGN KEY (flag_key, env) REFERENCES flags(flag_key, env) ON DELETE CASCADE
);
CREATE INDEX idx_hist_time ON flags_history(flag_key, env, changed_at DESC);

-- Per-app visibility (introduced when design switched to per-app snapshots)
CREATE TABLE flag_app_scopes (
    flag_key  VARCHAR(128) NOT NULL,
    env       VARCHAR(16) NOT NULL,
    app_id    INTEGER NOT NULL REFERENCES applications(id) ON DELETE CASCADE,
    PRIMARY KEY (flag_key, env, app_id)
);
CREATE INDEX idx_scope_app ON flag_app_scopes(app_id, env);

CREATE TABLE audit_log (
    id          BIGSERIAL PRIMARY KEY,
    actor       VARCHAR(64) NOT NULL,                                      -- from user auth (SSO/OIDC)
    action      VARCHAR(32) NOT NULL,
    target      VARCHAR(128) NOT NULL,
    detail      VARCHAR(2000),
    ip_address  VARCHAR(45),
    created_at  TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (action IN ('created','updated','rolled_out','targeted','archived','login'))
);
CREATE INDEX idx_audit_actor_time ON audit_log(actor, created_at DESC);
CREATE INDEX idx_audit_target     ON audit_log(target, created_at DESC);
