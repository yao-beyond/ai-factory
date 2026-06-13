package com.lza.aifactory.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lza.aifactory.config.AiFactoryProperties;
import com.lza.aifactory.service.TaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the deliverable gates for a task and records the outcome as governance
 * events. This is the GATEWAY-layer enforcement point: a change cannot become a
 * deliverable until every {@code gates.beforeDeliverable} passes. Machine gates
 * (tests, independence, evidence) are decided here; the human-approval gate is
 * resolved against {@code governance.approve}/{@code governance.reject} markers
 * the dashboard/endpoints write. See docs/design/governance-runtime.md §4.
 */
@Service
public class GovernancePromotionService {

    private static final Logger log = LoggerFactory.getLogger(GovernancePromotionService.class);

    private final GovernanceProfileLibrary library;
    private final IndependenceChecker independenceChecker;
    private final EvidenceService evidenceService;
    private final GovernanceEventLog eventLog;
    private final TaskService taskService;
    private final AiFactoryProperties properties;
    private final ObjectMapper canonicalMapper;
    private final GovernanceTokens governanceTokens;
    private final Path workDir;
    // Secret used to HMAC-sign approval/override markers so untrusted code running
    // in the workspace (project tests, AI dev agents) cannot FORGE them by writing
    // the marker file directly. Prefer the configured internal secret (stable
    // across restarts); otherwise a per-process random key (a restart invalidates
    // pending approvals — fail-closed, human re-approves).
    private final byte[] markerKey;

