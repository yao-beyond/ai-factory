package com.lza.aifactory.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * A declarative governance contract for a class of work (e.g. payments,
 * internal tools, compliance patches). Loaded and validated at startup by
 * {@link GovernanceProfileLibrary}; the runtime interprets it at capability-
 * decision and promotion-gate time. See docs/design/governance-runtime.md §3.1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GovernanceProfile(
        String id,
        int version,
        boolean enabled,
        String riskTier,
        String title,
        String description,
        List<String> protectedPaths,
        Map<String, RoleCapabilities> capabilities,
        Gates gates,
        Independence independence,
        String humanApproval,
        boolean allowReviewBypass) {

    /** Per-role allow/deny lists over the {@link Capability} vocabulary. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleCapabilities(List<Capability> allow, List<Capability> deny) {
        public List<Capability> allow() { return allow == null ? List.of() : allow; }
        public List<Capability> deny() { return deny == null ? List.of() : deny; }
    }

    /** Mandatory checkpoints before a change may become a deliverable. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Gates(List<String> beforeDeliverable) {
        public List<String> beforeDeliverable() {
            return beforeDeliverable == null ? List.of() : beforeDeliverable;
        }
    }

    /** Independence requirements between the implementer and the reviewer. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Independence(
            boolean requireDistinctPrincipal,
            boolean requireReviewerReadOnly,
            boolean requireDistinctVendor) {
    }

    public boolean humanApprovalRequired() {
        return "required".equalsIgnoreCase(humanApproval);
    }

    public RoleCapabilities role(String role) {
        return capabilities == null ? null : capabilities.get(role);
    }
}
