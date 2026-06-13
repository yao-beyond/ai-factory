package com.lza.aifactory.governance;

import com.lza.aifactory.governance.GovernanceProfile.RoleCapabilities;
import org.springframework.stereotype.Service;

/**
 * The capability decision point (CDP): given a profile, a role, and a capability,
 * decides allow / allowed-unenforced / blocked. This is the technical kernel of
 * the runtime's honesty — a denial is only reported as {@code BLOCKED} when it
 * sits at the {@link EnforcementLayer#GATEWAY} layer (which Phase 1 truly
 * enforces); a denial at a layer we cannot yet enforce is reported as
 * {@link CapabilityResult#ALLOWED_UNENFORCED} so the runtime never claims a
 * containment it does not have. See docs/design/governance-runtime.md §3.2.
 *
 * <p>Default-deny (whitelist) semantics: a capability that is neither explicitly
 * allowed nor denied is BLOCKED.
 */
@Service
public class CapabilityDecisionPoint {

    public CapabilityDecision decide(GovernanceProfile profile, String role, Capability capability) {
        // Self-approval is never permitted, regardless of the profile.
        if (capability == Capability.APPROVE_OWN_CHANGE || capability == Capability.APPROVE_OWN_REVIEW) {
            return new CapabilityDecision(CapabilityResult.BLOCKED, capability, role,
                    EnforcementLayer.GATEWAY, "separation-of-duties.no-self-approval",
                    role + " 不得核准自己的產出");
        }
        RoleCapabilities rc = profile.role(role);
        if (rc == null) {
            // Unknown role => default deny.
            return new CapabilityDecision(CapabilityResult.BLOCKED, capability, role,
                    EnforcementLayer.GATEWAY, "default-deny.unknown-role",
                    "角色 '" + role + "' 在 profile '" + profile.id() + "' 中未定義");
        }
        if (rc.deny().contains(capability)) {
            if (capability.layer() == EnforcementLayer.GATEWAY) {
                return new CapabilityDecision(CapabilityResult.BLOCKED, capability, role,
                        EnforcementLayer.GATEWAY,
                        "capabilities." + role + ".deny." + capability.wire(),
                        role + " 角色被 profile '" + profile.id() + "' 拒絕 " + capability.wire());
            }
            // Policy denies it, but we cannot enforce it at this layer yet — be honest.
            return new CapabilityDecision(CapabilityResult.ALLOWED_UNENFORCED, capability, role,
                    capability.layer(),
                    "capabilities." + role + ".deny." + capability.wire(),
                    "政策拒絕 " + capability.wire() + "，但本階段於 " + capability.layer()
                            + " 層未強制（需執行層 sandbox 才是資安保證）");
        }
        if (rc.allow().contains(capability)) {
            return new CapabilityDecision(CapabilityResult.ALLOWED, capability, role,
                    capability.layer(), "capabilities." + role + ".allow." + capability.wire(),
                    role + " 角色被允許 " + capability.wire());
        }
        // Whitelist default: not listed => denied.
        return new CapabilityDecision(CapabilityResult.BLOCKED, capability, role,
                EnforcementLayer.GATEWAY, "default-deny.not-listed",
                capability.wire() + " 未在 " + role + " 的允許清單中（預設拒絕）");
    }
}
