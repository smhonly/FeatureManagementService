package com.example.managementapi.config;

import com.example.managementapi.auth.HmacAuthFilter;
import com.example.managementapi.auth.JwtAuthFilter;
import com.example.managementapi.repository.ApplicationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers auth filters.
 *
 * Deployment: management API runs single-region in us-east (see design §2,
 * with the cross-region write forwarding removed — see conversation 2026-06
 * for the rationale). No interceptor registration here.
 *
 * Auth filter registration:
 *   - Both filters are plain classes, not @Component (avoids Spring Boot
 *     auto-registering them on top of the FilterRegistrationBean below).
 *   - JwtAuthFilter covers all control-plane paths; HmacAuthFilter is
 *     an alternative for service-to-service calls. When both are enabled
 *     the JWT filter runs first and the HMAC filter is skipped if a
 *     valid Authorization header was already accepted.
 *   - Enable via: ff.auth.jwt-enabled=true, ff.auth.hmac-enabled=true
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    // --- Auth filter beans (not @Component) ---

    @Bean
    public HmacAuthFilter hmacAuthFilter(ApplicationRepository apps,
                                         @Value("${ff.server-secret}") String serverSecret,
                                         @Value("${ff.auth.hmac-enabled:false}") boolean enabled) {
        return new HmacAuthFilter(apps, serverSecret, enabled);
    }

    @Bean
    public JwtAuthFilter jwtAuthFilter(@Value("${ff.jwt-secret}") String jwtSecret,
                                       @Value("${ff.auth.jwt-enabled:false}") boolean enabled) {
        return new JwtAuthFilter(jwtSecret, enabled);
    }

    // --- Filter registrations ---

    @Bean
    public FilterRegistrationBean<JwtAuthFilter> jwtAuthFilterRegistration(JwtAuthFilter filter) {
        FilterRegistrationBean<JwtAuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(filter);
        // Control plane: all flag mutations and /explain need SSO identity.
        reg.addUrlPatterns(
                "/api/v1/flags",
                "/api/v1/flags/*",
                "/api/v1/flags/*/*",
                "/api/v1/explain"
        );
        reg.setOrder(1);
        reg.setEnabled(filter.isEnabled());
        return reg;
    }

    @Bean
    public FilterRegistrationBean<HmacAuthFilter> hmacAuthFilterRegistration(HmacAuthFilter filter) {
        FilterRegistrationBean<HmacAuthFilter> reg = new FilterRegistrationBean<>();
        reg.setFilter(filter);
        // HMAC is for service-to-service admin scripts. The same paths as JWT
        // (mutually exclusive at request time — caller picks one Authorization
        // header style). Order 2 so JWT runs first if both are enabled.
        reg.addUrlPatterns(
                "/api/v1/flags",
                "/api/v1/flags/*",
                "/api/v1/flags/*/*",
                "/api/v1/admin/*"
        );
        reg.setOrder(2);
        reg.setEnabled(filter.isEnabled());
        return reg;
    }
}
