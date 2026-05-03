package com.lza.aifactory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
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

@Service
public class TaskService {
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ObjectMapper objectMapper;
    private final Path workDir;
    private final String pipelineScript;
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    public TaskService(ObjectMapper objectMapper,
                       @Value("${ai-factory.work-dir}") String workDir,
                       @Value("${ai-factory.pipeline-script}") String pipelineScript) {
        this.objectMapper = objectMapper;
        this.workDir = Path.of(workDir);
        this.pipelineScript = pipelineScript;
    }

    public TaskRecord submit(IssueDto dto) throws IOException {
        String taskId = normalizeTaskId(
                dto.getExternalId() == null || dto.getExternalId().isBlank()
                        ? UUID.randomUUID().toString()
                        : dto.getExternalId());

        Path taskDir = workDir.resolve(taskId);
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("issue.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto));
        writeStatus(taskDir, TaskStatus.SUBMITTED, "submitted");

        ProcessBuilder pb = new ProcessBuilder("/bin/bash", pipelineScript, taskId)
                .redirectOutput(taskDir.resolve("run.log").toFile())
                .redirectErrorStream(true);

        Map<String, String> env = pb.environment();
        env.put("TASK_ID", taskId);
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

        pb.start();
        log.info("Submitted task taskId={} source={} externalId={} repo={}",
                taskId, dto.getSource(), dto.getExternalId(), dto.getRepo());

        TaskRecord record = new TaskRecord(
                taskId, dto.getSource(), dto.getExternalId(), dto.getTitle(),
                dto.getRepo(), dto.getTargetBranch(),
                TaskStatus.SUBMITTED, "submitted",
                Instant.now(), Instant.now(), taskDir.toString());
        tasks.put(taskId, record);
        return record;
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
            TaskRecord updated = record.withStatus(parsed, message);
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
        Files.writeString(taskDir.resolve("status.txt"), body);
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

    private String normalizeTaskId(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "-");
    }
}
