package com.example.sdk.evaluator;

import com.example.sdk.model.EvaluationResult;
import com.example.sdk.model.FlagDefinition;
import com.example.sdk.model.UserContext;

/**
 * Single-flag evaluator. Stateless; safe to call concurrently.
 *
 * <p>Per design §5 priority table, evaluation order is:
 * <ol>
 *   <li>For all types, rules are evaluated first (AND). Any rule fails
 *       → false, no further work.</li>
 *   <li>{@code boolean}     → just return the boolean decision; archived
 *                               flags never reach the SDK (filtered at the
 *                               server), so if it's in the snapshot it's active.</li>
 *   <li>{@code pct_rollout} → hash user into a bucket; bucket &lt; pct → true.</li>
 *   <li>{@code targeting}   → if any rule matched → true; else → default value.</li>
 * </ol>
 *
 * <p>Unknown type or unknown op → fail-closed (false). This is what design
 * §6.4 demands ("fails fast, no silent ignore").
 */
public final class FlagEvaluator {

    private FlagEvaluator() {}

    public static EvaluationResult evaluate(FlagDefinition def, UserContext ctx) {
        if (def == null) return EvaluationResult.MISSING;

        // Step 1: rules first (AND across all types that have rules).
        // For targeting: rule-miss falls through to default; for pct_rollout:
        // rule-miss short-circuits to false.
        boolean rulesPassed = RuleEngine.matchesAll(def.rules(), ctx);

        if (def.isBoolean()) {
            // Design §6.5: boolean flag's definition is just {"type":"boolean"}.
            // The "active" / "archived" decision lives in the DB column state
            // and is enforced server-side (archived flags never make it into
            // the snapshot). So if we see a boolean flag here, it's active.
            return EvaluationResult.of(true, "boolean=active");
        }

        if (def.isPctRollout()) {
            if (!rulesPassed) {
                return EvaluationResult.of(false, "rules not matched");
            }
            int pct = def.pct() == null ? 0 : def.pct();
            if (pct == 0) return EvaluationResult.of(false, "pct=0");
            if (pct == 100) return EvaluationResult.of(true, "pct=100");
            int bucket = HashBucketer.bucket(def.salt(), def.key(), ctx.userId());
            boolean inside = bucket < pct;
            return EvaluationResult.of(inside,
                    "bucket=" + bucket + " < " + pct + " → " + inside);
        }

        if (def.isTargeting()) {
            if (rulesPassed) return EvaluationResult.of(true, "rule matched");
            boolean defVal = def.defaultValue() != null && def.defaultValue();
            return EvaluationResult.of(defVal, "no rule matched, default=" + defVal);
        }

        return EvaluationResult.of(false, "unknown type=" + def.type());
    }
}