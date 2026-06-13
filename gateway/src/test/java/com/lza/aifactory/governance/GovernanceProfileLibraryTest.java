package com.lza.aifactory.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The profile library is a policy source treated like code: the real library
 * loads, and self-defeating/malformed profiles fail startup rather than silently
 * degrading governance. Mirrors DiscoveryLibraryTest.
 */
class GovernanceProfileLibraryTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private GovernanceProfileLibrary load(String resource) {
        GovernanceProfileLibrary lib = new GovernanceProfileLibrary(mapper, resource);
        lib.load();
        return lib;
    }

    @Test
    void realLibraryLoadsAndExposesEnabledProfiles() {
        GovernanceProfileLibrary lib = load("governance-profiles.json");
        assertEquals(4, lib.enabled().size());
        assertTrue(lib.enabledById("compliance-patcher").isPresent());
        assertTrue(lib.enabledById("standard-app").isPresent());
        // Unknown / forged id is rejected at lookup.
        assertTrue(lib.enabledById("nope").isEmpty());
    }

    @Test
    void unknownCapabilityFailsStartup() {
        // The unknown wire-name surfaces as a Jackson mapping failure during load.
        assertThrows(IllegalStateException.class,
                () -> load("governance-profiles-bad-unknown-capability.json"));
    }

    @Test
    void hollowHighTierFailsStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> load("governance-profiles-bad-hollow-high.json"));
        assertTrue(ex.getMessage().contains("human-approval")
                || ex.getMessage().contains("humanApproval")
                || ex.getMessage().contains("independent-review-pass"));
    }

    @Test
    void duplicateIdAllowDenyConflictAndUnknownGateFailStartup() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> load("governance-profiles-bad-conflict.json"));
        String m = ex.getMessage();
        assertTrue(m.contains("duplicate id"));
        assertTrue(m.contains("both allowed and denied"));
        assertTrue(m.contains("unknown gate"));
    }

    @Test
    void emergencyHotfixIsAValidReviewBypassProfile() {
        GovernanceProfileLibrary lib = load("governance-profiles.json");
        GovernanceProfile p = lib.enabledById("emergency-hotfix").orElseThrow();
        assertTrue(p.allowReviewBypass());
        assertTrue(p.humanApprovalRequired());
        // Bypass profile trades independent review for a recorded override rationale.
        assertTrue(p.gates().beforeDeliverable().contains("override-rationale-recorded"));
        assertFalse(p.gates().beforeDeliverable().contains("independent-review-pass"));
    }
}
