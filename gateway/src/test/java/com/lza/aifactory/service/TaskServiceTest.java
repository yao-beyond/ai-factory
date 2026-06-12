package com.lza.aifactory.service;

import com.lza.aifactory.dto.IssueDto;
import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit-level coverage for TaskService's state machine and security guards:
 * input sanitisation, hostile task-id normalisation, pause/resume/abort/delete
 * transitions, status.txt refresh, secret redaction in the activity feed, and
 * preview path-traversal protection. Uses the no-op pipeline so tasks stay in
 * their initial state until the test moves them.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "ai-factory.work-dir=${java.io.tmpdir}/ai-factory-taskservice-test",
        "ai-factory.pipeline-script=${user.dir}/src/test/resources/noop-pipeline.sh"
})
class TaskServiceTest {

    @Autowired
    private TaskService taskService;

    private final Path workDir =
            Path.of(System.getProperty("java.io.tmpdir"), "ai-factory-taskservice-test");

    private IssueDto dto(String externalId) {
        IssueDto dto = new IssueDto();
        dto.setSource("web");
        dto.setExternalId(externalId);
        dto.setTitle("t");
        dto.setDescription("d");
        dto.setMaxAgents(1);
        return dto;
    }

    /** Write a terminal status the way the pipeline would, so refresh() picks it up. */
    private void writeTerminalStatus(String taskId, TaskStatus status) throws Exception {
        Files.writeString(workDir.resolve(taskId).resolve("status.txt"),
                "STATUS=" + status.name() + "\nMESSAGE=test\nUPDATED_AT=" + Instant.now() + "\n");
    }

    // --- Input sanitisation ------------------------------------------------

    @Test
    void submitStripsControlCharsAndCapsLength() throws Exception {
        IssueDto in = dto("SAN-1");
        in.setTitle("hello\u0000\u001Bworld");
        in.setDescription("x".repeat(40_000));
        TaskRecord r = taskService.submit(in);
        assertEquals("helloworld", r.title());
        // Description is capped at 32768 in the stored issue.json.
        String issue = Files.readString(workDir.resolve(r.taskId()).resolve("issue.json"));
        assertFalse(issue.contains("x".repeat(32_769)));
    }

    @Test
    void submitRejectsTitleThatBecomesBlankAfterSanitise() {
        IssueDto in = dto("SAN-2");
        in.setTitle("\u0000\u0001\u0002");
        assertThrows(IllegalArgumentException.class, () -> taskService.submit(in));
    }

    // --- Task id normalisation ----------------------------------------------

    @Test
    void hostileExternalIdNeverEscapesWorkDir() throws Exception {
        TaskRecord r = taskService.submit(dto("../../etc/passwd"));
        Path taskDir = Path.of(r.workDir()).normalize();
        // The task dir must be a direct child of the work dir.
        assertEquals(workDir.normalize(), taskDir.getParent());
        assertFalse(r.taskId().contains("/"));
        // Lookup with the same hostile id resolves to the same task.
        assertTrue(taskService.findStatus("../../etc/passwd").isPresent());
    }

    @Test
    void dotOnlyExternalIdGetsReplacedNotResolved() throws Exception {
        TaskRecord r = taskService.submit(dto(".."));
        assertFalse(r.taskId().chars().allMatch(c -> c == '.'));
        assertEquals(workDir.normalize(), Path.of(r.workDir()).normalize().getParent());
    }

    // --- Pause / resume ------------------------------------------------------

    @Test
    void pauseWritesMarkerAndResumeRemovesIt() throws Exception {
        TaskRecord r = taskService.submit(dto("PAUSE-1"));
        Path marker = workDir.resolve(r.taskId()).resolve("pause.requested");

        assertTrue(taskService.pauseTask(r.taskId()));
        assertTrue(Files.exists(marker));

        assertTrue(taskService.resumeTask(r.taskId()));
        assertFalse(Files.exists(marker));

        // Nothing left to resume.
        assertFalse(taskService.resumeTask(r.taskId()));
    }

