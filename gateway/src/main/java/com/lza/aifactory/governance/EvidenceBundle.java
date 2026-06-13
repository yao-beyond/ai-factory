package com.lza.aifactory.governance;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The frozen proof that backs a promotion decision: the base, the proposed
 * change, test and review outputs, the policy it ran under, and the gate
 * decisions. {@link #isComplete} answers "does this satisfy the profile's
 * deliverable gates?" — the precondition the promote-check enforces before a
 * change may become a deliverable. See docs/design/governance-runtime.md §3.4.
 */
public record EvidenceBundle(
        String baseCommit,
        String proposedDiffRef,
        String testResultsRef,
        String reviewReportRef,
        String policyHash,
        List<String> gateDecisions,
        Instant assembledAt) {

    /** Gates whose satisfaction requires a concrete artifact in the bundle. */
    public List<String> missingFor(GovernanceProfile profile) {
        List<String> missing = new ArrayList<>();
        List<String> gates = profile.gates() == null ? List.of() : profile.gates().beforeDeliverable();
        if (proposedDiffRef == null || proposedDiffRef.isBlank()) missing.add("proposed-diff");
        if (policyHash == null || policyHash.isBlank()) missing.add("policy-hash");
        if (gates.contains("tests-pass") && (testResultsRef == null || testResultsRef.isBlank())) {
            missing.add("test-results");
        }
        if (gates.contains("independent-review-pass")
                && (reviewReportRef == null || reviewReportRef.isBlank())) {
            missing.add("independent-review-report");
        }
        return missing;
    }

    public boolean isComplete(GovernanceProfile profile) {
        return missingFor(profile).isEmpty();
    }
}
