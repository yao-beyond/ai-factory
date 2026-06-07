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
import java.io.InputStream;
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
    private final ArchiveExtractor archiveExtractor;
    private final Path workDir;
    private final List<String> allowRepositories;
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();

    public TaskService(ObjectMapper objectMapper,
                       AiFactoryProperties properties,
                       PipelineExecutor pipelineExecutor,
                       EtaService etaService,
                       ArchiveExtractor archiveExtractor,
                       @Value("${ai-factory.work-dir}") String workDir,
                       @Value("${ai-factory.allow-repositories:}") String allowRepositoriesCsv) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.pipelineExecutor = pipelineExecutor;
        this.etaService = etaService;
        this.archiveExtractor = archiveExtractor;
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
        return submitInternal(dto, null);
    }

    /**
     * Import an existing project from an uploaded zip and improve it locally
     * (mode=import) — no git account/token, no remote.
     */
    public TaskRecord submitImportZip(IssueDto dto, InputStream zipStream) throws IOException {
        dto.setMode("import");
        return submitInternal(dto, taskDir -> {
            Path repo = taskDir.resolve("workspace").resolve("repo");
            archiveExtractor.extractZip(zipStream, repo);
        });
    }

    @FunctionalInterface
    interface SeedAction {
        void seed(Path taskDir) throws IOException;
    }

    private TaskRecord submitInternal(IssueDto dto, SeedAction seed) throws IOException {
        validateRepoAllowed(dto.getRepo());
        if ("import".equalsIgnoreCase(dto.getMode())
                && dto.getSourcePath() != null && !dto.getSourcePath().isBlank()) {
            dto.setSourcePath(validateImportSourcePath(dto.getSourcePath()));
        }

        String taskId = normalizeTaskId(
                dto.getExternalId() == null || dto.getExternalId().isBlank()
                        ? UUID.randomUUID().toString()
                        : dto.getExternalId());

        Path taskDir = workDir.resolve(taskId);
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("issue.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto));
        if (seed != null) {
            seed.seed(taskDir); // e.g. extract the uploaded zip into workspace/repo
        }
        writeStatus(taskDir, TaskStatus.SUBMITTED, "submitted");

        pipelineExecutor.start(new PipelineRequest(taskId, taskDir, buildEnv(dto)));
        etaService.markStart(taskId);
        appendEvent(taskDir, taskId, "submitted", TaskStatus.SUBMITTED, "submitted");

        log.info("Submitted task taskId={} source={} mode={} externalId={} repo={}",
                taskId, dto.getSource(), dto.getMode(), dto.getExternalId(), dto.getRepo());

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
        if (dto.getProjectType() != null) {
            env.put("PROJECT_TYPE", dto.getProjectType());
        }
        // New + import both run in local mode (no clone/push/PR/token). Import
        // works on a seed: an uploaded zip (already extracted into workspace/repo)
        // or a local folder (copied by the pipeline from SOURCE_PATH).
        String mode = dto.getMode();
        if ("new".equalsIgnoreCase(mode) || "import".equalsIgnoreCase(mode)) {
            env.put("PROJECT_MODE", "local");
        }
        if ("import".equalsIgnoreCase(mode)
                && dto.getSourcePath() != null && !dto.getSourcePath().isBlank()) {
            env.put("SOURCE_PATH", dto.getSourcePath());
        }
        return env;
    }

    /** Path to the downloadable result zip a local-mode task produces, if present. */
    public Optional<Path> resultZip(String taskId) {
        Path f = workDir.resolve(normalizeTaskId(taskId)).resolve("result.zip");
        return Files.exists(f) ? Optional.of(f) : Optional.empty();
    }

    /** The generated project's working tree (local mode), if present. */
    public Optional<Path> resultDir(String taskId) {
        Path d = workDir.resolve(normalizeTaskId(taskId)).resolve("workspace").resolve("repo");
        return Files.isDirectory(d) ? Optional.of(d.normalize()) : Optional.empty();
    }

    /** True when the generated project has an index.html that can be previewed in a browser. */
    public boolean hasPreview(String taskId) {
        if (!isNewProjectResult(taskId)) return false;
        return resultDir(taskId).map(d -> Files.isRegularFile(d.resolve("index.html"))).orElse(false);
    }

    /**
     * Authoritative check that this is a new-project (local) task. Read from the
     * issue.json written once at submit — NOT inferred from sibling artifacts like
     * result.zip, which live in the same writable dir and could be stale/forged.
     * Used to gate preview, download, and the completed-page UI so an existing-repo
     * clone is never exposed.
     */
    public boolean isNewProjectResult(String taskId) {
        Path issue = workDir.resolve(normalizeTaskId(taskId)).resolve("issue.json");
        if (!Files.exists(issue)) return false;
        try {
            IssueDto dto = objectMapper.readValue(Files.readString(issue), IssueDto.class);
            String mode = dto.getMode();
            return "new".equalsIgnoreCase(mode) || "import".equalsIgnoreCase(mode);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resolve a file inside the generated project for preview. Empty/blank path
     * defaults to index.html. Protects against both lexical traversal ("..") and
     * symlink escape by comparing the real (symlink-resolved) paths. Only serves
     * new-project (local) results — never an existing-repo clone.
     */
    public Optional<Path> resolvePreviewFile(String taskId, String relativePath) {
        if (!isNewProjectResult(taskId)) return Optional.empty();
        Path dir = resultDir(taskId).orElse(null);
        if (dir == null) return Optional.empty();
        String rel = (relativePath == null || relativePath.isBlank()) ? "index.html" : relativePath;
        try {
            Path realDir = dir.toRealPath();
            Path target = realDir.resolve(rel).normalize();
            if (!Files.isRegularFile(target)) return Optional.empty();
            // Follow symlinks: the actual file must still live inside the project dir.
            Path realTarget = target.toRealPath();
            if (!realTarget.startsWith(realDir)) return Optional.empty();
            return Optional.of(realTarget);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /** The plain-language change summary the pipeline writes when it finishes. */
    public Optional<String> readSummary(String taskId) {
        return readMarkdown(taskId, "summary.md");
    }

    /** The plain-language plan summary shown at the pre-flight confirmation gate. */
    public Optional<String> readPlanSummary(String taskId) {
        return readMarkdown(taskId, "plan_summary.md");
    }

    /**
     * The technical options proposed by the AI during the confirmation gate.
     * The file is AI-generated, so this is defensive: anything that isn't a JSON
     * array of objects is dropped (not thrown), and only object entries are kept
     * with string keys — so a malformed-but-valid options.json can never crash
     * the rendering code that iterates these as maps.
     */
    public List<Map<String, Object>> readOptions(String taskId) {
        Path f = workDir.resolve(normalizeTaskId(taskId)).resolve("options.json");
        if (!Files.exists(f)) return List.of();
        try {
            Object parsed = objectMapper.readValue(Files.readString(f), Object.class);
            if (!(parsed instanceof List<?> raw)) return List.of();
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : raw) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    m.forEach((k, v) -> entry.put(String.valueOf(k), v));
                    out.add(entry);
                }
            }
            return out;
        } catch (IOException e) {
            return List.of();
        }
    }

    private Optional<String> readMarkdown(String taskId, String name) {
        Path f = workDir.resolve(normalizeTaskId(taskId)).resolve(name);
        if (!Files.exists(f)) return Optional.empty();
        try {
            String s = Files.readString(f).strip();
            return s.isEmpty() ? Optional.empty() : Optional.of(s);
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    /**
     * The last lines of the task's run.log, for the live "what AI is doing now"
     * feed on the status page. ANSI colour codes are stripped. Returns an empty
     * list (never empty Optional) when the log isn't there yet, so the polling
     * frontend can treat "no task" (404 elsewhere) and "no output yet" uniformly.
     */
    public java.util.List<String> recentActivity(String taskId, int maxLines) {
        Path f = workDir.resolve(normalizeTaskId(taskId)).resolve("run.log");
        if (!Files.exists(f)) return java.util.List.of();
        try {
            java.util.List<String> all = Files.readAllLines(f);
            int from = Math.max(0, all.size() - Math.max(1, maxLines));
            java.util.List<String> out = new java.util.ArrayList<>();
            for (String raw : all.subList(from, all.size())) {
                String clean = redactSecrets(stripAnsi(raw)).stripTrailing();
                if (!clean.isBlank()) out.add(clean);
            }
            return out;
        } catch (IOException e) {
            return java.util.List.of();
        }
    }

    private static final java.util.regex.Pattern ANSI =
            java.util.regex.Pattern.compile("\\u001B\\[[0-9;?]*[ -/]*[@-~]");

    private static String stripAnsi(String s) {
        return ANSI.matcher(s).replaceAll("");
    }

    // --- Secret redaction for the live activity feed ----------------------------
    // The feed surfaces the tail of run.log, which can echo credentials a pipeline
    // command printed (a clone URL with a token, a key=value secret, etc.). These
    // patterns mask the secret value before it ever leaves the server. Defence in
    // depth: the log shown is a sanitised view, never the raw bytes.

    // scheme://user:secret@host  ->  scheme://<redacted>@host
    private static final java.util.regex.Pattern URL_CREDS =
            java.util.regex.Pattern.compile("([a-zA-Z][a-zA-Z0-9+.\\-]*://)[^/\\s:@]+:[^/\\s@]+@");
    // Known provider token shapes (GitHub, GitLab, Slack, AWS, OpenAI, Google).
    private static final java.util.regex.Pattern KNOWN_TOKEN =
            java.util.regex.Pattern.compile(
                    "(gh[posru]_[A-Za-z0-9]{16,}"
                    + "|glpat-[A-Za-z0-9_\\-]{16,}"
                    + "|xox[baprs]-[A-Za-z0-9\\-]{10,}"
                    + "|AKIA[0-9A-Z]{16}"
                    + "|sk-[A-Za-z0-9]{20,}"
                    + "|AIza[0-9A-Za-z_\\-]{20,})");
    // An identifier that *contains* a secret word, then = or : and a value, e.g.
    // AWS_SECRET_ACCESS_KEY=...  or  api-key: ... . Underscore/hyphen tolerant so
    // env-var-style names are caught even though \b wouldn't fire between words.
    private static final java.util.regex.Pattern ASSIGN_SECRET =
            java.util.regex.Pattern.compile(
                    "(?i)([A-Za-z0-9_\\-]*"
                    + "(?:secret|token|passwd|password|pwd|api[_-]?key|access[_-]?key|"
                    + "private[_-]?key|credential)"
                    + "[A-Za-z0-9_\\-]*)(\\s*[:=]\\s*)(?:Bearer\\s+|Basic\\s+)?\\S+");
    // An `authorization` / `auth` key (whole word, so `author`/`oauth_state` are
    // left alone) followed by : or = — mask the whole value.
    private static final java.util.regex.Pattern AUTH_HEADER =
            java.util.regex.Pattern.compile("(?i)\\b(authorization|auth)\\b(\\s*[:=]\\s*).+");
    // Bearer/Basic <token> anywhere; token long enough that it isn't prose.
    private static final java.util.regex.Pattern BEARER =
            java.util.regex.Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._+/=\\-]{8,}");
    // A bare 40-char AWS secret access key. Requires at least one + or / so a
    // 40-hex git SHA (which never contains + or /) is never matched.
    private static final java.util.regex.Pattern AWS_SECRET_BARE =
            java.util.regex.Pattern.compile(
                    "(?<![A-Za-z0-9+/])(?=[A-Za-z0-9+/]{40}(?![A-Za-z0-9+/]))"
                    + "[A-Za-z0-9+/]*[+/][A-Za-z0-9+/]*");
    // Host home directory: /Users/<name>/...  ->  /Users/<user>/... (hide the OS account)
    private static final java.util.regex.Pattern HOME_PATH =
            java.util.regex.Pattern.compile("(/Users/|/home/)[^/\\s]+");

    private static String redactSecrets(String line) {
        String s = URL_CREDS.matcher(line).replaceAll("$1<redacted>@");
        s = KNOWN_TOKEN.matcher(s).replaceAll("<redacted>");
        s = ASSIGN_SECRET.matcher(s).replaceAll("$1$2<redacted>");
        s = AUTH_HEADER.matcher(s).replaceAll("$1$2<redacted>");
        s = BEARER.matcher(s).replaceAll("$1 <redacted>");
        s = AWS_SECRET_BARE.matcher(s).replaceAll("<redacted>");
        s = HOME_PATH.matcher(s).replaceAll("$1<user>");
        return s;
    }

    /**
     * Write the approve/cancel marker the waiting pipeline polls for. Atomic
     * (temp + rename). The bash loop treats cancel as winning over approve.
     */
    public void writeConfirmMarker(String taskId, boolean approve, String option) throws IOException {
        Path dir = workDir.resolve(normalizeTaskId(taskId));
        if (approve && option != null && !option.isBlank()) {
            Path optTarget = dir.resolve("confirm.option");
            Files.writeString(optTarget, option.trim() + "\n");
        }
        String name = approve ? "confirm.approve" : "confirm.cancel";
        Path target = dir.resolve(name);
        Path tmp = dir.resolve(name + ".tmp");
        Files.writeString(tmp, Instant.now().toString() + "\n");
        try {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
    /**
     * Local-folder import is off unless security.importRootDir is configured, and
     * the path must resolve (real path, following symlinks) inside that root — so
     * an API caller can never copy arbitrary host directories into a result.
     * Returns the canonical path to use.
     */
    private String validateImportSourcePath(String sourcePath) {
        String root = properties.getSecurity().getImportRootDir();
        if (root == null || root.isBlank()) {
            throw new IllegalArgumentException(
                    "本機資料夾匯入未啟用（請設定 security.importRootDir，或改用上傳 zip）。");
        }
        try {
            Path base = Path.of(root).toRealPath();
            Path src = Path.of(sourcePath).toRealPath();
            if (!Files.isDirectory(src) || !src.startsWith(base)) {
                throw new IllegalArgumentException("sourcePath 必須是 " + root + " 之內的資料夾。");
            }
            return src.toString();
        } catch (IOException e) {
            throw new IllegalArgumentException("找不到指定的資料夾：" + sourcePath);
        }
    }

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
