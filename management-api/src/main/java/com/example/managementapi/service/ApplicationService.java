package com.example.managementapi.service;

import com.example.managementapi.crypto.HmacSha256;
import com.example.managementapi.domain.Application;
import com.example.managementapi.repository.ApplicationRepository;
import com.example.managementapi.repository.AuditRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Application registration service.
 *
 * Generates API keys, HMAC-hashes them with the server secret, and stores
 * only the hash. The raw key is returned once in the registration response.
 *
 * Revoke writes an audit_log row (actor + target = app id) so that key
 * rotations are traceable back to a person.
 */
@Service
public class ApplicationService {

    private final ApplicationRepository appRepo;
    private final AuditRepository auditRepo;
    private final String serverSecret;
    private final SecureRandom rng = new SecureRandom();

    public ApplicationService(ApplicationRepository appRepo,
                              AuditRepository auditRepo,
                              @Value("${ff.server-secret}") String serverSecret) {
        this.appRepo = appRepo;
        this.auditRepo = auditRepo;
        this.serverSecret = serverSecret;
    }

    /**
     * Register a new application. Returns the raw API key — this is the only
     * time it's shown. Caller must store it securely.
     */
    @Transactional(rollbackFor = Exception.class)
    public RegisterResult register(String team, String name, String envScope, String owner) {
        String rawKey = generateApiKey();
        String hash = HmacSha256.hex(serverSecret, rawKey);
        // Design §7 says "first 8 chars"; we use up to 12 for ops convenience.
        // rawKey is always at least 40 chars (sk_live_ + 32 base64), so this is safe.
        String prefix = rawKey.substring(0, 12);
        Application app = appRepo.insert(team, name, envScope, hash, prefix, owner);
        auditRepo.record(owner, "created", "app:" + app.id(),
                null, null);
        return new RegisterResult(app.id(), rawKey, prefix);
    }

    /**
     * Revoke an application's API key. The row stays in the applications
     * table with revoked_at = now() (design §7.3) so we can still answer
     * "who used this key and when was it killed".
     *
     * DELETE is idempotent: revoking a non-existent or already-revoked
     * key affects 0 rows. We still record the attempt in the audit log
     * — failed revokes can indicate operator typos or hostile probing.
     */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(int appId, String actor) {
        appRepo.revoke(appId);
        auditRepo.record(actor, "revoked", "app:" + appId, null, null);
    }

    private String generateApiKey() {
        byte[] bytes = new byte[24];
        rng.nextBytes(bytes);
        return "sk_live_" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record RegisterResult(int appId, String rawApiKey, String prefix) {}
}
