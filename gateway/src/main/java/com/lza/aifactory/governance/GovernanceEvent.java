package com.lza.aifactory.governance;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.Map;

/**
 * One append-only governance decision record. The {@link Integrity} block
 * hash-chains events per task so accidental edits or partial tampering are
 * detectable on read. See docs/design/governance-runtime.md §3.3 for the threat
 * model (this is tamper-EVIDENT, not tamper-PROOF against a host-level attacker
 * who can rewrite the whole file — that needs Phase 2 external signing).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GovernanceEvent(
        String eventId,
        long seq,
        String eventType,
        Instant occurredAt,
        String taskId,
        String profile,
        String policyHash,
        Actor actor,
        Decision decision,
        Map<String, Object> extra,
        Integrity integrity) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Actor(String principalId, String role, String vendor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Decision(
            String capability,
            String result,
            String enforcementLayer,
            String matchedRule,
            String reason) {
        public static Decision of(CapabilityDecision d) {
            return new Decision(
                    d.capability() == null ? null : d.capability().wire(),
                    d.result() == null ? null : d.result().wire(),
                    d.enforcementLayer() == null ? null : d.enforcementLayer().name(),
                    d.matchedRule(), d.reason());
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Integrity(String prevEventHash, String eventHash, String signature) {
    }

    /** Copy with a different integrity block (used while computing the hash). */
    public GovernanceEvent withIntegrity(Integrity newIntegrity) {
        return new GovernanceEvent(eventId, seq, eventType, occurredAt, taskId, profile,
                policyHash, actor, decision, extra, newIntegrity);
    }
}
