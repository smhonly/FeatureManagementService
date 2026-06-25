package com.example.managementapi.api;

import com.example.managementapi.service.FlagAppScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Per-app flag visibility endpoints (design §7 — "Flag → App visibility").
 *
 * The flag_app_scopes table is the cross-team isolation mechanism. The
 * control plane is the only place where this table is mutated; the
 * Snapshot API only reads it. So all of these endpoints live in the
 * management API, behind JwtAuthFilter.
 *
 *   POST   /api/v1/flags/{key}/apps/{appId}        (X-Env header) — grant
 *   DELETE /api/v1/flags/{key}/apps/{appId}        (X-Env header) — revoke
 *   GET    /api/v1/flags/{key}/apps                (X-Env header) — list app ids
 *   GET    /api/v1/apps/{appId}/flags              (X-Env header) — list flag keys
 */
@RestController
public class FlagAppScopeController {

    private final FlagAppScopeService scopeService;

    public FlagAppScopeController(FlagAppScopeService scopeService) {
        this.scopeService = scopeService;
    }

    @PostMapping("/api/v1/flags/{key}/apps/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void grant(@RequestAttribute(value = "X-Actor", required = false) String actor,
                      @PathVariable String key,
                      @RequestHeader(value = "X-Env", defaultValue = "production") String env,
                      @PathVariable int appId) {
        scopeService.grant(actor != null ? actor : "anonymous", key, env, appId);
    }

    @DeleteMapping("/api/v1/flags/{key}/apps/{appId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestAttribute(value = "X-Actor", required = false) String actor,
                       @PathVariable String key,
                       @RequestHeader(value = "X-Env", defaultValue = "production") String env,
                       @PathVariable int appId) {
        scopeService.revoke(actor != null ? actor : "anonymous", key, env, appId);
    }

    @GetMapping("/api/v1/flags/{key}/apps")
    public List<Integer> listAppsForFlag(
            @PathVariable String key,
            @RequestHeader(value = "X-Env", defaultValue = "production") String env) {
        return scopeService.listAppIdsForFlag(key, env);
    }

    @GetMapping("/api/v1/apps/{appId}/flags")
    public List<String> listFlagsForApp(
            @PathVariable int appId,
            @RequestHeader(value = "X-Env", defaultValue = "production") String env) {
        return scopeService.listFlagKeysForApp(appId, env);
    }
}
