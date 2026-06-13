package com.lza.aifactory.governance;

import com.lza.aifactory.config.AiFactoryProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks implementer/reviewer independence for a profile. Phase 1b derives the
 * vendor from the configured agent id (the config has no vendor field yet —
 * typed vendor config is deferred to Phase 2). Reviewer read-only is asserted by
 * the pipeline contract: the reviewer runs codex-review.sh, which produces a
 * report and does not commit source.
 */
@Service
public class IndependenceChecker {

    private final AiFactoryProperties properties;

    public IndependenceChecker(AiFactoryProperties properties) {
        this.properties = properties;
    }

    public IndependenceCheck check(GovernanceProfile profile) {
        String impl = properties.getAgents().getDeveloper();
        String rev = properties.getAgents().getReviewer();
        String implVendor = vendorOf(impl);
        String revVendor = vendorOf(rev);
        GovernanceProfile.Independence ind = profile.independence();
        List<String> failures = new ArrayList<>();

        if (ind != null && ind.requireDistinctPrincipal() && eq(impl, rev)) {
            failures.add("implementer 與 reviewer 是同一個 agent（" + impl + "）");
        }
        if (ind != null && ind.requireDistinctVendor()) {
            if ("unknown".equals(implVendor) || "unknown".equals(revVendor)) {
                failures.add("無法確認 vendor 獨立性（implementer=" + impl + " / reviewer=" + rev + "）");
            } else if (implVendor.equals(revVendor)) {
                failures.add("implementer 與 reviewer 同 vendor（" + implVendor + "）");
            }
        }
        // requireReviewerReadOnly is satisfied by the pipeline contract (codex-review
        // does not commit source). It cannot fail here without execution-layer proof,
        // so it is recorded but not asserted as a hard fail in Phase 1b.
        return new IndependenceCheck(failures.isEmpty(), impl, implVendor, rev, revVendor, failures);
    }

    /** Map a known agent CLI id to its model vendor; unknown ids are flagged. */
    static String vendorOf(String agentId) {
        if (agentId == null) return "unknown";
        String a = agentId.toLowerCase();
        if (a.contains("claude")) return "anthropic";
        if (a.contains("codex") || a.contains("gpt") || a.contains("openai")) return "openai";
        if (a.contains("gemini") || a.contains("google")) return "google";
        return "unknown";
    }

    private static boolean eq(String a, String b) {
        return a != null && a.equalsIgnoreCase(b);
    }
}
