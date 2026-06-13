package com.lza.aifactory.governance;

import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import com.lza.aifactory.service.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Aggregates governance activity across all tasks into the interception
 * dashboard view: blocked actions ("Safe Catch"), tasks awaiting human delivery
 * approval, active overrides, and a policy friction map (which rules get hit
 * most). The dashboard is a security control surface, not a CI log — its value
 * is showing "the AI tried something it wasn't allowed to, and we stopped it".
 * See docs/design/governance-runtime.md §3.3 / §8.
 */
@Service
public class GovernanceDashboardService {

    private static final int MAX_FEED = 50;

    private final GovernanceEventLog eventLog;
    private final TaskService taskService;
    private final Path workDir;

    public GovernanceDashboardService(GovernanceEventLog eventLog,
                                      TaskService taskService,
                                      @Value("${ai-factory.work-dir}") String workDir) {
        this.eventLog = eventLog;
        this.taskService = taskService;
        this.workDir = Path.of(workDir);
    }

    public record Incident(String taskId, String kind, String detail, String occurredAt) {}
    public record OverrideEntry(String taskId, String rationale, String ticket, String expiry, String occurredAt) {}
    public record PendingApproval(String taskId, String title) {}

    public record DashboardData(
            List<Incident> safeCatches,
            List<PendingApproval> pending,
            List<OverrideEntry> overrides,
            Map<String, Integer> frictionMap,
            int governedTasks,
            boolean anyTampered) {}

    public DashboardData aggregate() {
        List<Incident> safeCatches = new ArrayList<>();
        List<OverrideEntry> overrides = new ArrayList<>();
        Map<String, Integer> friction = new LinkedHashMap<>();
        int governed = 0;
        boolean tampered = false;

        if (Files.isDirectory(workDir)) {
            try (Stream<Path> dirs = Files.list(workDir)) {
                // NOFOLLOW_LINKS: a symlinked task dir (if the work dir were writable
                // by anything untrusted) must not make the dashboard read outside the
                // intended task tree. Only real, safely-named direct children count.
                for (Path dir : dirs.filter(p -> Files.isDirectory(p, java.nio.file.LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String name = dir.getFileName().toString();
                    if (!name.equals(taskService.normalizeTaskId(name))) continue;  // skip odd names
                    if (!Files.exists(dir.resolve("governance-events.jsonl"))) continue;
                    governed++;
                    GovernanceEventLog.ReadResult r = eventLog.read(name);
                    if (r.tampered()) tampered = true;
                    for (GovernanceEvent e : r.events()) {
                        classify(name, e, safeCatches, overrides, friction);
                    }
                }
            } catch (IOException ignored) {
                // A dashboard read failure should degrade gracefully, not 500.
            }
        }

        // Newest first; cap the feed so the page stays a glanceable control surface.
        safeCatches.sort(Comparator.comparing(Incident::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));
        if (safeCatches.size() > MAX_FEED) safeCatches = safeCatches.subList(0, MAX_FEED);
        overrides.sort(Comparator.comparing(OverrideEntry::occurredAt, Comparator.nullsLast(Comparator.reverseOrder())));

        List<PendingApproval> pending = new ArrayList<>();
        for (TaskRecord t : taskService.listTasks()) {
            if (t.status() == TaskStatus.AWAITING_DELIVERY_APPROVAL) {
                pending.add(new PendingApproval(t.taskId(), t.title()));
            }
        }
        return new DashboardData(safeCatches, pending, overrides, friction, governed, tampered);
    }

    private void classify(String taskId, GovernanceEvent e, List<Incident> safeCatches,
                          List<OverrideEntry> overrides, Map<String, Integer> friction) {
        String type = e.eventType();
        String when = e.occurredAt() == null ? null : e.occurredAt().toString();
        if ("boundary-violation-blocked".equals(type) || "gate-failed".equals(type)
                || "independence-check-failed".equals(type)) {
            String detail = e.decision() != null && e.decision().reason() != null
                    ? e.decision().reason()
                    : str(e.extra(), "reason");
            String rule = e.decision() != null && e.decision().matchedRule() != null
                    ? e.decision().matchedRule()
                    : str(e.extra(), "gate");
            safeCatches.add(new Incident(taskId, type, detail == null ? rule : detail, when));
            if (rule != null && !rule.isBlank()) {
                friction.merge(rule, 1, Integer::sum);
            }
        } else if ("override-with-rationale".equals(type)) {
            overrides.add(new OverrideEntry(taskId,
                    str(e.extra(), "rationale"), str(e.extra(), "ticket"),
                    str(e.extra(), "expiry"), when));
        }
    }

    private static String str(Map<String, Object> m, String k) {
        if (m == null) return null;
        Object v = m.get(k);
        return v == null ? null : String.valueOf(v);
    }
}