    public GovernancePromotionService(GovernanceProfileLibrary library,
                                      IndependenceChecker independenceChecker,
                                      EvidenceService evidenceService,
                                      GovernanceEventLog eventLog,
                                      TaskService taskService,
                                      AiFactoryProperties properties,
                                      ObjectMapper objectMapper,
                                      GovernanceTokens governanceTokens,
                                      @Value("${ai-factory.work-dir}") String workDir,
                                      @Value("${AIF_INTERNAL_SECRET:}") String internalSecret) {
        this.library = library;
        this.independenceChecker = independenceChecker;
        this.evidenceService = evidenceService;
        this.eventLog = eventLog;
        this.taskService = taskService;
        this.properties = properties;
        this.governanceTokens = governanceTokens;
        // Key-sorted mapper so the policy hash is canonical/reproducible.
        this.canonicalMapper = objectMapper.copy()
                .configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);
        this.workDir = Path.of(workDir);
        if (internalSecret != null && !internalSecret.isBlank()) {
            this.markerKey = internalSecret.getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] rnd = new byte[32];
            new java.security.SecureRandom().nextBytes(rnd);
            this.markerKey = rnd;
        }
    }

    /**
     * Unforgeable token bound to (taskId, marker, RUN NONCE): HMAC-SHA256 over the
     * gateway key. Binding the current run's nonce means an approval marker left
     * over from a PREVIOUS run of the same task id no longer validates — a fresh
     * run gets a fresh nonce, so a stale approval cannot approve it.
     */
    private String markerToken(String taskId, String name) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(markerKey, "HmacSHA256"));
            String msg = taskId + "|" + name + "|" + governanceTokens.runNonce(taskId);
            byte[] h = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("hmac:");
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /**
     * A marker counts only if its first field equals the gateway-issued HMAC token.
     * A file forged by untrusted workspace code (with no/garbage content) fails this,
     * so it cannot satisfy a human-approval / override gate.
     */
    private boolean markerValid(String taskId, String name) {
        Path f = workDir.resolve(taskService.normalizeTaskId(taskId)).resolve(name);
        if (!Files.exists(f)) return false;
        try {
            String first = Files.readString(f).strip().split("\\s+", 2)[0];
            return MessageDigest.isEqual(
                    first.getBytes(StandardCharsets.UTF_8),
                    markerToken(taskId, name).getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    public GovernanceProfile resolveProfile(String taskId) {
        String id = taskService.readGovernanceProfileId(taskId);
        return library.enabledById(id)
                .or(() -> library.enabledById("standard-app"))
                .orElseThrow(() -> new IllegalStateException("找不到治理 profile"));
    }

    /**
     * Stable canonical content hash of a profile, recorded on every governance
     * decision. Uses a key-sorted mapper so the hash is reproducible, and FAILS
     * CLOSED (throws) on a hashing error rather than emitting a bogus
     * non-blank "sha256:unknown" into compliance evidence.
     */
    public String policyHash(GovernanceProfile profile) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalMapper.writeValueAsString(profile).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("sha256:");
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("無法計算 policyHash（fail-closed）", e);
        }
    }

    /**
     * Evaluate every deliverable gate. Emits a governance event per gate and a
     * final promotion-state-change. The pipeline acts on the returned state.
     */
    public PromotionDecision check(String taskId, PromoteCheckRequest req) {
        GovernanceProfile profile = resolveProfile(taskId);
        String policyHash = policyHash(profile);
        GovernanceEvent.Actor system = new GovernanceEvent.Actor("system.gateway", "gateway", null);
        String testStatus = req.testStatusOrUnknown();
        // The test result IS the evidence; only a verified pass/none counts as a
        // present test-results artifact for evidence completeness.
        String testRef = ("pass".equals(testStatus) || "none".equals(testStatus))
                ? "tests:" + testStatus : null;
        EvidenceBundle bundle = new EvidenceBundle(null, req.diffRef(),
                testRef, req.reviewReportRef(), policyHash, List.of(), Instant.now());

        // requireHumanMerge is now actually consulted (not just config intent): in
        // existing-repo mode the gate records that the AI did not merge and a human
        // must, so the control is real and audited rather than nominal.
        if ("existing".equalsIgnoreCase(req.mode()) && properties.getSecurity().isRequireHumanMerge()) {
            eventLog.append(taskId, profile.id(), policyHash, "human-merge-required", system, null,
                    Map.of("note", "AI 不合併到保護分支；需人類於 git provider 合併"));
        }

        for (String gate : profile.gates().beforeDeliverable()) {
            PromotionDecision blocked = evaluateGate(taskId, profile, policyHash, gate, req, bundle, system);
            if (blocked != null) {
                // HUMAN_APPROVAL_PENDING is not a block — surface it as-is.
                if ("HUMAN_APPROVAL_PENDING".equals(blocked.state())) {
                    emitPromotion(taskId, profile, policyHash, system, "HUMAN_APPROVAL_PENDING", gate);
                    return blocked;
                }
                eventLog.append(taskId, profile.id(), policyHash, "gate-failed", system, null,
                        Map.of("gate", gate, "reason", blocked.reason()));
                emitPromotion(taskId, profile, policyHash, system, "BLOCKED", gate);
                return blocked;
            }
            eventLog.append(taskId, profile.id(), policyHash, "gate-passed", system, null, Map.of("gate", gate));
        }
        emitPromotion(taskId, profile, policyHash, system, "DELIVERABLE_ELIGIBLE", null);
        return PromotionDecision.eligible(profile.id(), policyHash);
    }

    /** Returns a non-null decision when the gate blocks or pends; null when it passes. */
    private PromotionDecision evaluateGate(String taskId, GovernanceProfile profile, String policyHash,
                                           String gate, PromoteCheckRequest req, EvidenceBundle bundle,
                                           GovernanceEvent.Actor system) {
        switch (gate) {
            case "tests-pass": {
                String ts = req.testStatusOrUnknown();
                boolean elevated = "high".equals(profile.riskTier()) || "critical".equals(profile.riskTier());
                switch (ts) {
                    case "pass":
                        return null;
                    case "fail":
                        return PromotionDecision.blocked(gate, "測試未通過", profile.id(), policyHash);
                    case "none":
                        // No test framework: acceptable for low-risk deliverables, but
                        // regulated/critical code must not ship without tests.
                        if (elevated) {
                            return PromotionDecision.blocked(gate,
                                    "受規管／關鍵程式必須有測試，但專案沒有可執行的測試", profile.id(), policyHash);
                        }
                        return null;
                    default: // "unknown" — fail-closed: we could not verify tests
                        return PromotionDecision.blocked(gate, "無法確認測試結果（fail-closed）",
                                profile.id(), policyHash);
                }
            }
            case "independent-review-pass": {
                IndependenceCheck ic = independenceChecker.check(profile);
                eventLog.append(taskId, profile.id(), policyHash,
                        ic.passed() ? "independence-check-passed" : "independence-check-failed",
                        system, null, Map.of("implementer", ic.implementer(), "reviewer", ic.reviewer(),
                                "failures", ic.failures()));
                if (!ic.passed()) {
                    return PromotionDecision.blocked(gate,
                            "獨立性不足：" + String.join("；", ic.failures()), profile.id(), policyHash);
                }
                if (req.reviewReportRef() == null || req.reviewReportRef().isBlank()) {
                    return PromotionDecision.blocked(gate, "缺少獨立審查報告", profile.id(), policyHash);
                }
                return null;
            }
            case "human-approval": {
                if (!profile.humanApprovalRequired()) return null;
                if (markerValid(taskId, "governance.reject")) {
                    return PromotionDecision.blocked(gate, "人類拒絕交付", profile.id(), policyHash);
                }
                if (markerValid(taskId, "governance.approve")) {
                    eventLog.append(taskId, profile.id(), policyHash, "human-approval-recorded", system, null, null);
                    return null;
                }
                return PromotionDecision.awaitingApproval(profile.id(), policyHash);
            }
            case "override-rationale-recorded":
                if (!markerValid(taskId, "governance.override")) {
                    return PromotionDecision.blocked(gate, "緊急修復需先記錄例外理由（override）",
                            profile.id(), policyHash);
                }
                return null;
            case "evidence-bundle-complete":
                if (!bundle.isComplete(profile)) {
                    return PromotionDecision.blocked(gate,
                            "證據包不完整，缺少：" + String.join("、", bundle.missingFor(profile)),
                            profile.id(), policyHash);
                }
                return null;
            default:
                // Unknown gates are caught at library load; treat as a hard block here.
                return PromotionDecision.blocked(gate, "未知閘門", profile.id(), policyHash);
        }
    }

    private void emitPromotion(String taskId, GovernanceProfile profile, String policyHash,
                               GovernanceEvent.Actor system, String to, String gate) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("to", to);
        if (gate != null) extra.put("gate", gate);
        eventLog.append(taskId, profile.id(), policyHash, "promotion-state-change", system, null, extra);
    }


    /** Human approves delivery: drop the governance.approve marker + record it. */
    public void recordApproval(String taskId, String by) throws java.io.IOException {
        GovernanceProfile profile = resolveProfile(taskId);
        writeMarker(taskId, "governance.approve", by);
        eventLog.append(taskId, profile.id(), policyHash(profile), "human-approval-recorded",
                new GovernanceEvent.Actor(by, "humanApprover", null), null, null);
    }

    /** Human rejects delivery: drop the governance.reject marker + record it. */
    public void recordRejection(String taskId, String by) throws java.io.IOException {
        GovernanceProfile profile = resolveProfile(taskId);
        writeMarker(taskId, "governance.reject", by);
        eventLog.append(taskId, profile.id(), policyHash(profile), "human-rejection-recorded",
                new GovernanceEvent.Actor(by, "humanApprover", null), null, null);
    }

    /** Record a human override of a gate, with mandatory rationale/ticket/expiry. */
    public void recordOverride(String taskId, String by, String rationale, String ticket, String expiry)
            throws java.io.IOException {
        if (rationale == null || rationale.isBlank() || ticket == null || ticket.isBlank()
                || expiry == null || expiry.isBlank()) {
            throw new IllegalArgumentException("override 需要 rationale、ticket 與 expiry");
        }
        GovernanceProfile profile = resolveProfile(taskId);
        writeMarker(taskId, "governance.override", by);
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("by", by);
        extra.put("rationale", rationale);
        extra.put("ticket", ticket);
        extra.put("expiry", expiry);
        eventLog.append(taskId, profile.id(), policyHash(profile), "override-with-rationale",
                new GovernanceEvent.Actor(by, "humanApprover", null), null, extra);
    }

    private void writeMarker(String taskId, String name, String by) throws java.io.IOException {
        Path dir = workDir.resolve(taskService.normalizeTaskId(taskId));
        Files.createDirectories(dir);
        // First field is the unforgeable HMAC token the gate validates; the rest is
        // human-readable audit context.
        Files.writeString(dir.resolve(name),
                markerToken(taskId, name) + " " + Instant.now() + " " + (by == null ? "" : by) + "\n");
    }
}
