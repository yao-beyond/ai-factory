package com.lza.aifactory.governance;

import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.service.TaskService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-promote-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh"
})
class GovernancePromotionServiceTest {

    @Autowired
    private GovernancePromotionService promotion;
    @Autowired
    private TaskService taskService;
    @Autowired
    private GovernanceTokens governanceTokens;

    private final Path workDir = Path.of(System.getProperty("java.io.tmpdir"), "ai-factory-promote-test");

    private String submit(String id, String profileId) throws Exception {
        IssueDto dto = new IssueDto();
        dto.setSource("web");
        dto.setExternalId(id);
        dto.setTitle("t");
        dto.setDescription("d");
        dto.setMaxAgents(1);
        dto.setMode("new");
        dto.setGovernanceProfileId(profileId);
        String taskId = taskService.submit(dto).taskId();
        // The work dir persists across JVM runs; clear leftover governance markers
        // and the event log so each test starts from a clean governance state.
        Path dir = workDir.resolve(taskId);
        for (String f : new String[]{"governance.approve", "governance.reject",
                "governance.override", "governance-events.jsonl"}) {
            Files.deleteIfExists(dir.resolve(f));
        }
        return taskId;
    }

    private PromoteCheckRequest req(String testStatus, String review) {
        return new PromoteCheckRequest(testStatus, review, "ai/x/final", "local");
    }

    @Test
    void failingTestsBlock() throws Exception {
        String t = submit("PR-TESTS", "compliance-patcher");
        PromotionDecision d = promotion.check(t, req("fail", "docs/ai/CODEX_REVIEW.md"));
        assertEquals("BLOCKED", d.state());
        assertEquals("tests-pass", d.blockingGate());
    }

    @Test
    void unknownTestStatusFailsClosed() throws Exception {
        String t = submit("PR-TESTS-UNK", "compliance-patcher");
        PromotionDecision d = promotion.check(t, req("unknown", "docs/ai/CODEX_REVIEW.md"));
        assertEquals("BLOCKED", d.state());
        assertEquals("tests-pass", d.blockingGate());
    }

    @Test
    void noTestFrameworkBlocksCriticalButNotStandard() throws Exception {
        // Critical code without tests must not ship.
        String crit = submit("PR-NONE-CRIT", "compliance-patcher");
        promotion.recordApproval(crit, "human@test");
        PromotionDecision blocked = promotion.check(crit, req("none", "docs/ai/CODEX_REVIEW.md"));
        assertEquals("BLOCKED", blocked.state());
        assertEquals("tests-pass", blocked.blockingGate());

        // A low-risk deliverable with no test framework is acceptable.
        String std = submit("PR-NONE-STD", "standard-app");
        PromotionDecision ok = promotion.check(std, req("none", null));
        assertEquals("DELIVERABLE_ELIGIBLE", ok.state());
    }

    @Test
    void criticalWaitsForHumanApprovalThenBecomesEligible() throws Exception {
        String t = submit("PR-APPROVE", "compliance-patcher");
        // tests pass + review present, but no approval marker yet -> pending.
        PromotionDecision pending = promotion.check(t, req("pass", "docs/ai/CODEX_REVIEW.md"));
        assertEquals("HUMAN_APPROVAL_PENDING", pending.state());

        // Human approves -> all gates satisfied -> eligible.
        promotion.recordApproval(t, "human@test");
        PromotionDecision ok = promotion.check(t, req("pass", "docs/ai/CODEX_REVIEW.md"));
        assertEquals("DELIVERABLE_ELIGIBLE", ok.state());
    }

    @Test
    void forgedApprovalMarkerIsRejected() throws Exception {
        String t = submit("PR-FORGE", "compliance-patcher");
        // Untrusted workspace code (a project test, a dev agent) writes the marker
        // file directly with garbage content — it has no valid HMAC token.
        Files.writeString(workDir.resolve(t).resolve("governance.approve"), "approved-by-evil\n");
        PromotionDecision forged = promotion.check(t, req("pass", "docs/ai/CODEX_REVIEW.md"));
        assertEquals("HUMAN_APPROVAL_PENDING", forged.state(), "forged marker must not satisfy the gate");

        // A genuine gateway-issued approval (signed) IS honoured.
        promotion.recordApproval(t, "human@test");
        assertEquals("DELIVERABLE_ELIGIBLE",
                promotion.check(t, req("pass", "docs/ai/CODEX_REVIEW.md")).state());
    }

