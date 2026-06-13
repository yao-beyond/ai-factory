package com.lza.aifactory.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The CDP is the honesty kernel: it BLOCKS only what it can truly enforce at the
 * gateway layer; a policy denial it cannot enforce yet is reported as
 * ALLOWED_UNENFORCED (never a fake block). Default-deny for unlisted capabilities.
 */
class CapabilityDecisionPointTest {

    private final CapabilityDecisionPoint cdp = new CapabilityDecisionPoint();

    private GovernanceProfile profile() {
        GovernanceProfileLibrary lib = new GovernanceProfileLibrary(new ObjectMapper(), "governance-profiles.json");
        lib.load();
        return lib.enabledById("compliance-patcher").orElseThrow();
    }

    @Test
    void implementerBlockedFromMergingMain() {
        CapabilityDecision d = cdp.decide(profile(), "implementer", Capability.MERGE_MAIN);
        assertEquals(CapabilityResult.BLOCKED, d.result());
        assertEquals(EnforcementLayer.GATEWAY, d.enforcementLayer());
    }

    @Test
    void reviewerBlockedFromWritingSource() {
        // write:source is denied for reviewer. It's an EXECUTION_SANDBOX capability,
        // so honesty: reported ALLOWED_UNENFORCED (we don't pretend to OS-block it).
        CapabilityDecision d = cdp.decide(profile(), "reviewer", Capability.WRITE_SOURCE);
        assertEquals(CapabilityResult.ALLOWED_UNENFORCED, d.result());
        assertEquals(EnforcementLayer.EXECUTION_SANDBOX, d.enforcementLayer());
    }

    @Test
    void prodSecretsDenialIsHonestlyUnenforced() {
        CapabilityDecision d = cdp.decide(profile(), "implementer", Capability.ACCESS_PROD_SECRETS);
        assertEquals(CapabilityResult.ALLOWED_UNENFORCED, d.result());
    }

    @Test
    void allowedCapabilityIsAllowed() {
        assertEquals(CapabilityResult.ALLOWED,
                cdp.decide(profile(), "implementer", Capability.PROPOSE_PR).result());
    }

    @Test
    void unlistedCapabilityDefaultsToBlocked() {
        // run:static-analysis is not in implementer's allow or deny -> default deny.
        assertEquals(CapabilityResult.BLOCKED,
                cdp.decide(profile(), "implementer", Capability.RUN_STATIC_ANALYSIS).result());
    }

    @Test
    void selfApprovalAlwaysBlocked() {
        assertEquals(CapabilityResult.BLOCKED,
                cdp.decide(profile(), "implementer", Capability.APPROVE_OWN_CHANGE).result());
        assertEquals(CapabilityResult.BLOCKED,
                cdp.decide(profile(), "reviewer", Capability.APPROVE_OWN_REVIEW).result());
    }

    @Test
    void unknownRoleDefaultsToBlocked() {
        assertEquals(CapabilityResult.BLOCKED,
                cdp.decide(profile(), "intruder", Capability.READ_REPO).result());
    }
}
