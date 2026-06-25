package com.example.managementapi.auth;

import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.exceptions.JWTVerificationException;
import com.auth0.jwt.interfaces.DecodedJWT;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT-based user auth filter (design §7 — "User-level identity: SSO/OIDC").
 *
 * Validates {@code Authorization: Bearer <jwt>} signed with HS256(ff.jwt-secret)
 * and extracts the {@code sub} claim as the actor for audit logging.
 *
 * Disabled by default — enable with ff.auth.jwt-enabled=true. The
 * management API should have this enabled in any non-test environment;
 * leaving it off means anyone can spoof operations by setting an
 * X-Actor request attribute from a client-side header.
 *
 * Not @Component on purpose: registration goes through WebConfig's
 * FilterRegistrationBean to avoid Spring Boot's auto-registration
 * running the filter twice.
 */
public class JwtAuthFilter extends OncePerRequestFilter {

    private final Algorithm algorithm;
    private final boolean enabledProperty;

    public JwtAuthFilter(@Value("${ff.jwt-secret}") String jwtSecret,
                         @Value("${ff.auth.jwt-enabled:false}") boolean enabledProperty) {
        this.algorithm = Algorithm.HMAC256(jwtSecret);
        this.enabledProperty = enabledProperty;
    }

    public boolean isEnabled() {
        return enabledProperty;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String auth = request.getHeader("Authorization");
        if (auth == null || !auth.toLowerCase().startsWith("bearer ")) {
            HmacAuthFilter.writeJsonError(response, 401, "Authorization: Bearer <jwt> required");
            return;
        }

        String token = auth.substring(7).trim();
        try {
            DecodedJWT jwt = JWT.require(algorithm).build().verify(token);
            String actor = jwt.getSubject();
            if (actor == null || actor.isBlank()) {
                HmacAuthFilter.writeJsonError(response, 401, "JWT missing sub claim");
                return;
            }
            // Set actor for downstream controllers + audit. @RequestAttribute("X-Actor")
            // on controller methods reads from this.
            request.setAttribute(HmacAuthFilter.ACTOR_ATTR, actor);
            chain.doFilter(request, response);
        } catch (JWTVerificationException e) {
            HmacAuthFilter.writeJsonError(response, 401, "invalid JWT: " + e.getMessage());
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!enabledProperty) return true;
        String path = request.getServletPath();
        return path.startsWith("/actuator") || path.startsWith("/health");
    }
}
