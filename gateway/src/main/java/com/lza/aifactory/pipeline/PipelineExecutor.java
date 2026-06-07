package com.lza.aifactory.pipeline;

import java.io.IOException;

/**
 * Runs a task's pipeline. Implementations decide how (local bash process,
 * Kubernetes Job, ...). TaskService depends only on this interface so the
 * execution backend can change without touching submission/status logic.
 */
public interface PipelineExecutor {
    void start(PipelineRequest request) throws IOException;

    /**
     * Stop a running task's process tree (best effort). Returns true if a live
     * process was found and signalled here, false otherwise (e.g. it was started
     * before a restart, so this process no longer owns it). Default: no-op.
     */
    default boolean abort(String taskId) {
        return false;
    }
}
