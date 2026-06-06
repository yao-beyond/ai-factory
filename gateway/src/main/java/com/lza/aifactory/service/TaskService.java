package com.lza.aifactory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lza.aifactory.config.AiFactoryProperties;
import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import com.lza.aifactory.pipeline.PipelineExecutor;
import com.lza.aifactory.pipeline.PipelineRequest;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ObjectMapper objectMapper;
    private final AiFactoryProperties properties;
    private final PipelineExecutor pipelineExecutor;
    private final EtaService etaService;
    private final Path workDir;
    private final List<String> allowRepositories;
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    public TaskService(ObjectMapper objectMapper,
                       AiFactoryProperties properties,
                       PipelineExecutor pipelineExecutor,
                       EtaService etaService,
                       @Value("${ai-factory.work-dir}") String workDir,
                       @Value("${ai-factory.allow-repositories:}") String allowRepositoriesCsv) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.pipelineExecutor = pipelineExecutor;
        this.etaService = etaService;
        this.workDir = Path.of(workDir);
        // Allowlist = typed config (ai-factory.yml security.allowRepositories)
        // plus the legacy CSV property, for backward compatibility.
        this.allowRepositories = new ArrayList<>(properties.getSecurity().getAllowRepositories());
        this.allowRepositories.addAll(parseCsv(allowRepositoriesCsv));
        if (this.allowRepositories.isEmpty()) {
            log.warn("No repository allowlist configured — any repository will be accepted. "
                    + "Set security.allowRepositories in ai-factory.yml (or AI_FACTORY_ALLOW_REPOSITORIES).");
        }
    }

    /** Rebuild the in-memory task list from disk so it survives restarts. */
    @PostConstruct
    void restoreFromDisk() {
        if (!Files.isDirectory(workDir)) return;
        try (Stream<Path> dirs = Files.list(workDir)) {
            dirs.filter(Files::isDirectory).forEach(this::restoreOne);
            if (!tasks.isEmpty()) {
                log.info("Restored {} task(s) from {}", tasks.size(), workDir);
            }
        } catch (IOException e) {
            log.warn("Could not scan work dir {} on startup: {}", workDir, e.getMessage());
        }
    }

    private void restoreOne(Path taskDir) {
        Path issueFile = taskDir.resolve("issue.json");
        if (!Files.exists(issueFile)) return;
        String taskId = taskDir.getFileName().toString();
        try {
            IssueDto dto = objectMapper.readValue(Files.readString(issueFile), IssueDto.class);
            TaskRecord record = new TaskRecord(
                    taskId, dto.getSource(), dto.getExternalId(), dto.getTitle(),
                    dto.getRepo(), dto.getTargetBranch(),
                    TaskStatus.SUBMITTED, "restored", Instant.now(), Instant.now(),
                    taskDir.toString(), null);
            tasks.put(taskId, refresh(record));
        } catch (IOException e) {
            log.warn("Skipping unreadable task dir {}: {}", taskId, e.getMessage());
        }
    }

    public TaskRecord submit(IssueDto dto) throws IOException {
        validateRepoAllowed(dto.getRepo());

        String taskId = normalizeTaskId(
                dto.getExternalId() == null || dto.getExternalId().isBlank()
                        ? UUID.randomUUID().toString()
                        : dto.getExternalId());

        Path taskDir = workDir.resolve(taskId);
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("issue.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto));
        writeStatus(taskDir, TaskStatus.SUBMITTED, "submitted");

        pipelineExecutor.start(new PipelineRequest(taskId, taskDir, buildEnv(dto)));
        etaService.markStart(taskId);
        appendEvent(taskDir, taskId, "submitted", TaskStatus.SUBMITTED, "submitted");

        log.info("Submitted task taskId={} source={} externalId={} repo={}",
                taskId, dto.getSource(), dto.getExternalId(), dto.getRepo());

        TaskRecord record = new TaskRecord(
                taskId, dto.getSource(), dto.getExternalId(), dto.getTitle(),
                dto.getRepo(), dto.getTargetBranch(),
                TaskStatus.SUBMITTED, "submitted",
                Instant.now(), Instant.now(), taskDir.toString(), null);
        tasks.put(taskId, record);
        return record;
    }

    private Map<String, String> buildEnv(IssueDto dto) {
        Map<String, String> env = new LinkedHashMap<>();
        if (dto.getRepo() != null && isUrlLike(dto.getRepo())) {
            env.put("REPO_URL", dto.getRepo());
        }
        if (dto.getTargetBranch() != null && !dto.getTargetBranch().isBlank()) {
            env.put("TARGET_BRANCH", dto.getTargetBranch());
        }
        if (dto.getMaxAgents() != null) {
            env.put("MAX_AGENTS", String.valueOf(dto.getMaxAgents()));
        }
        if (dto.getPriority() != null) {
            env.put("ISSUE_PRIORITY", dto.getPriority());
        }
        return env;
    }

    /** The plain-language change summary the pipeline writes when it finishes. */
    public Optional<String> readSummary(String taskId) {
        Path f = workDir.resolve(normalizeTaskId(taskId)).resolve("summary.md");
        if (!Files.exists(f)) return Optional.empty();
        try {
            String s = Files.readString(f).strip();
            return s.isEmpty() ? Optional.empty() : Optional.of(s);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    public Optional<TaskRecord> findStatus(String taskId) {
        TaskRecord record = tasks.get(normalizeTaskId(taskId));
        if (record == null) return Optional.empty();
        return Optional.of(refresh(record));
    }

    public List<TaskRecord> listTasks() {
        List<TaskRecord> snapshot = new ArrayList<>();
        for (TaskRecord record : tasks.values()) {
            snapshot.add(refresh(record));
        }
        return snapshot;
    }

    private TaskRecord refresh(TaskRecord record) {
        Path statusFile = workDir.resolve(record.taskId()).resolve("status.txt");
        if (!Files.exists(statusFile)) return record;
        try {
            Map<String, String> kv = parseStatus(Files.readString(statusFile));
            TaskStatus parsed;
            try {
                parsed = TaskStatus.valueOf(kv.getOrDefault("STATUS", record.status().name()).trim());
            } catch (IllegalArgumentException e) {
                parsed = record.status();
            }
            String message = kv.getOrDefault("MESSAGE", record.message());
            String prUrl = kv.get("PR_URL");
            // Feed the ETA estimator the moment a task first reaches a terminal state.
            if (record.status() != parsed) {
                if (parsed == TaskStatus.COMPLETED) etaService.onCompleted(record.taskId());
                else if (parsed == TaskStatus.FAILED) etaService.forget(record.taskId());
            }
            TaskRecord updated = record.withStatus(parsed, message, prUrl);
            tasks.put(record.taskId(), updated);
            return updated;
        } catch (IOException e) {
            log.warn("Failed to refresh status for {}: {}", record.taskId(), e.getMessage());
            return record;
        }
    }

    private void writeStatus(Path taskDir, TaskStatus status, String message) throws IOException {
        String body = "STATUS=" + status.name() + "\n"
                + "MESSAGE=" + (message == null ? "" : message) + "\n"
                + "UPDATED_AT=" + Instant.now() + "\n";
        // Temp file + atomic rename so a concurrent reader never sees a partial file.
        Path target = taskDir.resolve("status.txt");
        Path tmp = taskDir.resolve("status.txt.tmp");
        Files.writeString(tmp, body);
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Append one JSON line to the task's event log (lightweight audit trail). */
    private void appendEvent(Path taskDir, String taskId, String type, TaskStatus status, String message) {
        try {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("timestamp", Instant.now().toString());
            event.put("taskId", taskId);
            event.put("type", type);
            event.put("status", status.name());
            event.put("message", message);
            Files.writeString(taskDir.resolve("events.log"),
                    objectMapper.writeValueAsString(event) + "\n",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.warn("Failed to append event for {}: {}", taskId, e.getMessage());
        }
    }

    private Map<String, String> parseStatus(String content) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String line : content.split("\\R")) {
            int idx = line.indexOf('=');
            if (idx > 0) map.put(line.substring(0, idx).trim(), line.substring(idx + 1).trim());
        }
        return map;
    }

    private boolean isUrlLike(String value) {
        return value.matches("^(https?|git|ssh)://.+") || value.startsWith("git@");
    }

    /**
     * Rejects a submission whose repository is not in the configured allowlist.
     * Short repo names (resolved server-side from config) and an empty allowlist
     * are not blocked here.
     */
    private void validateRepoAllowed(String repo) {
        if (repo == null || repo.isBlank() || !isUrlLike(repo)) return;
        if (allowRepositories.isEmpty()) return;
        for (String pattern : allowRepositories) {
            if (globMatch(pattern, repo)) return;
        }
        throw new IllegalArgumentException(
                "Repository '" + repo + "' is not in the allowed list. "
                        + "Ask an administrator to add it to ai-factory allowRepositories.");
    }

    private boolean globMatch(String glob, String value) {
        String regex = "^" + java.util.regex.Pattern.quote(glob).replace("*", "\\E.*\\Q") + "$";
        return value.matches(regex);
    }

    private static List<String> parseCsv(String value) {
        if (value == null || value.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String part : value.split(",")) {
            if (!part.isBlank()) out.add(part.trim());
        }
        return out;
    }

    private String normalizeTaskId(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
