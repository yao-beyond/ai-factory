package com.lza.aifactory.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;

/**
 * Default executor: runs the bash pipeline (run-task.sh) as a local process,
 * streaming output to {@code <taskDir>/run.log}. Selected when workspace.mode is
 * "local" (the default).
 */
@Component
@ConditionalOnProperty(name = "workspace.mode", havingValue = "local", matchIfMissing = true)
public class BashPipelineExecutor implements PipelineExecutor {
    private static final Logger log = LoggerFactory.getLogger(BashPipelineExecutor.class);

    private final String pipelineScript;

    public BashPipelineExecutor(@Value("${ai-factory.pipeline-script}") String pipelineScript) {
        this.pipelineScript = pipelineScript;
    }

    @Override
    public void start(PipelineRequest request) throws IOException {
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", pipelineScript, request.taskId())
                .redirectOutput(request.taskDir().resolve("run.log").toFile())
                .redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("TASK_ID", request.taskId());
        env.putAll(request.env());

        pb.start();
        log.info("Started bash pipeline for taskId={} script={}", request.taskId(), pipelineScript);
    }
}
