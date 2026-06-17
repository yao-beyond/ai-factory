package com.lza.aifactory.controller;

import com.lza.aifactory.governance.EvidenceBundle;
import com.lza.aifactory.governance.EvidenceService;
import com.lza.aifactory.governance.GovernanceEvent;
import com.lza.aifactory.governance.GovernanceEventLog;
import com.lza.aifactory.governance.GovernanceProfile;
import com.lza.aifactory.governance.GovernanceProfileLibrary;
import com.lza.aifactory.service.SecretRedactor;
import com.lza.aifactory.service.TaskService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Server-rendered, READ-ONLY view of one task's hash-chained governance event
 * chain — the "GEP Evidence Explorer". Visualises what each event was based on
 * (policy / matched rule / actor / decision), whether the chain verifies, and
 * links out to the human-readable GEP. Pure presentation over data that already
 * exists in {@link GovernanceEventLog} / {@link EvidenceService}; it never writes,
 * adds events, or changes the schema, and (like /events and the dashboard) needs
 * no operator secret. See docs/design/gep-explorer.md.
 *
 * <p>Honest boundary: the chain is tamper-EVIDENT, not tamper-PROOF; a null
 * {@code signature} is shown as "未簽章" rather than implying cryptographic proof.
 */
@RestController
public class GovernanceExplorerController {

    private final GovernanceEventLog eventLog;
    private final EvidenceService evidenceService;
    private final GovernanceProfileLibrary profileLibrary;
    private final TaskService taskService;

    public GovernanceExplorerController(GovernanceEventLog eventLog,
                                        EvidenceService evidenceService,
                                        GovernanceProfileLibrary profileLibrary,
                                        TaskService taskService) {
        this.eventLog = eventLog;
        this.evidenceService = evidenceService;
        this.profileLibrary = profileLibrary;
        this.taskService = taskService;
    }

