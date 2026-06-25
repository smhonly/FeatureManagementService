package com.example.sdk.evaluator;

import com.example.sdk.model.FlagDefinition;
import com.example.sdk.model.UserContext;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Rule matching for targeting flags. All rules in a flag are AND'd.
 *
 * Supported ops (mirrors design §4 vocabulary):
 *   eq, ne, in, not_in, gt, gte, lt, lte, starts_with, contains, regex, exists
 *
 * Attribute resolution: ctx.attr(name). Missing attribute → rule fails
 * (except for "exists" / "not_exists").
 */
public final class RuleEngine {

    private RuleEngine() {}

    public static boolean matchesAll(List<FlagDefinition.Rule> rules, UserContext ctx) {
        // null / empty rules list → vacuously true. A flag with no rules is
        // not "no match" — it's "no constraints, everything matches".
        // (Previously this returned false, which silently broke every
        // pct_rollout and boolean flag that didn't define rules.)
        if (rules == null || rules.isEmpty()) return true;
        for (FlagDefinition.Rule r : rules) {
            if (!matches(r, ctx)) return false;
        }
        return true;
    }

    public static boolean matches(FlagDefinition.Rule r, UserContext ctx) {
        Object actual = ctx.attr(r.attribute());
        List<Object> expected = r.values();

        return switch (r.op()) {
            case "eq"          -> equalsAny(actual, expected);
            case "ne"          -> !equalsAny(actual, expected);
            case "in"          -> expected.stream().anyMatch(v -> eq(actual, v));
            case "not_in"      -> expected.stream().noneMatch(v -> eq(actual, v));
            case "gt"          -> cmpNumber(actual, expected, n -> n > 0);
            case "gte"         -> cmpNumber(actual, expected, n -> n >= 0);
            case "lt"          -> cmpNumber(actual, expected, n -> n < 0);
            case "lte"         -> cmpNumber(actual, expected, n -> n <= 0);
            case "starts_with" -> actual instanceof String s
                                  && !expected.isEmpty() && s.startsWith(String.valueOf(expected.get(0)));
            case "contains"    -> actual instanceof String s
                                  && !expected.isEmpty() && s.contains(String.valueOf(expected.get(0)));
            case "regex"       -> actual instanceof String s && !expected.isEmpty()
                                  && Pattern.matches(String.valueOf(expected.get(0)), s);
            case "exists"      -> actual != null;
            default            -> false;  // unknown op → fail-closed
        };
    }

    private static boolean equalsAny(Object actual, List<Object> expected) {
        if (actual == null) return false;
        return expected.stream().anyMatch(v -> eq(actual, v));
    }

    private static boolean eq(Object a, Object b) {
        if (a == null || b == null) return false;
        if (a instanceof Number na && b instanceof Number nb) {
            return Double.compare(na.doubleValue(), nb.doubleValue()) == 0;
        }
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private static boolean cmpNumber(Object actual, List<Object> expected, java.util.function.IntPredicate pred) {
        if (!(actual instanceof Number na) || expected.isEmpty()) return false;
        if (!(expected.get(0) instanceof Number nb)) return false;
        int c = Double.compare(na.doubleValue(), nb.doubleValue());
        return pred.test(c);
    }
}