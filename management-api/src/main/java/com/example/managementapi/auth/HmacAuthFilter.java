package com.example.managementapi.auth;

import com.example.managementapi.crypto.HmacSha256;
import com.example.managementapi.domain.Application;
import com.example.managementapi.repository.ApplicationRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * HMAC-based machine auth filter.
 *
 * Verifies the {@code Authorization: Bearer <api_key>} header by computing
 * HMAC-SHA256(server_secret, api_key) and looking up the resulting hash
 * in the applications table (design §7.3).
 *
 * Disabled by default. Enable with: ff.auth.hmac-enabled=true
 *
 * IMPORTANT: this class is NOT @Component — the filter is registered in
 * WebConfig via a FilterRegistrationBean. If it were @Component, Spring
 * Boot would auto-register it AND the explicit FilterRegistrationBean
 * would run the filter twice (second pass would see the request already
 * consumed and 401 everything).
 */
public class HmacAuthFilter extends OncePerRequestFilter {

    /** Request attribute key set by both this filter and JwtAuthFilter. */
    public static final String ACTOR_ATTR = "X-Actor";

    private final ApplicationRepository apps;
    private final String serverSecret;
    private final boolean enabledProperty;

    public HmacAuthFilter(ApplicationRepository apps,
                          @Value("${ff.server-secret}") String serverSecret,
                          @Value("${ff.auth.hmac-enabled:false}") boolean enabledProperty) {
        this.apps = apps;
        this.serverSecret = serverSecret;
        this.enabledProperty = enabledProperty;
    }

    public boolean isEnabled() {
        return enabledProperty;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String apiKey = stripBearer(request.getHeader("Authorization"));
        if (apiKey == null || apiKey.isBlank()) {
            writeJsonError(response, 401, "Authorization: Bearer <api_key> required");
            return;
        }

        String hash = HmacSha256.hex(serverSecret, apiKey);
        Optional<Application> app = apps.findByKeyHash(hash);
        if (app.isEmpty()) {
            writeJsonError(response, 401, "unknown api_key");
            return;
        }
        if (app.get().revokedAt() != null) {
            writeJsonError(response, 401, "api_key revoked");
            return;
        }

        // Set actor for downstream audit logging. Format: "app:<name>" so audit
        // rows distinguish machine actors from human SSO subjects.
        request.setAttribute(ACTOR_ATTR, "app:" + app.get().name());
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabledProperty) return true;
        String path = request.getServletPath();
        return path.startsWith("/actuator") || path.startsWith("/health");
    }

    private static String stripBearer(String auth) {
        if (auth == null) return null;
        String trimmed = auth.trim();
        if (trimmed.toLowerCase().startsWith("bearer ")) {
            return trimmed.substring(7).trim();
        }
        return null;
    }

    static void writeJsonError(HttpServletResponse response, int status, String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        // Hand-rolled JSON to avoid pulling ObjectMapper into a hot path
        // that runs on every unauthenticated request.
        String body = "{\"error\":\"" + escape(message) + "\"}";
        response.getWriter().write(body);
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
