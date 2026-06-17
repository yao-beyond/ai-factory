package com.lza.aifactory.governance;

import com.lza.aifactory.service.SecretRedactor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Assembles the evidence bundle for a task from the artifacts the pipeline
 * leaves on disk, and renders the human-readable Governance Evidence Pack (GEP)
 * — the "結案報告" a CISO/auditor can read instead of raw JSONL. In Phase 1a the
 * pipeline is not yet wired, so assembly is best-effort over whatever artifacts
 * already exist; Phase 1b feeds it the real diff/test/review refs.
 *
 * <p>See docs/design/governance-runtime.md §3.4 / §7.
 */
@Service
public class EvidenceService {

    private final Path workDir;

    public EvidenceService(@Value("${ai-factory.work-dir}") String workDir) {
        this.workDir = Path.of(workDir);
    }

    /** Best-effort assembly from on-disk artifacts (filenames as refs). */
    public EvidenceBundle assemble(String taskId, String policyHash) {
        Path dir = workDir.resolve(taskId);
        String diff = refIfExists(dir, "workspace/repo");        // a working tree present
        String tests = refIfExists(dir, "test-results.txt");      // pipeline writes this in 1b
        String review = firstExisting(dir, List.of(
                "selection_report.md", "summary.md"));            // review/summary proxies until 1b
        return new EvidenceBundle(null, diff, tests, review, policyHash,
                new ArrayList<>(), Instant.now());
    }

    /**
     * Render the human-readable GEP. Sections follow the EXPLAINER ordering
     * philosophy (what → did → tested → who reviewed → policy → integrity), all
     * run through redaction so a leaked secret never reaches the report.
     */
    public String renderGep(String taskId, GovernanceProfile profile, EvidenceBundle bundle,
                            GovernanceEventLog.ReadResult events) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 治理證據包（GEP）— 任務 ").append(red(taskId)).append("\n\n");
        sb.append("> 這份報告由 AI Factory 自動產生，記錄 AI 在治理政策下做了什麼、被擋了什麼、誰核准。\n\n");

        sb.append("## 1. 治理政策\n");
        sb.append("- Profile：**").append(red(profile.title())).append("**（")
          .append(red(profile.id())).append("，風險等級 ").append(red(profile.riskTier())).append("）\n");
        sb.append("- 政策雜湊：`").append(red(orDash(bundle.policyHash()))).append("`\n");
        sb.append("- 人類核准：").append(profile.humanApprovalRequired() ? "必須" : "選用").append("\n\n");

        sb.append("## 2. 證據完整性\n");
        List<String> missing = bundle.missingFor(profile);
        if (missing.isEmpty()) {
            sb.append("- ✅ 證據包齊全（符合此 profile 的交付前閘門）\n");
        } else {
            sb.append("- ⚠️ 缺少：").append(red(String.join("、", missing))).append("\n");
        }
        sb.append("- 變更內容：`").append(red(orDash(bundle.proposedDiffRef()))).append("`\n");
        sb.append("- 測試結果：`").append(red(orDash(bundle.testResultsRef()))).append("`\n");
        sb.append("- 審查報告：`").append(red(orDash(bundle.reviewReportRef()))).append("`\n\n");

        sb.append("## 3. 治理事件（含被攔截的動作）\n");
        if (events == null || events.events().isEmpty()) {
            sb.append("- （尚無治理事件）\n");
        } else {
            for (GovernanceEvent e : events.events()) {
                String mark = "boundary-violation-blocked".equals(e.eventType()) ? "🛡️ 攔截" : "•";
                sb.append("- ").append(mark).append(" `").append(red(e.eventType())).append("`");
                if (e.decision() != null && e.decision().reason() != null) {
                    sb.append("：").append(red(e.decision().reason()));
                }
                sb.append("\n");
            }
        }
        sb.append("\n## 4. 紀錄完整性驗證\n");
        if (events != null && events.tampered()) {
            sb.append("- ⚠️ 雜湊鏈在 seq ").append(events.brokenSeq())
              .append(" 處驗證失敗：事件紀錄可能遭竄改，請人工調查。\n");
        } else {
            sb.append("- ✅ 雜湊鏈驗證通過（未發現竄改）\n");
        }
        sb.append("- ℹ️ 此為可偵測竄改（tamper-evident）的雜湊鏈，非密碼學簽章；")
          .append("無法防範能重寫整個檔的主機層攻擊者（外部簽章為後續階段）。\n");
        return sb.toString();
    }

    private String refIfExists(Path dir, String rel) {
        return Files.exists(dir.resolve(rel)) ? rel : null;
    }

    private String firstExisting(Path dir, List<String> rels) {
        for (String r : rels) {
            if (Files.exists(dir.resolve(r))) return r;
        }
        return null;
    }

    private static String orDash(String s) {
        return (s == null || s.isBlank()) ? "（無）" : s;
    }

    private static String red(String s) {
        return SecretRedactor.redact(s == null ? "" : s);
    }
}