    @Test
    void pauseRejectedOnTerminalTask() throws Exception {
        TaskRecord r = taskService.submit(dto("PAUSE-2"));
        writeTerminalStatus(r.taskId(), TaskStatus.COMPLETED);
        assertFalse(taskService.pauseTask(r.taskId()));
    }

    // --- Abort ----------------------------------------------------------------

    @Test
    void abortMovesTaskToCancelledAndIsIdempotentlyRejectedAfter() throws Exception {
        TaskRecord r = taskService.submit(dto("ABORT-1"));
        assertTrue(taskService.abortTask(r.taskId()));
        assertTrue(Files.exists(workDir.resolve(r.taskId()).resolve("abort.requested")));

        TaskRecord after = taskService.findStatus(r.taskId()).orElseThrow();
        assertEquals(TaskStatus.CANCELLED, after.status());
        // Cancelled by the USER is terminal: a second abort is rejected.
        assertFalse(taskService.abortTask(r.taskId()));
    }

    // --- Delete (terminal-only + tombstone) -----------------------------------

    @Test
    void deleteOnlyAllowedForTerminalTasksAndErasesDir() throws Exception {
        TaskRecord r = taskService.submit(dto("DEL-1"));
        // Still SUBMITTED -> refuse.
        assertFalse(taskService.deleteTask(r.taskId()));

        writeTerminalStatus(r.taskId(), TaskStatus.COMPLETED);
        assertEquals(TaskStatus.COMPLETED,
                taskService.findStatus(r.taskId()).orElseThrow().status());

        assertTrue(taskService.deleteTask(r.taskId()));
        // Tombstoned: gone from the list, dir erased.
        assertTrue(taskService.findStatus(r.taskId()).isEmpty());
        assertFalse(Files.exists(workDir.resolve(r.taskId())));
    }

    // --- Refresh from status.txt ----------------------------------------------

    @Test
    void refreshParsesTerminalStatusAndStampsCompletedAt() throws Exception {
        TaskRecord r = taskService.submit(dto("REF-1"));
        Files.writeString(workDir.resolve(r.taskId()).resolve("status.txt"),
                "STATUS=COMPLETED\nMESSAGE=done\nCOMPLETED_AT=2026-01-01T00:00:00Z\n");

        TaskRecord refreshed = taskService.findStatus(r.taskId()).orElseThrow();
        assertEquals(TaskStatus.COMPLETED, refreshed.status());
        assertEquals(Instant.parse("2026-01-01T00:00:00Z"), refreshed.completedAt());
        // Durable: completion time is persisted for restart survival.
        assertTrue(Files.exists(workDir.resolve(r.taskId()).resolve("task-meta.json")));
    }

    @Test
    void refreshIgnoresGarbageStatusValue() throws Exception {
        TaskRecord r = taskService.submit(dto("REF-2"));
        Files.writeString(workDir.resolve(r.taskId()).resolve("status.txt"),
                "STATUS=NOT_A_REAL_STATUS\nMESSAGE=hm\n");
        // Unknown status falls back to the last known one instead of crashing.
        assertEquals(TaskStatus.SUBMITTED,
                taskService.findStatus(r.taskId()).orElseThrow().status());
    }

    // --- Secret redaction in the live activity feed ----------------------------

    @Test
    void recentActivityRedactsTokensCredentialsAndAnsi() throws Exception {
        TaskRecord r = taskService.submit(dto("LOG-1"));
        Files.writeString(workDir.resolve(r.taskId()).resolve("run.log"),
                "cloning https://user:hunter2@github.com/acme/repo.git\n"
                + "GITHUB_TOKEN=ghp_0123456789abcdef0123456789abcdef\n"
                + "AWS_SECRET_ACCESS_KEY=abc123\n"
                + "\u001B[31mred error text\u001B[0m\n");

        List<String> lines = taskService.recentActivity(r.taskId(), 50);
        String joined = String.join("\n", lines);
        assertFalse(joined.contains("hunter2"), "url credentials must be masked");
        assertFalse(joined.contains("ghp_0123456789abcdef"), "provider token must be masked");
        assertFalse(joined.contains("abc123"), "key=value secret must be masked");
        assertFalse(joined.contains("\u001B"), "ANSI codes must be stripped");
        assertTrue(joined.contains("red error text"));
    }

