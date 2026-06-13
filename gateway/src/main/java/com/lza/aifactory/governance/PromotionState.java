package com.lza.aifactory.governance;

/**
 * Where a change sits on the road to becoming a trusted deliverable. This lives
 * ONLY in governance events — it is deliberately NOT a {@code TaskStatus}, so the
 * existing task terminal/reconcile semantics are untouched. A governance block
 * surfaces as the existing {@code FAILED} task status with a
 * {@code promotion_blocked:<gate>} message plus a {@link #BLOCKED} event here.
 */
public enum PromotionState {
    DRAFT,
    IMPLEMENTATION_COMPLETE,
    REVIEW_PENDING,
    HUMAN_APPROVAL_PENDING,
    DELIVERABLE_ELIGIBLE,
    DELIVERED,
    BLOCKED,
    OVERRIDDEN
}
