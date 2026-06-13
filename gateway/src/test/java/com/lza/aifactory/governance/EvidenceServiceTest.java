package com.lza.aifactory.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceServiceTest {

    private GovernanceProfile profile(String id) {
        GovernanceProfileLibrary lib = new GovernanceProfileLibrary(new ObjectMapper(), "governance-profiles.json");
        lib.load();
        return lib.enabledById(id).orElseThrow();
    }

    @Test
    void completenessFollowsProfileGates() {
        GovernanceProfile critical = profile("compliance-patcher");
        // Missing test-results + review-report for a critical profile -> incomplete.
        EvidenceBundle bare = new EvidenceBundle("base", "diff", null, null, "sha256:p", List.of(), Instant.now());
        assertFalse(bare.isComplete(critical));
        assertTrue(bare.missingFor(critical).contains("test-results"));
        assertTrue(bare.missingFor(critical).contains("independent-review-report"));

        EvidenceBundle full = new EvidenceBundle("base", "diff", "tests.txt", "review.md",
                "sha256:p", List.of(), Instant.now());
        assertTrue(full.isComplete(critical));

        // standard-app gates only on tests + evidence, no independent review needed.
        GovernanceProfile standard = profile("standard-app");
        EvidenceBundle noReview = new EvidenceBundle("base", "diff", "tests.txt", null,
                "sha256:p", List.of(), Instant.now());
        assertTrue(noReview.isComplete(standard));
    }

    @Test
    void gepReportIsHumanReadableAndRedacted(@org.junit.jupiter.api.io.TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("T1"));
        EvidenceService svc = new EvidenceService(tmp.toString());
        GovernanceProfile p = profile("compliance-patcher");
        EvidenceBundle bundle = new EvidenceBundle("base", "diff", "tests.txt", "review.md",
                "sha256:policy leaked ghp_0123456789abcdef0123456789abcdef", List.of(), Instant.now());

        // A governance event log with a blocked-merge interception to surface.
        ObjectMapper m = new ObjectMapper().findAndRegisterModules();
        m.disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        GovernanceEventLog log = new GovernanceEventLog(m, tmp.toString());
        CapabilityDecision d = new CapabilityDecision(CapabilityResult.BLOCKED, Capability.MERGE_MAIN,
                "implementer", EnforcementLayer.GATEWAY, "rule", "implementer 被拒 merge:main");
        log.append("T1", p.id(), "sha256:p", "boundary-violation-blocked",
                new GovernanceEvent.Actor("ai.x", "implementer", "anthropic"), d, null);

        String gep = svc.renderGep("T1", p, bundle, log.read("T1"));
        assertTrue(gep.contains("治理證據包"));
        assertTrue(gep.contains("合規修復員"));
        assertTrue(gep.contains("🛡️ 攔截"));               // the interception high-moment
        assertTrue(gep.contains("雜湊鏈驗證通過"));
        assertFalse(gep.contains("ghp_0123456789abcdef")); // policy hash text redacted
    }
}
