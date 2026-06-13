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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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
    private final String serverPort;
    private final com.lza.aifactory.governance.GovernanceTokens governanceTokens;
    // Live pipeline processes, so a task can be aborted after it starts.
    private final Map<String, Process> running = new ConcurrentHashMap<>();
    // Tasks the user aborted: makes reconcile finalize to CANCELLED, not FAILED.
    private final Set<String> abortRequested = ConcurrentHashMap.newKeySet();

    public BashPipelineExecutor(@Value("${ai-factory.pipeline-script}") String pipelineScript,
                                @Value("${server.port:8080}") String serverPort,
                                com.lza.aifactory.governance.GovernanceTokens governanceTokens) {
        this.pipelineScript = pipelineScript;
        this.serverPort = serverPort;
        this.governanceTokens = governanceTokens;
    }

    @Override
    public void start(PipelineRequest request) throws IOException {
        Path taskDir = request.taskDir();
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", pipelineScript, request.taskId())
                .redirectOutput(taskDir.resolve("run.log").toFile())
                .redirectErrorStream(true);

        applyEnv(pb.environment(), request.taskId(), request.env());
        // Tell the pipeline where to call back for the governance promote-check.
        // Setting this is what opts a deployment into gateway-enforced gating; if a
        // request already supplied it, the request value wins.
        pb.environment().putIfAbsent("AIF_GATEWAY_URL", "http://127.0.0.1:" + serverPort);
        // Per-task promote-check token: lets the pipeline authenticate the gate call
        // WITHOUT the operator secret. applyEnv has already stripped
        // AIF_INTERNAL_SECRET from the child env, so untrusted workspace code (AI
        // agents, project tests) never inherits the operator credential — only this
        // scoped token, which authorises nothing but this task's gate evaluation.
        pb.environment().put("AIF_PROMOTE_TOKEN",
                governanceTokens.issue(request.taskId(), "promote-check"));
        // Rotate the gateway-owned run nonce so any approval from a previous run of
        // this task id no longer validates (stale-approval defence). Kept in gateway
        // memory — untrusted workspace code can't reach it.
        governanceTokens.rotateRunNonce(request.taskId());

        Process process = pb.start();
        running.put(request.taskId(), process);
        log.info("Started bash pipeline for taskId={} script={}", request.taskId(), pipelineScript);

        // Safety net: if the process dies non-zero without leaving a terminal
        // status (crash, OOM, SIGKILL — cases the bash ERR trap can miss), the
        // task would otherwise hang forever as "in progress". Reconcile to FAILED
        // (or CANCELLED if the user aborted it).
        process.onExit().thenAccept(p -> {
            running.remove(request.taskId());
            reconcile(request.taskId(), taskDir, p.exitValue());
        });
    }

    /**
     * Abort a running task: kill its whole process tree (the bash leader plus the
     * claude/codex/git children it spawned). macOS has no {@code setsid}, so we
     * walk {@link Process#descendants()} — children first, then the leader — and
     * escalate from destroy() to destroyForcibly() after a short grace period.
     */
    @Override
    public boolean abort(String taskId) {
        abortRequested.add(taskId);   // so reconcile writes CANCELLED, not FAILED
        Process p = running.get(taskId);
        if (p == null) {
            return false;             // not owned here (e.g. started before a restart)
        }
        List<ProcessHandle> kids = p.descendants().toList();
        kids.forEach(ProcessHandle::destroy);
        p.destroy();
        try {
            if (!p.waitFor(5, TimeUnit.SECONDS)) {
                kids.forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        log.info("Aborted pipeline for taskId={} (killed {} descendant process(es))", taskId, kids.size());
        return true;
    }

    /**
     * Build the child process environment. Per-task pipeline vars that must come
     * ONLY from the (validated) request are stripped from the inherited env first,
     * so an inherited SOURCE_PATH/PROJECT_MODE can never bypass gateway validation.
     */
    public static void applyEnv(Map<String, String> env, String taskId, Map<String, String> requestEnv) {
        env.remove("SOURCE_PATH");
        env.remove("PROJECT_MODE");
        // The operator approval secret must NEVER reach the pipeline (it would be
        // inherited by untrusted AI agents / project tests, which could then
        // self-approve). The pipeline authenticates promote-check with a scoped
        // AIF_PROMOTE_TOKEN instead, set by start() after this. Strip any inherited
        // promote token too, so only the executor's freshly-issued one is present.
        env.remove("AIF_INTERNAL_SECRET");
        env.remove("AIF_PROMOTE_TOKEN");
        env.put("TASK_ID", taskId);
        env.putAll(requestEnv);
    }

    private void reconcile(String taskId, Path taskDir, int exitCode) {
        boolean aborted = abortRequested.remove(taskId);
        // A clean (zero) exit means run-task.sh ran to the end and already wrote a
        // terminal status; nothing to do — unless the user aborted it.
        if (exitCode == 0 && !aborted) return;
        Path statusFile = taskDir.resolve("status.txt");
        try {
            String status = readStatus(statusFile);
            if ("COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status)) return;
            // User abort wins: it's a decision, not a failure.
            String terminal = aborted ? "CANCELLED" : "FAILED";
            String message = aborted ? "cancelled_by_user" : ("process_exited_rc:" + exitCode);
            String body = "STATUS=" + terminal + "\n"
                    + "MESSAGE=" + message + "\n"
                    + "UPDATED_AT=" + Instant.now() + "\n";
            Path tmp = taskDir.resolve("status.txt.tmp");
            Files.writeString(tmp, body);
            Files.move(tmp, statusFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            log.warn("Reconciled taskId={} to {} (process exited rc={})", taskId, terminal, exitCode);
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
