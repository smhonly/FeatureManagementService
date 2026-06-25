package com.example.managementapi.service;

import com.example.managementapi.domain.Flag;
import com.example.managementapi.repository.AuditRepository;
import com.example.managementapi.repository.FlagRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

/**
 * Business logic layer for flag CRUD and convenience endpoints.
 *
 * Extracted from FlagController so controllers are thin and the same
 * logic can be reused by /rollout, /targeting, and future endpoints.
 *
 * All mutating methods run inside a single transaction so that the
 * `flags` row, the `flags_history` row, and the `audit_log` row commit
 * or roll back together. Without @Transactional, a failure between the
 * three writes (e.g. DB blip on insertHistory) would leave the system
 * in a state where the flag is at version=N but history only has up to
 * N-1 — /explain would then return wrong results for any time after
 * the failed write.
 */
@Service
public class FlagService {

    private final FlagRepository flagRepo;
    private final AuditRepository auditRepo;
    private final ObjectMapper json;

    public FlagService(FlagRepository flagRepo, AuditRepository auditRepo, ObjectMapper json) {
        this.flagRepo = flagRepo;
        this.auditRepo = auditRepo;
        this.json = json;
    }

    @Transactional(rollbackFor = Exception.class)
    public Flag createFlag(String actor, String key, String env, JsonNode definition,
                           boolean critical, String owner, String release) {
        validateDefinition(definition);
        Flag f = flagRepo.insert(key, env, definition, critical, owner, release);
        auditRepo.record(actor, "created", key + "@" + env, definition, null);

        return f;
    }

    public List<Flag> searchFlags(String env, String cursor, int limit) {
        if (limit <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be > 0");
        }
        return flagRepo.search(env, cursor, Math.min(limit, 500));
    }

    public Map<String, Object> getFlag(String key, String env, int historyLimit) {
        Flag f = flagRepo.find(key, env)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        List<JsonNode> history = flagRepo.findHistory(key, env, historyLimit);
        return Map.of("flag", f, "history", history);
    }

    @Transactional(rollbackFor = Exception.class)
    public Flag updateFlag(String actor, String key, String env,
                           int expectedVersion, JsonNode definition) {
        validateDefinition(definition);
        int rows = flagRepo.updateWithVersion(key, env, expectedVersion, definition);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED,
                    "version mismatch or flag not active");
        }
        Flag updated = flagRepo.find(key, env).orElseThrow();
        flagRepo.insertHistory(key, env, updated.version(), definition, actor);
        auditRepo.record(actor, "updated", key + "@" + env, definition, null);

        return updated;
    }

    @Transactional(rollbackFor = Exception.class)
    public void archiveFlag(String actor, String key, String env, int expectedVersion) {
        // Capture the pre-archive definition for the history row, so /explain can
        // still answer "what did the user see at moment T just before archive".
        Flag current = flagRepo.find(key, env)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "flag not found: " + key + "@" + env));
        int rows = flagRepo.archive(key, env, expectedVersion);
        if (rows == 0) {
            throw new ResponseStatusException(HttpStatus.PRECONDITION_FAILED, "version mismatch");
        }
        // The archive bumps version+1; write a history row at that new version
        // carrying the pre-archive definition (so replay sees a continuous timeline).
        flagRepo.insertHistory(key, env, current.version() + 1, current.definition(), actor);
        auditRepo.record(actor, "archived", key + "@" + env,
                json.createObjectNode().put("previous_version", current.version()), null);
    }

    /**
     * POST /{key}/rollout — convenience: update only the pct field.
     * Fetches current definition, replaces pct, re-uses standard update path.
     *
     * Runs in the same transaction as updateFlag() — Spring's @Transactional
     * propagation REQUIRED means the inner call joins the outer transaction.
     * (Self-injection isn't required when the outer method itself is annotated.)
     */
    @Transactional(rollbackFor = Exception.class)
    public Flag rolloutFlag(String actor, String key, String env,
                            int expectedVersion, int newPct) {
        Flag current = flagRepo.find(key, env)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        if (!"pct_rollout".equals(current.definition().get("type").asText())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "rollout only applies to pct_rollout flags");
        }
        if (newPct < 0 || newPct > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "pct must be in [0,100]");
        }

        ObjectNode newDef = current.definition().deepCopy();
        newDef.put("pct", newPct);
        Flag updated = updateFlag(actor, key, env, expectedVersion, newDef);
        auditRepo.record(actor, "rolled_out", key + "@" + env,
                json.createObjectNode().put("pct", newPct), null);
        return updated;
    }

    /**
     * POST /{key}/targeting — convenience: update only the rules array.
     */
    @Transactional(rollbackFor = Exception.class)
    public Flag targetingFlag(String actor, String key, String env,
                              int expectedVersion, JsonNode newRules) {
        Flag current = flagRepo.find(key, env)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        String type = current.definition().get("type").asText();
        if (!"targeting".equals(type) && !"pct_rollout".equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "targeting only applies to targeting or pct_rollout flags");
        }
        if (!newRules.isArray()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "rules must be an array");
        }

        ObjectNode newDef = current.definition().deepCopy();
        newDef.set("rules", newRules);
        Flag updated = updateFlag(actor, key, env, expectedVersion, newDef);
        auditRepo.record(actor, "targeted", key + "@" + env, newRules, null);
        return updated;
    }

    private void validateDefinition(JsonNode def) {
        if (def == null || def.get("type") == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "definition.type is required (one of: boolean, targeting, pct_rollout)");
        }
        String type = def.get("type").asText();
        if (!type.equals("boolean") && !type.equals("targeting") && !type.equals("pct_rollout")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "unknown flag type: " + type);
        }
        if (type.equals("pct_rollout")) {
            JsonNode pct = def.get("pct");
            if (pct == null || !pct.isInt() || pct.asInt() < 0 || pct.asInt() > 100) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "pct_rollout requires integer pct in [0,100]");
            }
        }
    }
}