    // --- Preview gating + path traversal ----------------------------------------

    @Test
    void previewOnlyServedForNewProjectsAndNeverEscapesResultDir() throws Exception {
        IssueDto in = dto("PREV-1");
        in.setMode("new");
        TaskRecord r = taskService.submit(in);
        Path repo = workDir.resolve(r.taskId()).resolve("workspace").resolve("repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("index.html"), "<html>ok</html>");

        assertTrue(taskService.hasPreview(r.taskId()));
        assertTrue(taskService.resolvePreviewFile(r.taskId(), "index.html").isPresent());
        // Blank path defaults to index.html.
        assertTrue(taskService.resolvePreviewFile(r.taskId(), "").isPresent());
        // Lexical traversal out of the project dir is refused. issue.json DOES
        // exist two levels up (in the task dir), so only the containment check —
        // not a missing file — stops this from being served.
        assertTrue(taskService.resolvePreviewFile(r.taskId(), "../../issue.json").isEmpty());
    }

    @Test
    void previewRefusedForRepoModeTasks() throws Exception {
        TaskRecord r = taskService.submit(dto("PREV-2"));   // no mode -> repo task
        Path repo = workDir.resolve(r.taskId()).resolve("workspace").resolve("repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("index.html"), "<html>cloned</html>");
        // An existing-repo clone must never be exposed via preview/download.
        assertFalse(taskService.hasPreview(r.taskId()));
        assertTrue(taskService.resolvePreviewFile(r.taskId(), "index.html").isEmpty());
        assertNotNull(taskService.resultZip(r.taskId()));   // Optional, just no NPE
        assertTrue(taskService.resultZip(r.taskId()).isEmpty());
    }

    // --- Selection report (evidence-based "select best" audit trail) -------------

    @Test
    void readSelectionReportIsRedactedAndEmptyWhenMissing() throws Exception {
        TaskRecord r = taskService.submit(dto("SEL-1"));
        // The work dir persists across JVM runs; drop a leftover report from a
        // previous run so the "empty when missing" half stays idempotent.
        Files.deleteIfExists(workDir.resolve(r.taskId()).resolve("selection_report.md"));
        assertTrue(taskService.readSelectionReport(r.taskId()).isEmpty());

        // Pipeline-authored: goes through the same redaction as the summary, so a
        // secret echoed into the report can never reach the UI.
        Files.writeString(workDir.resolve(r.taskId()).resolve("selection_report.md"),
                "# 🏆 AI 候選評選報告\n- 錄取：候選 1\n"
                + "- leaked: GITHUB_TOKEN=ghp_0123456789abcdef0123456789abcdef\n");
        String md = taskService.readSelectionReport(r.taskId()).orElseThrow();
        assertTrue(md.contains("錄取：候選 1"));
        assertFalse(md.contains("ghp_0123456789abcdef"), "report must be redacted");
    }

    // --- AI-authored options.json is parsed defensively --------------------------

    @Test
    void readOptionsDropsMalformedShapesInsteadOfThrowing() throws Exception {
        TaskRecord r = taskService.submit(dto("OPT-1"));
        Path f = workDir.resolve(r.taskId()).resolve("options.json");

        Files.writeString(f, "{\"not\":\"an array\"}");
        assertTrue(taskService.readOptions(r.taskId()).isEmpty());

        Files.writeString(f, "[\"a string\", {\"id\":\"opt-a\",\"label\":\"A\"}]");
        var options = taskService.readOptions(r.taskId());
        assertEquals(1, options.size());                    // non-object entry dropped
        assertEquals("opt-a", options.get(0).get("id"));
    }
}
