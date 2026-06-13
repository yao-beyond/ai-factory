package com.lza.aifactory.governance;

/**
 * The promote-check verdict the pipeline acts on before a change becomes a
 * deliverable. {@code state} is one of DELIVERABLE_ELIGIBLE / BLOCKED /
 * HUMAN_APPROVAL_PENDING (see {@link PromotionState}). On BLOCKED, {@code
 * blockingGate} names the gate that failed.
 */
public record PromotionDecision(
        String state,
        String blockingGate,
        String reason,
        String profile,
        String policyHash) {

    public static PromotionDecision eligible(String profile, String policyHash) {
        return new PromotionDecision("DELIVERABLE_ELIGIBLE", null,
                "所有交付前閘門通過", profile, policyHash);
    }

    public static PromotionDecision blocked(String gate, String reason, String profile, String policyHash) {
        return new PromotionDecision("BLOCKED", gate, reason, profile, policyHash);
    }

    public static PromotionDecision awaitingApproval(String profile, String policyHash) {
        return new PromotionDecision("HUMAN_APPROVAL_PENDING", "human-approval",
                "等待人類核准交付", profile, policyHash);
    }
}