    @GetMapping(value = "/gateway/governance/{taskId}/explorer", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> explorer(@PathVariable String taskId) {
        // Normalise to a safe single path segment before any disk access (shared
        // guard with TaskService — defends against encoded-slash traversal).
        String id = taskService.normalizeTaskId(taskId);
        if (taskService.findStatus(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        GovernanceProfile profile = profileLibrary.enabledById(taskService.readGovernanceProfileId(id))
                .orElseGet(() -> profileLibrary.enabledById("standard-app").orElse(null));
        GovernanceEventLog.ReadResult chain = eventLog.read(id);

        // Integrity banner straight from the read verdict — never recomputed or masked.
        String banner = chain.tampered()
                ? "<div class=\"tamper\">⚠️ 雜湊鏈在 seq " + esc(String.valueOf(chain.brokenSeq()))
                  + " 處驗證失敗：事件紀錄可能遭竄改，請人工調查。</div>"
                : "<div class=\"ok\">✅ 雜湊鏈驗證通過（未發現竄改）</div>";

        String profileLine = profile == null
                ? "<span class=\"muted\">（未知 profile）</span>"
                : red(profile.title()) + "（<code>" + red(profile.id()) + "</code> · "
                  + red(profile.riskTier()) + "） ｜ 人類核准："
                  + (profile.humanApprovalRequired() ? "必須" : "選用");

        String missingLine;
        if (profile == null) {
            missingLine = "—";
        } else {
            EvidenceBundle bundle = evidenceService.assemble(id, null);
            List<String> missing = bundle.missingFor(profile);
            missingLine = missing.isEmpty()
                    ? "✅ 證據包齊全"
                    : "⚠️ 缺少：" + red(String.join("、", missing));
        }

        StringBuilder nodes = new StringBuilder();
        if (chain.events().isEmpty()) {
            nodes.append("<p class=\"empty\">尚無治理事件。</p>");
        } else {
            for (GovernanceEvent e : chain.events()) {
                nodes.append(renderNode(e));
            }
        }

        return ResponseEntity.ok(PAGE.formatted(
                esc(id), esc(id), esc(id),     // title, header taskId, GEP link id
                banner, profileLine, missingLine,
                chain.events().size(), nodes.toString()));
    }

    private String renderNode(GovernanceEvent e) {
        String icon = icon(e.eventType());
        StringBuilder meta = new StringBuilder();

        GovernanceEvent.Actor a = e.actor();
        if (a != null && a.principalId() != null) {
            meta.append("<div class=\"m\">actor：").append(red(a.principalId()));
            if (a.role() != null) meta.append(" · ").append(red(a.role()));
            if (a.vendor() != null) meta.append(" · ").append(red(a.vendor()));
            meta.append("</div>");
        }

        GovernanceEvent.Decision d = e.decision();
        if (d != null && (d.reason() != null || d.matchedRule() != null || d.result() != null)) {
            meta.append("<div class=\"m\">decision：");
            if (d.result() != null) meta.append(red(d.result()));
            if (d.matchedRule() != null) meta.append(" · rule <code>").append(red(d.matchedRule())).append("</code>");
            if (d.reason() != null) meta.append(" — ").append(red(d.reason()));
            meta.append("</div>");
        }

        String extra = renderExtra(e.extra());
        if (!extra.isEmpty()) meta.append("<div class=\"m\">").append(extra).append("</div>");

        String policy = (e.policyHash() == null) ? "" : " ｜ policy <code>" + esc(shortHash(e.policyHash())) + "</code>";
        String hashLine = "";
        if (e.integrity() != null) {
            String sig = e.integrity().signature() == null
                    ? "<span class=\"muted\">未簽章</span>" : "已簽章";
            hashLine = "<div class=\"h\">hash <code>" + esc(shortHash(e.integrity().eventHash()))
                    + "</code> ← <code>" + esc(shortHash(e.integrity().prevEventHash()))
                    + "</code> ｜ " + sig + "</div>";
        }

        return """
            <div class="node %s">
              <div class="hd"><span class="ic">%s</span><span class="seq">seq %s</span>
                <span class="ev"><code>%s</code></span><span class="ts">%s%s</span></div>
              %s
              %s
            </div>
            """.formatted(
                kind(e.eventType()), icon, esc(String.valueOf(e.seq())), esc(orDash(e.eventType())),
                esc(e.occurredAt() == null ? "—" : e.occurredAt().toString()), policy,
                meta, hashLine);
    }

    private String renderExtra(Map<String, Object> extra) {
        if (extra == null || extra.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String k : List.of("gate", "to", "rationale", "ticket", "expiry", "implementer", "reviewer")) {
            Object v = extra.get(k);
            if (v == null) continue;
            if (sb.length() > 0) sb.append(" · ");
            sb.append(esc(k)).append("=").append(red(String.valueOf(v)));
        }
        return sb.toString();
    }

    private static String icon(String type) {
        if (type == null) return "•";
        return switch (type) {
            case "boundary-violation-blocked", "gate-failed", "independence-check-failed" -> "🛡️";
            case "gate-passed", "independence-check-passed" -> "✅";
            case "promotion-state-change" -> "🧑‍⚖️";
            case "override-with-rationale" -> "🔓";
            default -> "•";
        };
    }

    private static String kind(String type) {
        if (type == null) return "";
        return switch (type) {
            case "boundary-violation-blocked", "gate-failed", "independence-check-failed" -> "blocked";
            case "gate-passed", "independence-check-passed" -> "passed";
            default -> "";
        };
    }

    /** Short, display-only hash: keep the algo prefix + first 10 hex. */
    private static String shortHash(String h) {
        if (h == null || h.isBlank()) return "—";
        int colon = h.indexOf(':');
        String algo = colon > 0 ? h.substring(0, colon + 1) : "";
        String hex = colon > 0 ? h.substring(colon + 1) : h;
        return algo + (hex.length() > 10 ? hex.substring(0, 10) + "…" : hex);
    }

    private static String orDash(String v) {
        return (v == null || v.isBlank()) ? "—" : v;
    }

    /** Redact secrets THEN HTML-escape — never leak a secret, never allow injection. */
    private static String red(String v) {
        return esc(SecretRedactor.redact(v == null ? "" : v));
    }

    private static String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String PAGE = """
        <!doctype html>
        <html lang="zh-Hant">
        <head>
          <meta charset="utf-8">
          <meta name="viewport" content="width=device-width, initial-scale=1">
          <title>AI Factory — 證據鏈 %s</title>
          <style>
            body{font-family:-apple-system,"Segoe UI",Roboto,"PingFang TC","Microsoft JhengHei",sans-serif;
                 background:#0d1117;color:#e6edf3;margin:0;padding:32px 16px;}
            .wrap{max-width:880px;margin:0 auto;}
            h1{font-size:22px;margin:0 0 4px;display:flex;justify-content:space-between;align-items:center;gap:12px;flex-wrap:wrap;}
            h1 .links{font-size:13px;font-weight:400;}
            h1 .links a{color:#58a6ff;text-decoration:none;margin-left:14px;}
            .sub{color:#8b949e;font-size:13px;margin:0 0 22px;}
            .card{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:18px 20px;margin:0 0 18px;}
            .card h2{font-size:15px;margin:0 0 12px;display:flex;justify-content:space-between;align-items:center;}
            .pill{font-size:12px;background:#21262d;border:1px solid #30363d;border-radius:999px;padding:2px 10px;color:#8b949e;}
            .ok{background:#12261a;border:1px solid #238636;color:#7ee2a8;border-radius:10px;padding:12px 14px;margin:0 0 18px;font-size:14px;}
            .tamper{background:#3d1d1d;border:1px solid #f85149;color:#ffb4ab;border-radius:10px;padding:12px 14px;margin:0 0 18px;font-size:14px;}
            .kv{font-size:13px;color:#c9d1d9;line-height:1.9;}
            .kv .lbl{color:#8b949e;display:inline-block;min-width:84px;}
            code{font-family:ui-monospace,Menlo,monospace;font-size:12px;background:#21262d;border-radius:5px;padding:1px 5px;color:#c9d1d9;}
            .node{border-left:2px solid #30363d;padding:4px 0 14px 16px;margin-left:8px;position:relative;}
            .node:before{content:"";position:absolute;left:-7px;top:8px;width:12px;height:12px;border-radius:50%%;
                         background:#30363d;border:2px solid #0d1117;}
            .node.passed:before{background:#238636;} .node.blocked:before{background:#f85149;}
            .node .hd{display:flex;gap:10px;align-items:baseline;flex-wrap:wrap;font-size:14px;}
            .node .ic{font-size:15px;} .node .seq{color:#6e7681;font-size:12px;font-family:ui-monospace,Menlo,monospace;}
            .node .ev{font-weight:600;} .node .ts{color:#6e7681;font-size:11px;margin-left:auto;}
            .node .m{color:#c9d1d9;font-size:12.5px;margin-top:5px;}
            .node .h{color:#6e7681;font-size:11px;margin-top:5px;}
            .caveat{color:#8b949e;font-size:12px;margin:-8px 0 18px;line-height:1.5;}
            .muted{color:#6e7681;} .empty{color:#6e7681;font-size:13px;}
            .foot{color:#6e7681;font-size:12px;margin-top:8px;text-align:center;}
            .foot a{color:#58a6ff;}
          </style>
        </head>
        <body>
          <div class="wrap">
            <h1>🔍 證據鏈 — 任務 %s
              <span class="links"><a href="/gateway/governance/%s/gep">📄 下載證據包</a>
                <a href="/gateway/governance/dashboard">← 控制台</a></span></h1>
            <p class="sub">這個任務的治理事件，依雜湊鏈順序展開：每個動作基於哪條政策、誰做的、被擋還是放行，一眼看完。</p>
            %s
            <p class="caveat">ℹ️ 雜湊鏈是可偵測竄改（tamper-evident）的完整性檢查，非密碼學簽章——擋得住意外編輯與部分竄改，擋不住能重寫整個檔的主機層攻擊者；外部簽章列為後續階段。</p>
            <div class="card">
              <h2>摘要</h2>
              <div class="kv"><span class="lbl">Profile</span>%s</div>
              <div class="kv"><span class="lbl">證據完整性</span>%s</div>
            </div>
            <div class="card">
              <h2>治理事件鏈 <span class="pill">%d</span></h2>
              %s
            </div>
            <p class="foot"><a href="/gateway/governance/dashboard">治理控制台</a> ｜ <a href="/gateway/ui">所有任務</a> ｜ <a href="/">首頁</a></p>
          </div>
        </body>
        </html>
        """;
}
