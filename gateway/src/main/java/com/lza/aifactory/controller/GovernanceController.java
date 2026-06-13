package com.lza.aifactory.controller;

import com.lza.aifactory.governance.EvidenceBundle;
import com.lza.aifactory.governance.EvidenceService;
import com.lza.aifactory.governance.GovernanceEventLog;
import com.lza.aifactory.governance.GovernancePromotionService;
import com.lza.aifactory.governance.GovernanceProfile;
import com.lza.aifactory.governance.GovernanceProfileLibrary;
import com.lza.aifactory.governance.PromoteCheckRequest;
import com.lza.aifactory.governance.PromotionDecision;
import com.lza.aifactory.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only governance query endpoints (Phase 1a). Surfaces the profile picker,
 * the per-task append-only event log (verified + redacted), and the evidence
 * bundle / human-readable GEP. Write paths (promote-check, approve, override) and
 * the interception dashboard land in Phase 1b/1c.
 *
 * <p>See docs/design/governance-runtime.md §5.
 */
@RestController
@RequestMapping("/gateway/governance")
public class GovernanceController {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GovernanceController.class);

    private final GovernanceProfileLibrary profileLibrary;
    private final GovernanceEventLog eventLog;
    private final EvidenceService evidenceService;
    private final GovernancePromotionService promotionService;
    private final TaskService taskService;
    private final com.lza.aifactory.governance.GovernanceTokens governanceTokens;
    private final String internalSecret;

    public GovernanceController(GovernanceProfileLibrary profileLibrary,
                                GovernanceEventLog eventLog,
                                EvidenceService evidenceService,
                                GovernancePromotionService promotionService,
                                TaskService taskService,
                                com.lza.aifactory.governance.GovernanceTokens governanceTokens,
                                @Value("${AIF_INTERNAL_SECRET:}") String internalSecret) {
        this.profileLibrary = profileLibrary;
        this.eventLog = eventLog;
        this.evidenceService = evidenceService;
        this.promotionService = promotionService;
        this.taskService = taskService;
        this.governanceTokens = governanceTokens;
        String configured = internalSecret == null ? "" : internalSecret;
        if (configured.isBlank()) {
            // Fail-closed by default. With no operator secret the human-approval
            // endpoints would otherwise accept ANY caller — and untrusted workspace
            // code inherits AIF_GATEWAY_URL (see BashPipelineExecutor), so it could
            // POST /approve and self-clear the very gate that is meant to hold for a
            // human. Generate an ephemeral secret, held only in this process's
            // memory, and surface it ONCE on the gateway console: an operator can
            // read the console; a separate workspace process cannot read it.
            this.internalSecret = randomSecret();
            log.warn("""
                    [governance] AIF_INTERNAL_SECRET is not set — generated an ephemeral \
                    human-approval secret for this gateway run:
                        ephemeral-approval-secret {}
                      Send it as the X-AIF-Internal header (the dashboard prompts for it). A \
                    separate workspace/pipeline process cannot read this console.
                      Prefer setting AIF_INTERNAL_SECRET in the gateway environment for a \
                    stable secret that is also kept OUT of any file you redirect stdout to \
                    (a redirected log readable by task code would leak this value).""",
                    this.internalSecret);
        } else {
            this.internalSecret = configured;
        }
    }

    private static String randomSecret() {
        byte[] r = new byte[24];
        new java.security.SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        return sb.toString();
    }

    /** Display-safe profile list for the picker (no capability internals). */
    @GetMapping("/profiles")
    public Map<String, Object> profiles() {
        List<Map<String, Object>> view = profileLibrary.enabled().stream()
                .map(GovernanceController::profileView)
                .toList();
        return Map.of("profiles", view);
    }

    /** The task's verified, redacted governance event chain. */
    @GetMapping("/{taskId}/events")
    public ResponseEntity<?> events(@PathVariable String taskId) {
        // Normalise to a safe single path segment before any disk access reuses it
        // (shared guard with TaskService — defends against encoded-slash traversal).
        taskId = taskService.normalizeTaskId(taskId);
        GovernanceEventLog.ReadResult r = eventLog.read(taskId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("tampered", r.tampered());
        body.put("brokenSeq", r.brokenSeq());
        body.put("events", r.events());
        return ResponseEntity.ok(body);
    }

    /** Evidence bundle status + completeness + the human-readable GEP markdown. */
    @GetMapping("/{taskId}/evidence")
    public ResponseEntity<?> evidence(@PathVariable String taskId) {
        taskId = taskService.normalizeTaskId(taskId);
        String profileId = taskService.readGovernanceProfileId(taskId);
        GovernanceProfile profile = profileLibrary.enabledById(profileId)
                .orElseGet(() -> profileLibrary.enabledById("standard-app").orElse(null));
        if (profile == null) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "no_profile", "message", "找不到治理 profile"));
        }
        EvidenceBundle bundle = evidenceService.assemble(taskId, null);
        GovernanceEventLog.ReadResult events = eventLog.read(taskId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", taskId);
        body.put("profile", profile.id());
        body.put("complete", bundle.isComplete(profile));
        body.put("missing", bundle.missingFor(profile));
        body.put("gepMarkdown", evidenceService.renderGep(taskId, profile, bundle, events));
        return ResponseEntity.ok(body);
    }

    /**
     * Run the deliverable gates (pipeline → gateway). Returns the promotion
     * decision the pipeline acts on. When an internal secret is configured it is
     * required (machine-to-machine); otherwise the trusted-network posture of the
     * other control endpoints applies.
     */
    @PostMapping("/{taskId}/promote-check")
    public ResponseEntity<?> promoteCheck(@PathVariable String taskId,
                                          @RequestBody(required = false) PromoteCheckRequest body,
                                          HttpServletRequest request) {
        String id = taskService.normalizeTaskId(taskId);
        // The pipeline authenticates with its scoped per-task promote token (it does
        // NOT hold the operator secret); an operator/curl may also use the secret.
        // The secret is always present (auto-generated when unset), so this never
        // falls open to an unauthenticated caller.
        if (!secretMatches(request)
                && !governanceTokens.isValid(id, "promote-check", request.getHeader("X-AIF-Promote-Token"))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("error", "forbidden", "message", "missing/invalid promote-check authorization"));
        }
        if (taskService.findStatus(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "not_found", "message", "unknown task"));
        }
        PromoteCheckRequest req = body != null ? body
                : new PromoteCheckRequest("unknown", null, null, "local");
        PromotionDecision d = promotionService.check(id, req);
        return ResponseEntity.ok(d);
    }

    /** Human approves delivery (writes governance.approve). */
    @PostMapping("/{taskId}/approve")
    public ResponseEntity<?> approve(@PathVariable String taskId, HttpServletRequest request) {
        return decideHuman(taskId, true, request);
    }

    /** Human rejects delivery (writes governance.reject). */
    @PostMapping("/{taskId}/reject")
    public ResponseEntity<?> reject(@PathVariable String taskId, HttpServletRequest request) {
        return decideHuman(taskId, false, request);
    }

    /** Human override of a failed gate, with mandatory rationale/ticket/expiry. */
    @PostMapping("/{taskId}/override")
    public ResponseEntity<?> override(@PathVariable String taskId, HttpServletRequest request) {
        ResponseEntity<?> denied = requireInternalSecret(request);
        if (denied != null) return denied;
        String id = taskService.normalizeTaskId(taskId);
        if (taskService.findStatus(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        try {
            promotionService.recordOverride(id, actor(request),
                    request.getParameter("rationale"), request.getParameter("ticket"),
                    request.getParameter("expiry"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "bad_override", "message", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "io_error"));
        }
        return ResponseEntity.ok(Map.of("taskId", id, "override", "recorded"));
    }

    private ResponseEntity<?> decideHuman(String taskId, boolean approve, HttpServletRequest request) {
        // Human approve/reject directly drive the human-approval gate, so they are
        // at least as protected as promote-check: required when a secret is set,
        // trusted-network posture (like confirm/abort) when it is not.
        ResponseEntity<?> denied = requireInternalSecret(request);
        if (denied != null) return denied;
        String id = taskService.normalizeTaskId(taskId);
        if (taskService.findStatus(id).isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", "not_found"));
        }
        try {
            if (approve) {
                promotionService.recordApproval(id, actor(request));
            } else {
                promotionService.recordRejection(id, actor(request));
            }
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "io_error"));
        }
        return ResponseEntity.ok(Map.of("taskId", id, "decision", approve ? "approved" : "rejected"));
    }

    /**
     * Gate the human-approval endpoints. {@code internalSecret} is never blank (it
     * is auto-generated when unset, see constructor), so this ALWAYS enforces and
     * never falls open to unauthenticated workspace code.
     */
    private ResponseEntity<?> requireInternalSecret(HttpServletRequest request) {
        if (secretMatches(request)) return null;
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "forbidden", "message", "missing/invalid internal secret"));
    }

    /** Constant-time check that the request carries the operator secret. */
    private boolean secretMatches(HttpServletRequest request) {
        String got = request.getHeader("X-AIF-Internal");
        if (got == null) return false;
        return java.security.MessageDigest.isEqual(
                got.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                internalSecret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Best-effort actor label for the audit trail (no user system yet). */
    private String actor(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        return "human@" + (remote == null ? "unknown" : remote);
    }

    /** One-click human-readable Governance Evidence Pack (GEP) as a Markdown download. */
    @GetMapping(value = "/{taskId}/gep", produces = "text/markdown; charset=utf-8")
    public ResponseEntity<?> gep(@PathVariable String taskId) {
        String id = taskService.normalizeTaskId(taskId);
        if (taskService.findStatus(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String profileId = taskService.readGovernanceProfileId(id);
        GovernanceProfile profile = profileLibrary.enabledById(profileId)
                .orElseGet(() -> profileLibrary.enabledById("standard-app").orElse(null));
        if (profile == null) {
            return ResponseEntity.internalServerError().body("找不到治理 profile");
        }
        String md = evidenceService.renderGep(id, profile,
                evidenceService.assemble(id, null), eventLog.read(id));
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"GEP-"
                        + id.replaceAll("[^A-Za-z0-9._-]", "-") + ".md\"")
                .body(md);
    }

    private static Map<String, Object> profileView(GovernanceProfile p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("title", p.title());
        m.put("description", p.description());
        m.put("riskTier", p.riskTier());
        m.put("humanApproval", p.humanApproval());
        return m;
    }
}
