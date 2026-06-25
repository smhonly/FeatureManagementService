package com.example.managementapi.api;

import com.example.managementapi.domain.Flag;
import com.example.managementapi.service.FlagService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Control-plane flag endpoints. Maps to docs/design.md §6.2.
 *
 * Thin controller — all business logic lives in FlagService.
 */
@RestController
@RequestMapping("/api/v1/flags")
public class FlagController {

    private final FlagService flagService;

    public FlagController(FlagService flagService) {
        this.flagService = flagService;
    }

    /** POST /api/v1/flags — create (X-Env inherited from query?env= — actually see X-Env in body via request) */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Flag create(@RequestAttribute(value = "X-Actor", required = false) String actor,
                       @RequestHeader(value = "X-Env", defaultValue = "production") String env,
                       @RequestBody CreateFlagRequest req) {
        // body may carry its own env (for creating in non-default env); header is the
        // runtime default. The body's env wins if non-null and non-blank.
        String targetEnv = (req.env() != null && !req.env().isBlank()) ? req.env() : env;
        return flagService.createFlag(actor != null ? actor : "anonymous",
                req.key(), targetEnv, req.definition(),
                req.critical(), req.owner(), req.release());
    }

    /**
     * GET /api/v1/flags?cursor=&limit=  — env is passed via X-Env header
     * (design §6.1: "Not a query parameter — that would make the CDN
     * treat one URL as many cache keys").
     */
    @GetMapping
    public List<Flag> search(@RequestHeader(value = "X-Env", defaultValue = "production") String env,
                             @RequestParam(defaultValue = "") String cursor,
                             @RequestParam(defaultValue = "100") int limit) {
        return flagService.searchFlags(env, cursor, limit);
    }

    /** GET /api/v1/flags/{key}  (X-Env header) — detail + history */
    @GetMapping("/{key}")
    public Map<String, Object> detail(@PathVariable String key,
                                      @RequestHeader(value = "X-Env", defaultValue = "production") String env,
                                      @RequestParam(defaultValue = "20") int historyLimit) {
        return flagService.getFlag(key, env, historyLimit);
    }

    /**
     * PUT /api/v1/flags/{key}  (X-Env header) — update with optimistic lock.
     * Requires If-Match header carrying the expected version.
     */
    @PutMapping("/{key}")
    public ResponseEntity<Flag> update(@RequestHeader(value = "If-Match", required = false) String ifMatch,
                                       @RequestAttribute(value = "X-Actor", required = false) String actor,
                                       @PathVariable String key,
                                       @RequestHeader(value = "X-Env", defaultValue = "production") String env,
                                       @RequestBody UpdateFlagRequest req) {
        int expectedVersion = parseIfMatch(ifMatch);
        Flag updated = flagService.updateFlag(actor != null ? actor : "anonymous",
                key, env, expectedVersion, req.definition());
        return ResponseEntity.ok()
                .eTag("\"" + updated.version() + "\"")
                .body(updated);
    }

    /** DELETE /api/v1/flags/{key}  (X-Env header) — soft delete (state → archived) */
    @DeleteMapping("/{key}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestHeader(value = "If-Match", required = false) String ifMatch,
                       @RequestAttribute(value = "X-Actor", required = false) String actor,
                       @PathVariable String key,
                       @RequestHeader(value = "X-Env", defaultValue = "production") String env) {
        int expectedVersion = ifMatch == null ? -1 :
                Integer.parseInt(ifMatch.replace("\"", "").trim());
        flagService.archiveFlag(actor != null ? actor : "anonymous",
                key, env, expectedVersion);
    }

    /**
     * POST /api/v1/flags/{key}/rollout — convenience: change only the pct field.
     * Reuses the standard optimistic-lock update path.
     */
    @PostMapping("/{key}/rollout")
    public ResponseEntity<Flag> rollout(@RequestHeader(value = "If-Match", required = false) String ifMatch,
                                        @RequestAttribute(value = "X-Actor", required = false) String actor,
                                        @PathVariable String key,
                                        @RequestHeader(value = "X-Env", defaultValue = "production") String env,
                                        @RequestBody RolloutRequest req) {
        int expectedVersion = parseIfMatch(ifMatch);
        Flag updated = flagService.rolloutFlag(actor != null ? actor : "anonymous",
                key, env, expectedVersion, req.pct());
        return ResponseEntity.ok()
                .eTag("\"" + updated.version() + "\"")
                .body(updated);
    }

    /**
     * POST /api/v1/flags/{key}/targeting — convenience: change only the rules array.
     */
    @PostMapping("/{key}/targeting")
    public ResponseEntity<Flag> targeting(@RequestHeader(value = "If-Match", required = false) String ifMatch,
                                          @RequestAttribute(value = "X-Actor", required = false) String actor,
                                          @PathVariable String key,
                                          @RequestHeader(value = "X-Env", defaultValue = "production") String env,
                                          @RequestBody TargetingRequest req) {
        int expectedVersion = parseIfMatch(ifMatch);
        Flag updated = flagService.targetingFlag(actor != null ? actor : "anonymous",
                key, env, expectedVersion, req.rules());
        return ResponseEntity.ok()
                .eTag("\"" + updated.version() + "\"")
                .body(updated);
    }

    private static int parseIfMatch(String ifMatch) {
        if (ifMatch == null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.PRECONDITION_REQUIRED, "If-Match header required (current version)");
        }
        try {
            return Integer.parseInt(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "If-Match must be an integer version");
        }
    }

    public record CreateFlagRequest(
            String key,
            String env,
            JsonNode definition,
            boolean critical,
            String owner,
            String release
    ) {}

    public record UpdateFlagRequest(JsonNode definition) {}
    public record RolloutRequest(int pct) {}
    public record TargetingRequest(JsonNode rules) {}
}
