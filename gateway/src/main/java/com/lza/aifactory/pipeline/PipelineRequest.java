package com.lza.aifactory.pipeline;

import java.nio.file.Path;
import java.util.Map;

/**
 * Everything an executor needs to run one task: its id, its working directory
 * (already containing issue.json), and the environment overrides derived from
 * the issue.
 */
public record PipelineRequest(String taskId, Path taskDir, Map<String, String> env) {
}
