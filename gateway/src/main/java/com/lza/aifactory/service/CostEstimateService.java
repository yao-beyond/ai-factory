package com.lza.aifactory.service;

import org.springframework.stereotype.Service;

/**
 * Estimates how many AI tokens the build will consume, shown at the pre-flight
 * confirmation gate so the user knows the "price of admission" before approving.
 *
 * <p>Deliberately a wide, honest range (same philosophy as {@link EtaService}):
 * we have no token telemetry from the AI CLIs, so this is a heuristic seeded
 * from typical agent-session sizes — each dev agent re-reads the plan several
 * times and produces a candidate, plus a fixed overhead for select/review/fix.
 * The UI presents it as a rough estimate, never a quote.
 */
@Service
public class CostEstimateService {

    /** Inclusive token range; {@code low <= high} always holds. */
    public record TokenRange(long lowTokens, long highTokens) {
        /** Lower bound in 萬 (10k) units, floored but never below 1. */
        public long lowWan() {
            return Math.max(1, lowTokens / 10_000);
        }

        /** Upper bound in 萬 (10k) units, ceiled so the range never understates. */
        public long highWan() {
            return Math.max(lowWan(), (highTokens + 9_999) / 10_000);
        }
    }

    // Per-dev-agent session, independent of plan size (tool calls, code, tests).
    private static final long AGENT_BASE_LOW = 60_000;
    private static final long AGENT_BASE_HIGH = 200_000;
    // How many times an agent effectively re-reads the plan across its session.
    private static final long PLAN_READS_LOW = 4;
    private static final long PLAN_READS_HIGH = 12;
    // Select + security review + fix, shared across the task.
    private static final long OVERHEAD_LOW = 50_000;
    private static final long OVERHEAD_HIGH = 150_000;

    /**
     * @param maxAgents requested parallel dev agents (clamped to 1–10, matching
     *                  the pipeline's own clamp in run-task.sh)
     * @param planChars length of the plan text shown at the gate; zh-TW text is
     *                  roughly one token per 2 chars on average
     */
    public TokenRange estimate(int maxAgents, int planChars) {
        long agents = Math.min(10, Math.max(1, maxAgents));
        long planTokens = Math.max(0, planChars) / 2;
        long low = agents * (AGENT_BASE_LOW + PLAN_READS_LOW * planTokens) + OVERHEAD_LOW;
        long high = agents * (AGENT_BASE_HIGH + PLAN_READS_HIGH * planTokens) + OVERHEAD_HIGH;
        return new TokenRange(low, high);
    }
}
