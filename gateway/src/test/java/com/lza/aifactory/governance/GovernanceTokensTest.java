package com.lza.aifactory.governance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceTokensTest {

    private final GovernanceTokens tokens = new GovernanceTokens("operator-secret");

    @Test
    void issuedTokenValidatesForSameTaskAndPurpose() {
        String t = tokens.issue("T1", "promote-check");
        assertTrue(tokens.isValid("T1", "promote-check", t));
    }

    @Test
    void tokenIsBoundToTaskAndPurpose() {
        String t = tokens.issue("T1", "promote-check");
        assertFalse(tokens.isValid("T2", "promote-check", t), "cross-task replay must fail");
        assertFalse(tokens.isValid("T1", "approve", t), "cross-purpose replay must fail");
    }

    @Test
    void garbageOrMissingTokenIsRejected() {
        assertFalse(tokens.isValid("T1", "promote-check", null));
        assertFalse(tokens.isValid("T1", "promote-check", ""));
        assertFalse(tokens.isValid("T1", "promote-check", "ptok:deadbeef"));
    }

    @Test
    void differentKeysProduceDifferentTokens() {
        GovernanceTokens other = new GovernanceTokens("a-different-secret");
        assertFalse(other.isValid("T1", "promote-check", tokens.issue("T1", "promote-check")));
    }
}
