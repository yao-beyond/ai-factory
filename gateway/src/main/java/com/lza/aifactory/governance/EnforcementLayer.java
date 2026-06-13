package com.lza.aifactory.governance;

/**
 * Where a capability denial can actually be enforced. This is the honesty
 * backbone of the runtime: only {@link #GATEWAY} denials are truly prevented in
 * Phase 1; the rest are recorded as policy intent that is not yet enforced, so
 * the product never claims a containment it does not have.
 *
 * <p>See docs/design/governance-runtime.md §0 (honest enforcement boundary).
 */
public enum EnforcementLayer {
    /** Enforced at the gateway/promotion boundary today (merge, PR, approval, evidence). */
    GATEWAY,
    /** Requires a real execution sandbox (seccomp/network policy/read-only FS) to be a security claim. */
    EXECUTION_SANDBOX,
    /** Fundamentally only detectable after the fact, never prevented by policy. */
    DETECTION_ONLY
}
