package com.example.managementapi.api;

import com.example.managementapi.service.ApplicationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * Application registration endpoints (design §7 — "Applications + credentials").
 *
 *   POST   /api/v1/admin/applications      — register a new app, get api_key
 *   DELETE /api/v1/admin/applications/{id}  — revoke an app's api_key
 *
 * X-Actor is the SSO subject (set by JwtAuthFilter as a request attribute).
 * Falls back to "anonymous" only when the auth filter is disabled (e.g. tests).
 */
@RestController
@RequestMapping("/api/v1/admin/applications")
public class ApplicationController {

    private final ApplicationService appService;

    public ApplicationController(ApplicationService appService) {
        this.appService = appService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RegisterResponse register(@RequestAttribute(value = "X-Actor", required = false) String actor,
                                     @RequestBody RegisterRequest req) {
        ApplicationService.RegisterResult r = appService.register(
                req.team(), req.name(), req.envScope(),
                actor != null ? actor : "anonymous");
        return new RegisterResponse(r.appId(), r.rawApiKey(), r.prefix());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@RequestAttribute(value = "X-Actor", required = false) String actor,
                       @PathVariable int id) {
        appService.revoke(id, actor != null ? actor : "anonymous");
    }

    public record RegisterRequest(String team, String name, String envScope) {}
    public record RegisterResponse(int appId, String rawApiKey, String prefix) {}
}
