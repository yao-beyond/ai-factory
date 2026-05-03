package com.lza.aifactory.dto;

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
        String workDir
) {
    public TaskRecord withStatus(TaskStatus newStatus, String newMessage) {
        return new TaskRecord(taskId, source, externalId, title, repo, targetBranch,
                newStatus, newMessage, submittedAt, Instant.now(), workDir);
    }
}
