package com.lza.aifactory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lza.aifactory.config.AiFactoryProperties;
import com.lza.aifactory.discovery.Card;
import com.lza.aifactory.discovery.DiscoveryCardLibrary;
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
    private final DiscoveryCardLibrary discoveryCardLibrary;
    private final com.lza.aifactory.governance.GovernanceProfileLibrary governanceProfileLibrary;
    private final Path workDir;
    private final List<String> allowRepositories;
    private final Map<String, TaskRecord> tasks = new ConcurrentHashMap<>();
    // Tasks the user deleted: tombstoned so a concurrent refresh()/restore can't
    // resurrect them after the in-memory entry and on-disk dir are gone.
    private final java.util.Set<String> deletedTaskIds = ConcurrentHashMap.newKeySet();

    public TaskService(ObjectMapper objectMapper,
                       AiFactoryProperties properties,
                       PipelineExecutor pipelineExecutor,
                       EtaService etaService,
                       ArchiveExtractor archiveExtractor,
                       DiscoveryCardLibrary discoveryCardLibrary,
                       com.lza.aifactory.governance.GovernanceProfileLibrary governanceProfileLibrary,
                       @Value("${ai-factory.work-dir}") String workDir,
                       @Value("${ai-factory.allow-repositories:}") String allowRepositoriesCsv) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.pipelineExecutor = pipelineExecutor;
        this.etaService = etaService;
        this.archiveExtractor = archiveExtractor;
        this.discoveryCardLibrary = discoveryCardLibrary;
        this.governanceProfileLibrary = governanceProfileLibrary;
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
            // Restore the real submit time (don't use now() — it would corrupt
            // sort order and elapsed-time). Prefer task-meta.json, else the
            // issue.json mtime, else now() as a last resort.
            Map<String, Instant> meta = readTaskMeta(taskDir);
            Instant submittedAt = meta.get("submittedAt");
            if (submittedAt == null) {
                try {
                    submittedAt = Files.getLastModifiedTime(issueFile).toInstant();
                } catch (IOException ignored) {
                    submittedAt = Instant.now();
                }
            }
            Instant completedAt = meta.get("completedAt");
            TaskRecord record = new TaskRecord(
                    taskId, dto.getSource(), dto.getExternalId(), dto.getTitle(),
                    dto.getRepo(), dto.getTargetBranch(),
                    TaskStatus.SUBMITTED, "restored", submittedAt, submittedAt, completedAt,
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

        // Reusing an id that was previously deleted (e.g. a re-submitted external
        // id): clear the tombstone so the fresh task is visible again. The
        // tombstone only exists to block a concurrent resurrect during deletion.
        deletedTaskIds.remove(taskId);

        // Backend defence: sanitise + length-cap the free-text fields that flow
        // into the AI prompt, so an oversized or control-char-laden value can't
        // slip in (the browser's limits can be bypassed via the JSON API/webhooks).
        dto.setTitle(sanitizeText(dto.getTitle(), 2048));
        dto.setDescription(sanitizeText(dto.getDescription(), 32768));
        // @NotBlank ran before sanitisation; a control-char-only value would have
        // passed it yet be empty now. Reject rather than store a blank field
        // (GlobalExceptionHandler maps IllegalArgumentException to HTTP 400).
        if (dto.getTitle().isBlank() || dto.getDescription().isBlank()) {
            throw new IllegalArgumentException("標題與描述需包含可閱讀的文字");
        }

        // Discovery-originated tasks: re-derive the authoritative capability boundary
        // from the chosen card on the SERVER, never trusting any client-supplied
        // boundary. This carries the structured constraints into the pipeline
        // (issue.json + env), so "the card library is the boundary" is enforced as
        // data, not just prose in the description. An unknown/forged card id clears
        // the boundary (the request degrades to a plain typed one).
        resolveDiscoveryBoundary(dto);

        // Resolve the governance profile against the library (unknown id -> 400, so
        // a forged id can't smuggle an ungoverned task). Default keeps existing
        // behaviour. The chosen id is persisted via issue.json (dto serialised below).
        resolveGovernanceProfile(dto);

        Path taskDir = workDir.resolve(taskId);
        Files.createDirectories(taskDir);
        Files.writeString(taskDir.resolve("issue.json"),
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(dto));
        if (seed != null) {
            seed.seed(taskDir); // e.g. extract the uploaded zip into workspace/repo
        }
        writeStatus(taskDir, TaskStatus.SUBMITTED, "submitted");
        Instant submittedAt = Instant.now();
        writeTaskMeta(taskDir, submittedAt, null);   // durable submit time, survives restart

        pipelineExecutor.start(new PipelineRequest(taskId, taskDir, buildEnv(dto)));
        etaService.markStart(taskId);
        appendEvent(taskDir, taskId, "submitted", TaskStatus.SUBMITTED, "submitted");

        log.info("Submitted task taskId={} source={} mode={} externalId={} repo={}",
                taskId, dto.getSource(), dto.getMode(), dto.getExternalId(), dto.getRepo());

        TaskRecord record = new TaskRecord(
                taskId, dto.getSource(), dto.getExternalId(), dto.getTitle(),
                dto.getRepo(), dto.getTargetBranch(),
                TaskStatus.SUBMITTED, "submitted",
                submittedAt, submittedAt, null, taskDir.toString(), null);
        tasks.put(taskId, record);
        return record;
    }

    /**
     * Start a follow-up task seeded from a finished local task's delivered
     * result, so the user can iterate ("根據成果繼續修改") without re-uploading.
     * Only completed new-project/import results may be continued — an
     * existing-repo clone must never be copied into a local deliverable.
     */
    public TaskRecord submitContinuation(String prevTaskId, IssueDto dto) throws IOException {
        String prev = normalizeTaskId(prevTaskId);
        TaskRecord r = findStatus(prev).orElse(null);
        if (r == null) {
            throw new IllegalArgumentException("找不到要接續的任務");
        }
        if (!isNewProjectResult(prev) || r.status() != TaskStatus.COMPLETED) {
            throw new IllegalArgumentException("只能接續「已完成」的本機專案成果");
        }
        Path src = resultDir(prev)
                .orElseThrow(() -> new IllegalArgumentException("找不到上次的成果檔案（可能已被清理）"));
        // The result root itself must be a real directory at its canonical
        // location — not a symlink a hostile/compromised task swapped in to make
        // the copy follow it to another task's files or outside the work dir.
        Path expected = workDir.resolve(prev).resolve("workspace").resolve("repo").normalize();
        try {
            if (Files.isSymbolicLink(expected) || !src.toRealPath().equals(expected.toRealPath())) {
                throw new IllegalArgumentException("成果路徑異常，無法接續");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("找不到上次的成果檔案（可能已被清理）");
        }
        dto.setMode("import");
        // A continuation never carries git/transport context from the caller.
        dto.setRepo(null);
        dto.setSourcePath(null);
        return submitInternal(dto,
                taskDir -> copyProjectTree(src, taskDir.resolve("workspace").resolve("repo")));
    }

    /**
     * Copy the previous result into the new seed, excluding pipeline metadata
     * (.git, .omc, docs/ai — a stale plan must not steer the new run) and
     * skipping symlinks (a link could smuggle content from outside the result).
     */
    private static void copyProjectTree(Path src, Path dst) throws IOException {
        Path realSrc = src.toRealPath();
        Files.walkFileTree(realSrc, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                    Path dir, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                Path rel = realSrc.relativize(dir);
                String name = rel.getFileName() == null ? "" : rel.getFileName().toString();
                if (name.equals(".git") || name.equals(".omc")
                        || rel.toString().equals("docs/ai") || rel.toString().startsWith("docs/ai/")) {
                    return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(dst.resolve(rel.toString()));
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFile(
                    Path file, java.nio.file.attribute.BasicFileAttributes a) throws IOException {
                if (Files.isSymbolicLink(file)) return java.nio.file.FileVisitResult.CONTINUE;
                Path rel = realSrc.relativize(file);
                Files.copy(file, dst.resolve(rel.toString()),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * If the request came from discovery, overwrite its capabilityBoundary with the
     * authoritative one re-derived from the card library by id. A client-supplied
     * boundary is never trusted; an unknown/disabled card id clears both fields.
     */
    /**
     * Validate the requested governance profile against the library. A blank id
     * defaults to {@code standard-app}; an unknown/disabled id is rejected (400)
     * so a client can never run a task under a profile the library doesn't vouch
     * for. The resolved id is normalised back onto the dto (persisted in issue.json).
     */
    private void resolveGovernanceProfile(IssueDto dto) {
        String id = dto.getGovernanceProfileId();
        if (id == null || id.isBlank()) {
            id = "standard-app";
        }
        if (governanceProfileLibrary.enabledById(id).isEmpty()) {
            throw new IllegalArgumentException("未知的治理 profile：" + id);
        }
        dto.setGovernanceProfileId(id);
    }

    /** The task's governance profile id (from issue.json; default standard-app). */
    public String readGovernanceProfileId(String taskId) {
        Path issue = workDir.resolve(normalizeTaskId(taskId)).resolve("issue.json");
        if (!Files.exists(issue)) return "standard-app";
        try {
            IssueDto dto = objectMapper.readValue(Files.readString(issue), IssueDto.class);
            String id = dto.getGovernanceProfileId();
            return (id == null || id.isBlank()) ? "standard-app" : id;
        } catch (IOException e) {
            return "standard-app";
        }
    }

    private void resolveDiscoveryBoundary(IssueDto dto) {
        String cardId = dto.getDiscoveryCardId();
        if (cardId == null || cardId.isBlank()) {
            dto.setCapabilityBoundary(null);
            return;
        }
        Card card = discoveryCardLibrary.enabledById(cardId).orElse(null);
        if (card == null) {
            dto.setDiscoveryCardId(null);
            dto.setCapabilityBoundary(null);
            return;
        }
        dto.setCapabilityBoundary(card.capabilityBoundary());
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
        // Discovery hard constraints, so the pipeline/dev step can read them as data
        // (the env value is set directly on the child process, no shell escaping).
        if (dto.getDiscoveryCardId() != null && dto.getCapabilityBoundary() != null) {
            env.put("DISCOVERY_CARD_ID", dto.getDiscoveryCardId());
            try {
                env.put("CAPABILITY_BOUNDARY", objectMapper.writeValueAsString(dto.getCapabilityBoundary()));
            } catch (IOException e) {
                log.warn("Could not serialise capabilityBoundary for {}", dto.getDiscoveryCardId(), e);
            }
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

    /** True when the delivered project ships a plain-language EXPLAINER.md tour. */
    public boolean hasExplainer(String taskId) {
        if (!isNewProjectResult(taskId)) return false;
        return resultDir(taskId).map(d -> Files.isRegularFile(d.resolve("EXPLAINER.md"))).orElse(false);
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

    /**
     * The task's requested parallel dev-agent count, for the confirm-gate cost
     * estimate. Read from issue.json (the authoritative submit-time record) and
     * clamped to the same 1–10 range run-task.sh enforces. A MAX_AGENTS env
     * override outside the normal gateway path can still diverge; this estimate
     * covers gateway-submitted tasks.
     */
    public int readMaxAgents(String taskId) {
        Path issue = workDir.resolve(normalizeTaskId(taskId)).resolve("issue.json");
        if (!Files.exists(issue)) return 3;
        try {
            IssueDto dto = objectMapper.readValue(Files.readString(issue), IssueDto.class);
            Integer n = dto.getMaxAgents();
            if (n == null) return 3;
            return Math.min(10, Math.max(1, n));
        } catch (IOException e) {
            return 3;
        }
    }

    /** The plain-language plan summary shown at the pre-flight confirmation gate. */
    public Optional<String> readPlanSummary(String taskId) {
        return readMarkdown(taskId, "plan_summary.md");
    }

    /**
     * The evidence-based candidate selection report the pipeline writes during
     * SELECTING: per-candidate checks (summary, diff size, preview, boundary,
     * secrets, conflicts), fixed scores, and why the winner won. Shown on the
     * completed page so "select best" is auditable, not a black box.
     */
    public Optional<String> readSelectionReport(String taskId) {
        return readMarkdown(taskId, "selection_report.md");
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
            // summary.md / plan_summary.md are AI-authored: the agent runs with the
            // pipeline env (tokens, API keys) in scope, so a stray echo could land a
            // secret in this text — which is shown in the UI and, in repo mode, can
            // flow into the PR body. Redact line-by-line, same as the activity feed.
            String s = Files.readString(f).lines()
                    .map(TaskService::redactSecrets)
                    .collect(java.util.stream.Collectors.joining("\n"))
                    .strip();
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
    // command printed (a clone URL with a token, a key=value secret, etc.). The
    // masking lives in the shared SecretRedactor so every surface that echoes
    // pipeline/agent output (feed, AI markdown, governance events) gets the same
    // defence-in-depth view, never the raw bytes.
    private static String redactSecrets(String line) {
        return SecretRedactor.redact(line);
    }

    /**
     * Write the approve/cancel marker the waiting pipeline polls for. Atomic
     * (temp + rename). The bash loop treats cancel as winning over approve.
     */
    public void writeConfirmMarker(String taskId, boolean approve, String option, String note)
            throws IOException {
        Path dir = workDir.resolve(normalizeTaskId(taskId));
        if (approve && option != null && !option.isBlank()) {
            Path optTarget = dir.resolve("confirm.option");
            Files.writeString(optTarget, option.trim() + "\n");
        }
        // The user's free-text plan/note: write it (sanitised) BEFORE confirm.approve
        // so the running pipeline only sees it once the approve marker exists.
        if (approve) {
            String clean = sanitizeNote(note);
            if (!clean.isBlank()) {
                Files.writeString(dir.resolve("confirm.note"), clean);
            }
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

    /**
     * Sanitise the user's confirm-page note. It is treated purely as DATA (written
     * to a file and folded into the plan as a fenced block, never executed):
     * normalise newlines, cap length, and drop NUL / control characters (keeping
     * \n and \t).
     */
    private static String sanitizeNote(String note) {
        return sanitizeText(note, 16384);
    }

    // --- Plan-refine marker protocol (confirm gate) ------------------------------
    // The waiting pipeline polls refine.request, runs the AI CLI in print mode,
    // and answers with refine.response (or refine.failed). The gateway only
    // moves markers — the model and its env stay with the pipeline.

    /**
     * Ask the waiting pipeline to AI-polish the user's plan draft. Returns false
     * when a refine is already in flight (one at a time). The draft is sanitised
     * the same way as the confirm note — it is data, never instructions.
     */
    public boolean writeRefineRequest(String taskId, String draft) throws IOException {
        Path dir = workDir.resolve(normalizeTaskId(taskId));
        String clean = sanitizeText(draft, 16384);
        if (clean.isBlank()) {
            throw new IllegalArgumentException("計畫內容是空的，先寫點什麼再請粉圓潤飾");
        }
        // Clear the previous round's outputs, then CLAIM the request atomically:
        // createFile is create-or-fail, so a second concurrent POST loses the race
        // and gets FileAlreadyExists (-> 409) rather than overwriting. (ATOMIC_MOVE
        // can't be used as the guard — POSIX rename silently replaces the target.)
        // The consumer only acts on a NON-EMPTY request, so it never reads the
        // just-claimed empty file before its body is written.
        Files.deleteIfExists(dir.resolve("refine.response"));
        Files.deleteIfExists(dir.resolve("refine.failed"));
        Path target = dir.resolve("refine.request");
        try {
            Files.createFile(target);
        } catch (java.nio.file.FileAlreadyExistsException e) {
            return false;   // a refine is already in flight
        }
        try {
            Files.writeString(target, clean);
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(target);   // release the claim so a retry can proceed
            throw e;
        }
        return true;
    }

    /**
     * Where the refine round stands: ready (with the redacted text), pending,
     * failed, or none. The response is AI-authored, so it goes through the same
     * redaction as every other AI artifact before it reaches the browser.
     */
    public Map<String, String> refineStatus(String taskId) {
        Path dir = workDir.resolve(normalizeTaskId(taskId));
        Optional<String> response = readMarkdown(taskId, "refine.response");
        if (response.isPresent()) {
            return Map.of("status", "ready", "text", response.get());
        }
        if (Files.exists(dir.resolve("refine.failed"))) {
            return Map.of("status", "failed");
        }
        if (Files.exists(dir.resolve("refine.request"))) {
            return Map.of("status", "pending");
        }
        return Map.of("status", "none");
    }

    /**
     * Sanitise free user text (description, note, …): normalise newlines, cap the
     * length, and drop NUL / control characters (keeping \n and \t). A shared
     * backend guard so an oversized or control-char-laden value can't slip in via
     * the JSON API, multipart form, or a webhook even if the browser is bypassed.
     */
    private static String sanitizeText(String s, int maxLen) {
        if (s == null) return "";
        String t = s.replace("\r\n", "\n").replace("\r", "\n");
        if (t.length() > maxLen) t = t.substring(0, maxLen);
        StringBuilder sb = new StringBuilder(t.length());
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || c == '\t') {
                sb.append(c);
            } else if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString().strip();
    }

    /**
     * Abort a running task: kill its pipeline process tree and finalize to
     * CANCELLED (a user decision, never FAILED). Terminal tasks can't be aborted.
     */
    public boolean abortTask(String taskId) {
        String id = normalizeTaskId(taskId);
        TaskRecord r = findStatus(id).orElse(null);
        // AWAITING_CONFIRMATION is a wait-for-human state with its own cancel path
        // (the confirm gate); it isn't an actively-running task to abort here.
        if (r == null || r.terminal() || r.status() == TaskStatus.AWAITING_CONFIRMATION) return false;
        Path dir = workDir.resolve(id);
        // Drop an abort marker FIRST: a pipeline this process no longer owns (an
        // orphan after a restart, which the in-process kill below can't reach)
        // self-terminates at its next checkpoint — so abort can't report success
        // while the task keeps running.
        try {
            Files.writeString(dir.resolve("abort.requested"), Instant.now().toString());
        } catch (IOException ignored) {
        }
        pipelineExecutor.abort(id);   // immediate kill for a process we still own
        try {
            Files.deleteIfExists(dir.resolve("pause.requested"));   // let a paused one reach the abort check
        } catch (IOException ignored) {
        }
        try {
            // Record the decision immediately; the executor's onExit guard agrees
            // and won't overwrite. Also covers the not-running-here (restart) case.
            writeStatus(dir, TaskStatus.CANCELLED, "cancelled_by_user");
        } catch (IOException e) {
            log.warn("Aborted {} but could not write CANCELLED status: {}", id, e.getMessage());
        }
        return true;
    }

    /**
     * Soft pause: drop a marker the running pipeline checks at stage boundaries.
     * It isn't instant — the task pauses at its next checkpoint. Resumable.
     */
    public boolean pauseTask(String taskId) {
        String id = normalizeTaskId(taskId);
        TaskRecord r = findStatus(id).orElse(null);
        // Not terminal, not already paused, and not waiting on the user (the
        // confirm gate is its own thing — pausing it is meaningless).
        if (r == null || r.terminal()
                || r.status() == TaskStatus.PAUSED
                || r.status() == TaskStatus.AWAITING_CONFIRMATION) return false;
        try {
            Files.writeString(workDir.resolve(id).resolve("pause.requested"), Instant.now().toString());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Resume by removing the pause marker. Valid when the task is PAUSED, or when
     * a pause was requested but hasn't taken effect yet (marker present) — both
     * mean "undo the pause". Rejected when there's nothing to resume.
     */
    public boolean resumeTask(String taskId) {
        String id = normalizeTaskId(taskId);
        TaskRecord r = findStatus(id).orElse(null);
        if (r == null || r.terminal()) return false;
        Path marker = workDir.resolve(id).resolve("pause.requested");
        boolean pending = Files.exists(marker);
        if (r.status() != TaskStatus.PAUSED && !pending) return false;
        try {
            Files.deleteIfExists(marker);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public Optional<TaskRecord> findStatus(String taskId) {
        String id = normalizeTaskId(taskId);
        if (deletedTaskIds.contains(id)) return Optional.empty();
        TaskRecord record = tasks.get(id);
        if (record == null) return Optional.empty();
        return Optional.of(refresh(record));
    }

    public List<TaskRecord> listTasks() {
        List<TaskRecord> snapshot = new ArrayList<>();
        for (TaskRecord record : tasks.values()) {
            if (deletedTaskIds.contains(record.taskId())) continue;
            snapshot.add(refresh(record));
        }
        return snapshot;
    }

    private TaskRecord refresh(TaskRecord record) {
        if (deletedTaskIds.contains(record.taskId())) return record;
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
                else if (parsed == TaskStatus.FAILED || parsed == TaskStatus.CANCELLED) {
                    etaService.forget(record.taskId());
                }
            }
            // Stamp the completion time once, from the pipeline's own terminal
            // timestamp (COMPLETED_AT, else UPDATED_AT), and persist it so a restart
            // keeps an accurate elapsed-time.
            boolean terminal = parsed == TaskStatus.COMPLETED || parsed == TaskStatus.FAILED
                    || parsed == TaskStatus.CANCELLED;
            Instant completedAt = record.completedAt();
            if (terminal && completedAt == null) {
                completedAt = parseInstant(kv.get("COMPLETED_AT"));
                if (completedAt == null) completedAt = parseInstant(kv.get("UPDATED_AT"));
                if (completedAt == null) completedAt = Instant.now();
                writeTaskMeta(workDir.resolve(record.taskId()), record.submittedAt(), completedAt);
            }
            TaskRecord updated = record.update(parsed, message, prUrl, completedAt);
            if (deletedTaskIds.contains(record.taskId())) return record; // deleted mid-refresh
            tasks.put(record.taskId(), updated);
            return updated;
        } catch (IOException e) {
            log.warn("Failed to refresh status for {}: {}", record.taskId(), e.getMessage());
            return record;
        }
    }

    private static Instant parseInstant(String v) {
        if (v == null || v.isBlank()) return null;
        try {
            return Instant.parse(v.trim());
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }

    /** Gateway-owned durable timing, so submit/complete times survive a restart. */
    private void writeTaskMeta(Path taskDir, Instant submittedAt, Instant completedAt) {
        try {
            Map<String, String> meta = new LinkedHashMap<>();
            if (submittedAt != null) meta.put("submittedAt", submittedAt.toString());
            if (completedAt != null) meta.put("completedAt", completedAt.toString());
            Path tmp = taskDir.resolve("task-meta.json.tmp");
            Files.writeString(tmp, objectMapper.writeValueAsString(meta));
            Files.move(tmp, taskDir.resolve("task-meta.json"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            log.debug("Could not write task-meta for {}: {}", taskDir, e.getMessage());
        }
    }

    private Map<String, Instant> readTaskMeta(Path taskDir) {
        Path f = taskDir.resolve("task-meta.json");
        Map<String, Instant> out = new LinkedHashMap<>();
        if (!Files.exists(f)) return out;
        try {
            Map<?, ?> raw = objectMapper.readValue(Files.readString(f), Map.class);
            Instant s = parseInstant(raw.get("submittedAt") == null ? null : String.valueOf(raw.get("submittedAt")));
            Instant c = parseInstant(raw.get("completedAt") == null ? null : String.valueOf(raw.get("completedAt")));
            if (s != null) out.put("submittedAt", s);
            if (c != null) out.put("completedAt", c);
        } catch (IOException ignored) {
        }
        return out;
    }

    /**
     * Delete a finished task: remove it from the in-memory list and erase its
     * work directory (including any result.zip). Only terminal tasks may be
     * deleted; a tombstone blocks a concurrent refresh/restore from resurrecting
     * it. Path is validated to stay inside the work dir.
     */
    public boolean deleteTask(String taskId) {
        String id = normalizeTaskId(taskId);
        TaskRecord current = findStatus(id).orElse(null);
        if (current == null || !current.terminal()) return false;
        deletedTaskIds.add(id);
        tasks.remove(id);
        try {
            Path base = workDir.normalize();
            Path dir = base.resolve(id).normalize();
            // Must be a *direct child* of the work dir — never the work dir itself
            // (id="." resolves to it) or anything above it (id=".."). Deleting the
            // work dir would wipe every task.
            if (dir.getParent() != null && dir.getParent().equals(base)
                    && !dir.equals(base) && Files.isDirectory(dir)) {
                deleteRecursively(dir);
            } else {
                log.warn("Refusing to delete unsafe path for task id '{}' -> {}", id, dir);
            }
        } catch (IOException e) {
            log.warn("Deleted task {} from list but could not erase its files: {}", id, e.getMessage());
        }
        return true;
    }

    private static void deleteRecursively(Path dir) throws IOException {
        Files.walkFileTree(dir, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes a)
                    throws IOException {
                Files.deleteIfExists(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path d, IOException e) throws IOException {
                Files.deleteIfExists(d);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
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

    /**
     * Sanitise a task id to a safe single path segment (no traversal): non
     * [A-Za-z0-9._-] chars become '-', and an all-dots value (".", "..") is
     * replaced, so {@code workDir.resolve(id)} can never escape the work dir.
     * Public so other governance/read paths reuse the same audited guard.
     */
    public String normalizeTaskId(String value) {
        String v = (value == null ? "" : value).replaceAll("[^A-Za-z0-9._-]", "-");
        // Never allow an id that resolves to the work dir itself ("." / "") or its
        // parent (".."), which would let path ops escape the per-task directory.
        if (v.isBlank() || v.chars().allMatch(c -> c == '.')) {
            v = "task-" + Integer.toHexString((value == null ? "" : value).hashCode());
        }
        return v;
    }
}
