package com.lza.aifactory.governance;

/**
 * The outcome of asking "may this role perform this capability under this
 * profile?". Carries the enforcement layer and the matched policy rule so the
 * decision is auditable, not a bare yes/no.
 */
public record CapabilityDecision(
        CapabilityResult result,
        Capability capability,
        String role,
        EnforcementLayer enforcementLayer,
        String matchedRule,
        String reason) {

    public boolean blocked() {
        return result == CapabilityResult.BLOCKED;
    }
}
