package com.example.sdk.model;

/**
 * Output of an evaluation. value is the boolean decision; reason is
 * a human-readable trace ("bucket=15 < 50 → true", "rule matched",
 * "no rule matched, default=false", "flag missing → false", ...).
 */
public record EvaluationResult(boolean value, String reason) {

    public static final EvaluationResult MISSING =
            new EvaluationResult(false, "flag not in snapshot (returning default false)");

    public static EvaluationResult of(boolean v, String reason) {
        return new EvaluationResult(v, reason);
    }
}