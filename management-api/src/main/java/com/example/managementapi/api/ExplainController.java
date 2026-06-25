package com.example.managementapi.api;

import com.example.managementapi.service.ExplainService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;

/**
 * Explain API — deterministic replay of past flag evaluations (design §6.6).
 *
 *   GET /api/v1/explain?flag=&user=&at=&userAttrs={json}
 *     → { enabled, reason, flagVersion, userContext }
 *
 * userAttrs is a JSON-encoded query parameter (URL-encoded by the caller).
 * GET cannot carry a body, so we surface nested user attributes as a single
 * JSON string in the query string. Most proxies and CDNs preserve query
 * strings; they silently drop GET bodies.
 */
@RestController
@RequestMapping("/api/v1/explain")
public class ExplainController {

    private final ExplainService explainService;
    private final ObjectMapper json;

    public ExplainController(ExplainService explainService, ObjectMapper json) {
        this.explainService = explainService;
        this.json = json;
    }

    @GetMapping
    public ExplainResponse explain(
            @RequestParam String flag,
            @RequestHeader(value = "X-Env", defaultValue = "production") String env,
            @RequestParam String user,
            @RequestParam String at,
            @RequestParam(value = "userAttrs", required = false) String userAttrsJson) {

        Instant atTime = Instant.parse(at);
        Map<String, Object> attrs = parseAttrs(userAttrsJson);
        ExplainService.ExplainResult r = explainService.explain(flag, env, user, atTime, attrs);
        return new ExplainResponse(r.enabled(), r.reason(), r.flagVersion(), r.userContext());
    }

    private Map<String, Object> parseAttrs(String userAttrsJson) {
        if (userAttrsJson == null || userAttrsJson.isBlank()) {
            return Map.of();
        }
        try {
            return json.readValue(userAttrsJson, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "userAttrs must be a JSON object: " + e.getMessage());
        }
    }

    public record ExplainResponse(boolean enabled, String reason,
                                  int flagVersion, Map<String, Object> userContext) {}
}
