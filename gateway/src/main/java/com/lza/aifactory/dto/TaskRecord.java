package com.lza.aifactory.dto;

import java.time.Duration;
import java.time.Instant;

public record TaskRecord(
        String taskId,
        String source,
        String externalId,
        String title,
        String repo,
        String targetBranch,
        TaskStatus status,
        String message,
        Instant submittedAt,
        Instant updatedAt,
        Instant completedAt,
        String workDir,
        String prUrl
) {
    public TaskRecord withStatus(TaskStatus newStatus, String newMessage) {
        return update(newStatus, newMessage, null, completedAt);
    }

    public TaskRecord withStatus(TaskStatus newStatus, String newMessage, String newPrUrl) {
        return update(newStatus, newMessage, newPrUrl, completedAt);
    }

    /** Full update including the completion timestamp (null until terminal). */
    public TaskRecord update(TaskStatus newStatus, String newMessage, String newPrUrl, Instant newCompletedAt) {
        return new TaskRecord(taskId, source, externalId, title, repo, targetBranch,
                newStatus, newMessage, submittedAt, Instant.now(), newCompletedAt, workDir,
                newPrUrl == null || newPrUrl.isBlank() ? prUrl : newPrUrl);
    }

    public boolean terminal() {
        return status == TaskStatus.COMPLETED || status == TaskStatus.FAILED
                || status == TaskStatus.CANCELLED;
    }

    /**
     * How long the task has taken: completion − submission for a finished task,
     * else {@code nowRef} − submission for one still running. A single shared
     * {@code nowRef} per render keeps every row consistent.
     */
    public Duration duration(Instant nowRef) {
        Instant end = completedAt != null ? completedAt : nowRef;
        if (submittedAt == null || end == null) return Duration.ZERO;
        Duration d = Duration.between(submittedAt, end);
        return d.isNegative() ? Duration.ZERO : d;
    }
}
