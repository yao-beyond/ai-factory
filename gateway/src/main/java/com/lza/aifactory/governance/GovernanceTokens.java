package com.lza.aifactory.governance;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Issues and validates short, purpose- and task-scoped HMAC tokens, so the
 * pipeline can authenticate a {@code promote-check} call WITHOUT ever holding
 * the operator secret ({@code AIF_INTERNAL_SECRET}).
 *
 * <p>Why this exists: the operator secret must never enter the pipeline
 * environment, because the pipeline spawns untrusted code (AI dev/fix agents,
 * the project's own tests) that would inherit it and could then self-approve.
 * Instead the gateway issues a per-task {@code promote-check} token; even if that
 * token leaks into the task's workspace, it only authorises that one task's gate
 * evaluation (a harmless read), never an approval/override.
 *
 * <p>A leaf component (no other governance deps) so it can be injected by both the
 * gateway controller and the pipeline executor without a dependency cycle.
 */
@Component
public class GovernanceTokens {

    private final byte[] key;

    public GovernanceTokens(@Value("${AIF_INTERNAL_SECRET:}") String internalSecret) {
        if (internalSecret != null && !internalSecret.isBlank()) {
            this.key = internalSecret.getBytes(StandardCharsets.UTF_8);
        } else {
            byte[] rnd = new byte[32];
            new java.security.SecureRandom().nextBytes(rnd);
            this.key = rnd;
        }
    }

    /** Issue a token bound to (taskId, purpose). */
    public String issue(String taskId, String purpose) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(key, "HmacSHA256"));
            byte[] h = mac.doFinal((taskId + "|" + purpose).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder("ptok:");
            for (byte b : h) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC unavailable", e);
        }
    }

    /** Constant-time check that {@code presented} is the token for (taskId, purpose). */
    public boolean isValid(String taskId, String purpose, String presented) {
        if (presented == null || presented.isBlank()) return false;
        return MessageDigest.isEqual(
                presented.getBytes(StandardCharsets.UTF_8),
                issue(taskId, purpose).getBytes(StandardCharsets.UTF_8));
    }

    // --- Per-run nonce (gateway-owned, in-memory) --------------------------------
    // Binds approval markers to the CURRENT run. Kept in gateway memory, never on
    // disk, so untrusted workspace code cannot read or rotate it to revive/replay a
    // stale approval. A gateway restart drops it (markers re-pend — fail-closed).
    private final java.util.concurrent.ConcurrentHashMap<String, String> runNonces =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Start a fresh run for a task: rotate its nonce (called by the executor per launch). */
    public void rotateRunNonce(String taskId) {
        byte[] r = new byte[16];
        new java.security.SecureRandom().nextBytes(r);
        StringBuilder sb = new StringBuilder();
        for (byte b : r) sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        runNonces.put(taskId, sb.toString());
    }

    /** Current run nonce for a task ("" if none — consistent for both write and validate). */
    public String runNonce(String taskId) {
        return runNonces.getOrDefault(taskId, "");
    }
}
