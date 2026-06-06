package com.lza.aifactory.pipeline;

import java.io.IOException;

/**
 * Runs a task's pipeline. Implementations decide how (local bash process,
 * Kubernetes Job, ...). TaskService depends only on this interface so the
 * execution backend can change without touching submission/status logic.
 */
public interface PipelineExecutor {
    void start(PipelineRequest request) throws IOException;
}
