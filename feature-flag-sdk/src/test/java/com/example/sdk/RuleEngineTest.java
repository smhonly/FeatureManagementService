package com.example.sdk;

import com.example.sdk.evaluator.RuleEngine;
import com.example.sdk.model.FlagDefinition;
import com.example.sdk.model.UserContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleEngineTest {

    private static UserContext ctx(Object... kv) {
        java.util.Map<String, Object> m = new java.util.HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return new UserContext("u", m);
    }

    private static FlagDefinition.Rule rule(String attr, String op, Object... values) {
        return new FlagDefinition.Rule(attr, op, List.of(values));
    }

    @Test
    void eqMatchesString() {
        var r = rule("country", "eq", "US");
        assertTrue(RuleEngine.matches(r, ctx("country", "US")));
        assertFalse(RuleEngine.matches(r, ctx("country", "CN")));
    }

    @Test
    void neInvertsEq() {
        var r = rule("country", "ne", "US");
        assertFalse(RuleEngine.matches(r, ctx("country", "US")));
        assertTrue(RuleEngine.matches(r, ctx("country", "CN")));
    }

    @Test
    void inMatchesAnyOfList() {
        var r = rule("country", "in", "US", "CA", "MX");
        assertTrue(RuleEngine.matches(r, ctx("country", "CA")));
        assertFalse(RuleEngine.matches(r, ctx("country", "FR")));
    }

    @Test
    void numericComparisons() {
        assertTrue(RuleEngine.matches(rule("age", "gte", 18), ctx("age", 25)));
        assertFalse(RuleEngine.matches(rule("age", "gte", 18), ctx("age", 10)));
        assertTrue(RuleEngine.matches(rule("age", "lt", 65), ctx("age", 30)));
    }

    @Test
    void stringPredicates() {
        assertTrue(RuleEngine.matches(rule("email", "starts_with", "admin"), ctx("email", "admin@x.com")));
        assertTrue(RuleEngine.matches(rule("email", "contains", "@"), ctx("email", "user@x.com")));
    }

    @Test
    void regexMatches() {
        assertTrue(RuleEngine.matches(rule("email", "regex", ".*@example\\.com$"), ctx("email", "a@example.com")));
        assertFalse(RuleEngine.matches(rule("email", "regex", ".*@example\\.com$"), ctx("email", "a@other.com")));
    }

    @Test
    void existsChecksPresence() {
        assertTrue(RuleEngine.matches(rule("beta", "exists"), ctx("beta", true)));
        assertFalse(RuleEngine.matches(rule("beta", "exists"), ctx("other", "x")));
    }

    @Test
    void unknownOpFailsClosed() {
        assertFalse(RuleEngine.matches(rule("x", "weird"), ctx("x", 1)));
    }

    @Test
    void allRulesMustMatch() {
        var rules = List.of(
                rule("country", "eq", "US"),
                rule("age", "gte", 18));
        assertTrue(RuleEngine.matchesAll(rules, ctx("country", "US", "age", 25)));
        assertFalse(RuleEngine.matchesAll(rules, ctx("country", "US", "age", 10)));  // age fails
        assertFalse(RuleEngine.matchesAll(rules, ctx("country", "CN", "age", 25)));  // country fails
    }
}