package com.lza.aifactory.pipeline;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
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
        Path taskDir = request.taskDir();
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", pipelineScript, request.taskId())
                .redirectOutput(taskDir.resolve("run.log").toFile())
                .redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("TASK_ID", request.taskId());
        env.putAll(request.env());

        Process process = pb.start();
        log.info("Started bash pipeline for taskId={} script={}", request.taskId(), pipelineScript);

        // Safety net: if the process dies non-zero without leaving a terminal
        // status (crash, OOM, SIGKILL — cases the bash ERR trap can miss), the
        // task would otherwise hang forever as "in progress". Reconcile to FAILED.
        process.onExit().thenAccept(p -> reconcile(request.taskId(), taskDir, p.exitValue()));
    }

    private void reconcile(String taskId, Path taskDir, int exitCode) {
        // A clean (zero) exit means run-task.sh ran to the end and already wrote a
        // terminal status; nothing to do.
        if (exitCode == 0) return;
        Path statusFile = taskDir.resolve("status.txt");
        try {
            String status = readStatus(statusFile);
            if ("COMPLETED".equals(status) || "FAILED".equals(status)) return;
            String body = "STATUS=FAILED\n"
                    + "MESSAGE=process_exited_rc:" + exitCode + "\n"
                    + "UPDATED_AT=" + Instant.now() + "\n";
            Path tmp = taskDir.resolve("status.txt.tmp");
            Files.writeString(tmp, body);
            Files.move(tmp, statusFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Reconciled taskId={} to FAILED (process exited rc={} without a terminal status)",
                    taskId, exitCode);
        } catch (IOException e) {
            log.warn("Could not reconcile status for taskId={}: {}", taskId, e.getMessage());
        }
    }

    private String readStatus(Path statusFile) throws IOException {
        if (!Files.exists(statusFile)) return "";
        for (String line : Files.readString(statusFile).split("\\R")) {
            if (line.startsWith("STATUS=")) return line.substring("STATUS=".length()).trim();
        }
        return "";
    }
}