    @Test
    void staleApprovalFromPreviousRunDoesNotApproveNewRun() throws Exception {
        String t = submit("PR-STALE", "compliance-patcher");
        // Run 1: rotate the gateway-owned nonce, then run 1's human approval.
        governanceTokens.rotateRunNonce(t);
        promotion.recordApproval(t, "human@test");
        org.junit.jupiter.api.Assertions.assertEquals("DELIVERABLE_ELIGIBLE",
                promotion.check(t, req("pass", "docs/ai/CODEX_REVIEW.md")).state());

        // A NEW run rotates the nonce again; the surviving run-1 approval marker must
        // NOT approve this run.
        governanceTokens.rotateRunNonce(t);
        org.junit.jupiter.api.Assertions.assertEquals("HUMAN_APPROVAL_PENDING",
                promotion.check(t, req("pass", "docs/ai/CODEX_REVIEW.md")).state());

        // A fresh approval under the new nonce works again.
        promotion.recordApproval(t, "human@test");
        org.junit.jupiter.api.Assertions.assertEquals("DELIVERABLE_ELIGIBLE",
                promotion.check(t, req("pass", "docs/ai/CODEX_REVIEW.md")).state());
    }

    @Test
    void humanRejectionBlocks() throws Exception {
        String t = submit("PR-REJECT", "compliance-patcher");
        promotion.recordRejection(t, "human@test");
        PromotionDecision d = promotion.check(t, req("pass", "docs/ai/CODEX_REVIEW.md"));
        assertEquals("BLOCKED", d.state());
        assertEquals("human-approval", d.blockingGate());
    }

    @Test
    void missingReviewReportBlocksCriticalEvenWhenIndependent() throws Exception {
        String t = submit("PR-NOREVIEW", "compliance-patcher");
        promotion.recordApproval(t, "human@test");
        // Independence passes (claude vs codex) but no review report artifact.
        PromotionDecision d = promotion.check(t, req("pass", ""));
        assertEquals("BLOCKED", d.state());
        assertEquals("independent-review-pass", d.blockingGate());
    }

    @Test
    void standardAppNeedsNoReviewOrApproval() throws Exception {
        String t = submit("PR-STD", "standard-app");
        PromotionDecision d = promotion.check(t, req("pass", null));
        assertEquals("DELIVERABLE_ELIGIBLE", d.state());
    }

    @Test
    void emergencyHotfixRequiresOverrideRationale() throws Exception {
        String t = submit("PR-EMERG", "emergency-hotfix");
        promotion.recordApproval(t, "human@test");
        // No override recorded yet -> blocked on override-rationale-recorded.
        PromotionDecision blocked = promotion.check(t, req("pass", null));
        assertEquals("BLOCKED", blocked.state());
        assertEquals("override-rationale-recorded", blocked.blockingGate());

        promotion.recordOverride(t, "lead@test", "生產事故緊急修復", "INC-1", "2026-06-14T00:00:00Z");
        PromotionDecision ok = promotion.check(t, req("pass", null));
        assertEquals("DELIVERABLE_ELIGIBLE", ok.state());
    }

    @Test
    void checkEmitsGovernanceEvents() throws Exception {
        String t = submit("PR-EVT", "standard-app");
        promotion.check(t, req("pass", null));
        // Events were written to the task's append-only log.
        Path events = workDir.resolve(t).resolve("governance-events.jsonl");
        org.junit.jupiter.api.Assertions.assertTrue(Files.exists(events));
        String content = Files.readString(events);
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("gate-passed"));
        org.junit.jupiter.api.Assertions.assertTrue(content.contains("promotion-state-change"));
    }
}
