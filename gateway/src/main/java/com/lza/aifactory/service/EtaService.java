package com.lza.aifactory.service;

import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Estimates how much longer a task will take. The estimate is deliberately
 * simple and honest: {@code remaining ≈ averageTotalDuration × (1 - progress)}.
 *
 * <p>The average total duration starts from a sensible seed and self-calibrates
 * as real tasks finish, so it gets more accurate over time. It only learns from
 * tasks that ran start-to-finish in this process (restored tasks are skipped, so
 * their inaccurate timestamps never pollute the average).
 */
@Service
public class EtaService {
    /** Seed used until at least one task has completed (8 minutes). */
    private static final long SEED_MILLIS = Duration.ofMinutes(8).toMillis();
    /** Clamp so a single weird run can't produce absurd estimates. */
    private static final long MIN_TOTAL_MILLIS = Duration.ofSeconds(30).toMillis();
    private static final long MAX_TOTAL_MILLIS = Duration.ofHours(2).toMillis();

    private final ConcurrentHashMap<String, Instant> starts = new ConcurrentHashMap<>();
    private final AtomicLong sampleCount = new AtomicLong(0);
    private final AtomicLong totalMillis = new AtomicLong(0);

    /** Record when a task began (called at submit time). */
    public void markStart(String taskId) {
        starts.put(taskId, Instant.now());
    }

    /** Fold a finished task's real duration into the rolling average (idempotent). */
    public void onCompleted(String taskId) {
        Instant start = starts.remove(taskId);
        if (start == null) return; // not started in this process (e.g. restored) — skip
        long dur = Duration.between(start, Instant.now()).toMillis();
        if (dur < MIN_TOTAL_MILLIS || dur > MAX_TOTAL_MILLIS) return; // ignore outliers
        sampleCount.incrementAndGet();
        totalMillis.addAndGet(dur);
    }

    /** Drop tracking for a task that ended without completing. */
    public void forget(String taskId) {
        starts.remove(taskId);
    }

    private long averageTotalMillis() {
        long n = sampleCount.get();
        return n == 0 ? SEED_MILLIS : totalMillis.get() / n;
    }

    /**
     * Estimated time remaining for an in-progress task, or empty for terminal
     * states (completed/failed) where an ETA is meaningless.
     */
    public Optional<Duration> estimateRemaining(TaskRecord record) {
        TaskStatus s = record.status();
        // No ETA for terminal states (incl. CANCELLED), while waiting on a human
        // decision, or while paused — "remaining" is meaningless in all of these.
        if (record.terminal()
                || s == TaskStatus.AWAITING_CONFIRMATION
                || s == TaskStatus.AWAITING_DELIVERY_APPROVAL
                || s == TaskStatus.PAUSED) return Optional.empty();
        int progress = Math.max(0, Math.min(100, s.progress()));
        long remaining = averageTotalMillis() * (100 - progress) / 100;
        return Optional.of(Duration.ofMillis(remaining));
    }
}
