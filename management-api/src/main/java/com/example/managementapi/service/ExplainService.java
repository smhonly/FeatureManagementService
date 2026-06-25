package com.example.managementapi.service;

import com.example.managementapi.repository.FlagRepository;
import com.example.sdk.evaluator.FlagEvaluator;
import com.example.sdk.model.EvaluationResult;
import com.example.sdk.model.FlagDefinition;
import com.example.sdk.model.UserContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * Deterministic replay for /explain (design §6.6).
 *
 * Instead of storing evaluation results (864 GB/day), we store flag version
 * history (~50-500 MB over 7 years) and replay evaluations on demand.
 *
 * How it works:
 *   1. Query flags_history for the version active at the requested timestamp
 *      (changed_at <= at, ordered DESC, limit 1)
 *   2. Reconstruct FlagDefinition from that version's JSON
 *   3. Build UserContext from the caller-provided attributes
 *   4. Re-run FlagEvaluator — same inputs → same outputs, guaranteed
 *
 * The one case we can't handle: if the flag's salt was later changed,
 * the replayed bucket number won't match. In that case we note the uncertainty.
 *
 * --------------------------------------------------------------------------
 * IMPORTANT — evaluator pinning (project structure issue, not a code bug)
 * --------------------------------------------------------------------------
 * The current implementation imports `com.example.sdk.evaluator.FlagEvaluator`
 * from the same `feature-flag-sdk` module that ships to clients. This is a
 * correctness hazard: if the SDK's evaluation algorithm ever changes (e.g.
 * a new `op` is added, the hash function is updated, the rule AND/OR
 * semantics shift), historical replays would suddenly return different
 * results from what the user actually saw at the time.
 *
 * The right fix is a separate, frozen module — something like
 *   flag-evaluator-reference-impl:1.0.0
 * that /explain depends on instead of the live SDK. That module would
 * have its own dedicated conformance test suite and a version that only
 * moves on a slow, deliberate cadence. The SDK itself can ship a newer
 * evaluator to clients without disturbing the historical truth.
 *
 * Until that split exists, the salt-changed guard in this class is the
 * only thing standing between us and silent historical drift. Treat any
 * change to FlagEvaluator's algorithm as a breaking change to /explain
 * and to the salt-replay contract.
 */
@Service
public class ExplainService {

    private final FlagRepository flagRepo;
    private final ObjectMapper json;

    public ExplainService(FlagRepository flagRepo, ObjectMapper json) {
        this.flagRepo = flagRepo;
        this.json = json;
    }

    public ExplainResult explain(String key, String env, String userId,
                                 Instant at, Map<String, Object> userAttrs) {
        // 1. Find the flag definition active at `at`
        FlagRepository.HistorySnapshot snapshot = flagRepo.findHistoryAt(key, env, at)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "flag " + key + " did not exist at " + at));

        JsonNode definition = snapshot.definition();
        int version = snapshot.version();

        // 2. Detect salt change — if the salt in the current flag differs from
        //    the salt at replay time, the bucket number may be wrong.
        String saltAtTime = definition.has("salt") ? definition.get("salt").asText() : null;
        boolean saltUncertain = false;
        var current = flagRepo.find(key, env);
        if (current.isPresent() && saltAtTime != null) {
            JsonNode curDef = current.get().definition();
            String curSalt = curDef.has("salt") ? curDef.get("salt").asText() : null;
            saltUncertain = curSalt != null && !curSalt.equals(saltAtTime);
        }

        // 3. Replay evaluation using the exact same evaluator as the SDK.
        //    See class javadoc for the pinning caveat.
        Map<String, Object> defMap = jsonToMap(definition);
        FlagDefinition flagDef = FlagDefinition.fromMap(key, defMap);
        UserContext ctx = new UserContext(userId, userAttrs);
        EvaluationResult result = FlagEvaluator.evaluate(flagDef, ctx);

        String reason = result.reason();
        if (saltUncertain) {
            reason += " (warning: salt changed since replay time, bucket may differ)";
        }

        return new ExplainResult(result.value(), reason, version, userAttrs);
    }

    private Map<String, Object> jsonToMap(JsonNode node) {
        try {
            return json.convertValue(node, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    public record ExplainResult(boolean enabled, String reason,
                                int flagVersion, Map<String, Object> userContext) {}
}
