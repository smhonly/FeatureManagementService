package com.example.managementapi.service;

import com.example.managementapi.domain.Flag;
import com.example.managementapi.event.FlagChangePublisher;
import com.example.managementapi.repository.ApplicationRepository;
import com.example.managementapi.repository.AuditRepository;
import com.example.managementapi.repository.FlagAppScopeRepository;
import com.example.managementapi.repository.FlagRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Business logic for the flag_app_scopes table (design §7).
 *
 * Grants and revokes are wrapped in transactions and audited. We also
 * sanity-check that the referenced flag and app actually exist before
 * writing — otherwise an FK violation would be a 500, but a 404 with
 * a precise message is friendlier to the admin UI.
 */
@Service
public class FlagAppScopeService {

    private final FlagAppScopeRepository scopeRepo;
    private final FlagRepository flagRepo;
    private final ApplicationRepository appRepo;
    private final AuditRepository auditRepo;
    private final FlagChangePublisher publisher;

    public FlagAppScopeService(FlagAppScopeRepository scopeRepo,
                               FlagRepository flagRepo,
                               ApplicationRepository appRepo,
                               AuditRepository auditRepo,
                               FlagChangePublisher publisher) {
        this.scopeRepo = scopeRepo;
        this.flagRepo = flagRepo;
        this.appRepo = appRepo;
        this.auditRepo = auditRepo;
        this.publisher = publisher;
    }

    @Transactional(rollbackFor = Exception.class)
    public void grant(String actor, String flagKey, String env, int appId) {
        Flag flag = flagRepo.find(flagKey, env)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "flag not found: " + flagKey + "@" + env));
        if (appRepo.findById(appId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "app not found: id=" + appId);
        }
        // scopeRepo.grant returns 1 if newly granted, 0 if it already existed.
        // Either way the audit row is recorded so the action is traceable.
        scopeRepo.grant(flagKey, env, appId);
        auditRepo.record(actor, "scope_granted",
                flagKey + "@" + env + "->app:" + appId,
                null, null);
        publisher.publishScopeChange(flagKey, env, appId, "scope_granted",
                flag.definition());
    }

    @Transactional(rollbackFor = Exception.class)
    public void revoke(String actor, String flagKey, String env, int appId) {
        // DELETE is idempotent: revoking something that was never granted
        // affects 0 rows. We still record the attempt — failed revokes
        // can indicate operator typos or hostile probing.
        Flag flag = flagRepo.find(flagKey, env).orElse(null);
        scopeRepo.revoke(flagKey, env, appId);
        auditRepo.record(actor, "scope_revoked",
                flagKey + "@" + env + "->app:" + appId,
                null, null);
        if (flag != null) {
            // For revoke, the flag should be REMOVED from the app's snapshot.
            // Pass null definition so mergeFlag removes it.
            publisher.publishScopeChange(flagKey, env, appId, "scope_revoked",
                    null);
        }
    }

    public List<Integer> listAppIdsForFlag(String flagKey, String env) {
        if (flagRepo.find(flagKey, env).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "flag not found: " + flagKey + "@" + env);
        }
        return scopeRepo.listAppIdsForFlag(flagKey, env);
    }

    public List<String> listFlagKeysForApp(int appId, String env) {
        if (appRepo.findById(appId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "app not found: id=" + appId);
        }
        return scopeRepo.listFlagKeysForApp(appId, env);
    }
}
