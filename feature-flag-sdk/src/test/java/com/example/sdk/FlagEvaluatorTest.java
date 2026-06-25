package com.example.sdk;

import com.example.sdk.evaluator.FlagEvaluator;
import com.example.sdk.model.FlagDefinition;
import com.example.sdk.model.UserContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FlagEvaluatorTest {

    private static UserContext ctx(String userId) {
        return new UserContext(userId, Map.of());
    }

    @Test
    void booleanFlagIsAlwaysTrueWhenInSnapshot() {
        // Per design §6.5: if a boolean flag is in the snapshot, it's active.
        // The DB state column (active/archived) is enforced server-side;
        // archived flags never make it into the SDK's snapshot.
        var def = new FlagDefinition("f", "boolean", null, null, null, null, null);
        assertTrue(FlagEvaluator.evaluate(def, ctx("u")).value());
    }

    @Test
    void pctZeroAlwaysFalse() {
        var def = new FlagDefinition("f", "pct_rollout", null, 0, "s", null, null);
        for (int i = 0; i < 100; i++) {
            assertFalse(FlagEvaluator.evaluate(def, ctx("u" + i)).value());
        }
    }

    @Test
    void pctHundredAlwaysTrue() {
        var def = new FlagDefinition("f", "pct_rollout", null, 100, "s", null, null);
        for (int i = 0; i < 100; i++) {
            assertTrue(FlagEvaluator.evaluate(def, ctx("u" + i)).value());
        }
    }

    @Test
    void pctMidProducesRoughlyCorrectShare() {
        var def = new FlagDefinition("f", "pct_rollout", null, 30, "s", null, null);
        int trueCount = 0;
        int n = 10000;
        for (int i = 0; i < n; i++) {
            if (FlagEvaluator.evaluate(def, ctx("u" + i)).value()) trueCount++;
        }
        double rate = trueCount * 1.0 / n;
        assertTrue(rate > 0.27 && rate < 0.33, "expected ~30%, got " + rate);
    }

    @Test
    void pctRolloutRulesShortCircuitToFalse() {
        // Design §5: rules narrow the population FIRST; hashing only applies
        // to those who pass. If rules fail, no bucket check.
        var rules = List.of(new FlagDefinition.Rule("region", "eq", List.of("us-east-1")));
        var def = new FlagDefinition("f", "pct_rollout", null, 100, "s", rules, null);

        // bucket=100 would always win, but region rule fails → false
        var result = FlagEvaluator.evaluate(def,
                new UserContext("u", Map.of("region", "eu-west-1")));
        assertFalse(result.value());
        assertTrue(result.reason().contains("rules not matched"),
                "reason should explain rule-miss, got: " + result.reason());
    }

    @Test
    void pctRolloutRulesPassThenBucketApplies() {
        // Combined case: rule passes AND user is in bucket → true
        var rules = List.of(new FlagDefinition.Rule("region", "eq", List.of("us-east-1")));
        var def = new FlagDefinition("f", "pct_rollout", null, 50, "s", rules, null);

        var usUser = new UserContext("u", Map.of("region", "us-east-1"));
        var r = FlagEvaluator.evaluate(def, usUser);
        // Either true (in bucket) or false (not in bucket) — both are
        // "rules passed", which is what we're verifying. Reason should
        // mention bucket, not rules.
        assertTrue(r.reason().contains("bucket="),
                "rules passed → reason should cite bucket, got: " + r.reason());
    }

    @Test
    void targetingNoRulesMatchesEveryone() {
        // Per design §6: empty rules = "no constraints" = vacuously matches.
        // The default value is unreachable when rules are empty (since
        // rules trivially pass → "rule matched" → true).
        var def = new FlagDefinition("f", "targeting", null, null, null,
                List.of(), false);
        assertTrue(FlagEvaluator.evaluate(def, ctx("u")).value());
    }

    @Test
    void targetingRuleMatchWins() {
        var rules = List.of(new FlagDefinition.Rule("role", "eq", List.of("admin")));
        var def = new FlagDefinition("f", "targeting", null, null, null, rules, false);
        assertTrue(FlagEvaluator.evaluate(def,
                new UserContext("u", Map.of("role", "admin"))).value());
        assertFalse(FlagEvaluator.evaluate(def,
                new UserContext("u", Map.of("role", "user"))).value());
    }

    @Test
    void nullDefinitionReturnsMissing() {
        var r = FlagEvaluator.evaluate(null, ctx("u"));
        assertFalse(r.value());
        assertTrue(r.reason().contains("not in snapshot"));
    }

    @Test
    void unknownTypeFailsClosed() {
        var def = new FlagDefinition("f", "banana", null, null, null, null, null);
        assertFalse(FlagEvaluator.evaluate(def, ctx("u")).value());
    }
}