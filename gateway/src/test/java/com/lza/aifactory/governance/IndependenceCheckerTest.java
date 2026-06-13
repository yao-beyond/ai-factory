package com.lza.aifactory.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lza.aifactory.config.AiFactoryProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndependenceCheckerTest {

    private GovernanceProfile profile(String id) {
        GovernanceProfileLibrary lib = new GovernanceProfileLibrary(new ObjectMapper(), "governance-profiles.json");
        lib.load();
        return lib.enabledById(id).orElseThrow();
    }

    private IndependenceChecker checker(String developer, String reviewer) {
        AiFactoryProperties p = new AiFactoryProperties();
        p.getAgents().setDeveloper(developer);
        p.getAgents().setReviewer(reviewer);
        return new IndependenceChecker(p);
    }

    @Test
    void defaultAgentsAreIndependentForCriticalProfile() {
        // claude (anthropic) writes, codex (openai) reviews -> distinct principal + vendor.
        IndependenceCheck c = checker("claude", "codex").check(profile("compliance-patcher"));
        assertTrue(c.passed(), c.failures().toString());
        assertEquals("anthropic", c.implementerVendor());
        assertEquals("openai", c.reviewerVendor());
    }

    @Test
    void samePrincipalFailsDistinctPrincipal() {
        IndependenceCheck c = checker("claude", "claude").check(profile("compliance-patcher"));
        assertFalse(c.passed());
        assertTrue(c.failures().stream().anyMatch(f -> f.contains("同一個 agent")));
    }

    @Test
    void sameVendorFailsWhenDistinctVendorRequired() {
        // Two openai-family agents: distinct principal but same vendor.
        IndependenceCheck c = checker("codex", "gpt-reviewer").check(profile("compliance-patcher"));
        assertFalse(c.passed());
        assertTrue(c.failures().stream().anyMatch(f -> f.contains("同 vendor")));
    }

    @Test
    void unknownVendorFailsWhenDistinctVendorRequired() {
        IndependenceCheck c = checker("claude", "mystery-cli").check(profile("compliance-patcher"));
        assertFalse(c.passed());
        assertTrue(c.failures().stream().anyMatch(f -> f.contains("無法確認 vendor")));
    }

    @Test
    void standardProfileDoesNotRequireDistinctVendor() {
        // standard-app has requireDistinctVendor=false: same vendor is fine.
        IndependenceCheck c = checker("codex", "gpt-reviewer").check(profile("standard-app"));
        assertTrue(c.passed(), c.failures().toString());
    }
}
