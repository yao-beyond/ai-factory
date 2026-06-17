package com.lza.aifactory.controller;

import com.lza.aifactory.governance.GovernanceDashboardService;
import com.lza.aifactory.governance.GovernanceDashboardService.DashboardData;
import com.lza.aifactory.governance.GovernanceDashboardService.Incident;
import com.lza.aifactory.governance.GovernanceDashboardService.OverrideEntry;
import com.lza.aifactory.governance.GovernanceDashboardService.PendingApproval;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Server-rendered governance interception dashboard — a security control
 * surface, not a CI log. Frames blocked actions as "Safe Catch" (the system
 * caught it for you), surfaces tasks awaiting human delivery approval with
 * one-click approve/reject, lists active overrides, and a policy friction map.
 * Plain HTML in the same style as WebUiController. See
 * docs/design/governance-runtime.md §3.3.
 */
@RestController
public class GovernanceDashboardController {

    private final GovernanceDashboardService dashboard;
    // Whether an internal secret gates the human approve/reject endpoints. We
    // expose only this BOOLEAN to the page (never the secret) so the one-click
    // buttons know to ask the operator for the secret instead of silently 403ing.
    // Always true now: the controller enforces an operator secret on approve/
    // reject/override unconditionally (auto-generated when AIF_INTERNAL_SECRET is
    // unset), so the page must always prompt for it.
    private final boolean secretRequired;

    public GovernanceDashboardController(GovernanceDashboardService dashboard) {
        this.dashboard = dashboard;
        this.secretRequired = true;
    }

    @GetMapping(value = "/gateway/governance/dashboard", produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() {
        DashboardData d = dashboard.aggregate();

        StringBuilder pending = new StringBuilder();
        if (d.pending().isEmpty()) {
            pending.append("<p class=\"empty\">目前沒有等待核准的交付。</p>");
        } else {
            for (PendingApproval p : d.pending()) {
                String id = esc(p.taskId());
                // taskId rides DATA ATTRIBUTES (HTML-escaped), never a JS string
                // literal — so an odd restored-task dir name can't break out into JS.
                pending.append("""
                    <div class="row pend">
                      <div class="who"><a href="/gateway/ui/%s">%s</a><span class="t">%s</span></div>
                      <div class="acts">
                        <button class="ok" data-task-id="%s" data-action="approve" onclick="decide(this)">✅ 核准交付</button>
                        <button class="no" data-task-id="%s" data-action="reject" onclick="decide(this)">❌ 退回</button>
                        <a class="gep" href="/gateway/governance/%s/explorer">🔍 證據鏈</a>
                        <a class="gep" href="/gateway/governance/%s/gep">📄 證據包</a>
                      </div>
                    </div>
                    """.formatted(id, id, esc(orDash(p.title())), id, id, id, id));
            }
        }

        StringBuilder feed = new StringBuilder();
        if (d.safeCatches().isEmpty()) {
            feed.append("<p class=\"empty\">尚無攔截紀錄。</p>");
        } else {
            for (Incident i : d.safeCatches()) {
                feed.append("""
                    <div class="row catch">
                      <span class="badge">🛡️ Safe Catch</span>
                      <span class="k"><a href="/gateway/ui/%s">%s</a></span>
                      <span class="d">%s</span>
                      <a class="gep" href="/gateway/governance/%s/explorer">🔍 證據鏈</a>
                      <span class="ts">%s</span>
                    </div>
                    """.formatted(esc(i.taskId()), esc(i.taskId()), esc(orDash(i.detail())),
                        esc(i.taskId()), esc(orDash(i.occurredAt()))));
            }
        }

        StringBuilder friction = new StringBuilder();
        if (d.frictionMap().isEmpty()) {
            friction.append("<p class=\"empty\">尚無政策衝突。</p>");
        } else {
            for (Map.Entry<String, Integer> e : d.frictionMap().entrySet()) {
                friction.append("<div class=\"frow\"><span class=\"r\">").append(esc(e.getKey()))
                        .append("</span><span class=\"n\">").append(e.getValue()).append("</span></div>");
            }
        }

        StringBuilder ovr = new StringBuilder();
        if (d.overrides().isEmpty()) {
            ovr.append("<p class=\"empty\">沒有生效中的例外。</p>");
        } else {
            for (OverrideEntry o : d.overrides()) {
                ovr.append("""
                    <div class="row ovr">
                      <span class="k">%s</span>
                      <span class="d">%s（%s，到期 %s）</span>
                    </div>
                    """.formatted(esc(o.taskId()), esc(orDash(o.rationale())),
                        esc(orDash(o.ticket())), esc(orDash(o.expiry()))));
            }
        }

        String tamperBanner = d.anyTampered()
                ? "<div class=\"tamper\">⚠️ 偵測到治理事件紀錄的雜湊鏈異常，請人工調查。</div>"
                : "";

        return PAGE.formatted(
                tamperBanner,
                d.pending().size(), pending.toString(),
                d.safeCatches().size(), feed.toString(),
                ovr.toString(),
                friction.toString(),
                d.governedTasks(),
                secretRequired);
    }

    private static String orDash(String v) {
        return (v == null || v.isBlank()) ? "—" : v;
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
          <title>AI Factory — 治理控制台</title>
          <style>
            body{font-family:-apple-system,"Segoe UI",Roboto,"PingFang TC","Microsoft JhengHei",sans-serif;
                 background:#0d1117;color:#e6edf3;margin:0;padding:32px 16px;}
            .wrap{max-width:880px;margin:0 auto;}
            h1{font-size:22px;margin:0 0 4px;}
            .sub{color:#8b949e;font-size:13px;margin:0 0 22px;}
            .card{background:#161b22;border:1px solid #30363d;border-radius:12px;padding:18px 20px;margin:0 0 18px;}
            .card h2{font-size:15px;margin:0 0 12px;display:flex;justify-content:space-between;align-items:center;}
            .pill{font-size:12px;background:#21262d;border:1px solid #30363d;border-radius:999px;padding:2px 10px;color:#8b949e;}
            .row{display:flex;align-items:center;gap:10px;padding:8px 0;border-top:1px solid #21262d;font-size:13px;flex-wrap:wrap;}
            .row:first-of-type{border-top:0;}
            .badge{background:#1f6feb;color:#fff;font-size:11px;font-weight:700;padding:2px 8px;border-radius:6px;white-space:nowrap;}
            .catch .badge{background:#238636;}
            .row .k{font-weight:600;} .row .k a,.who a{color:#58a6ff;text-decoration:none;}
            .row .d{color:#c9d1d9;flex:1;min-width:160px;} .row .ts{color:#6e7681;font-size:11px;}
            .pend .who{flex:1;display:flex;flex-direction:column;} .pend .who .t{color:#8b949e;font-size:12px;}
            .acts{display:flex;gap:8px;align-items:center;}
            .acts button{border:0;border-radius:7px;padding:7px 12px;font-size:13px;font-weight:600;cursor:pointer;}
            .acts .ok{background:#238636;color:#fff;} .acts .no{background:#da3633;color:#fff;}
            .acts .gep{color:#58a6ff;text-decoration:none;font-size:13px;}
            .frow{display:flex;justify-content:space-between;padding:6px 0;border-top:1px solid #21262d;font-size:13px;}
            .frow:first-of-type{border-top:0;} .frow .r{color:#c9d1d9;font-family:ui-monospace,Menlo,monospace;font-size:12px;}
            .frow .n{color:#f0883e;font-weight:700;}
            .empty{color:#6e7681;font-size:13px;margin:4px 0;}
            .tamper{background:#3d1d1d;border:1px solid #f85149;color:#ffb4ab;border-radius:10px;padding:12px 14px;margin:0 0 18px;font-size:14px;}
            .foot{color:#6e7681;font-size:12px;margin-top:8px;text-align:center;}
            a.home{color:#58a6ff;}
          </style>
        </head>
        <body>
          <div class="wrap">
            <h1>🏭 治理控制台</h1>
            <p class="sub">這裡是 AI 變更的控制面：被攔下的動作、等你核准的交付、生效中的例外。攔截不是錯誤，是系統替你擋下了風險。</p>
            %s
            <div class="card">
              <h2>🧑‍⚖️ 等待你核准的交付 <span class="pill">%d</span></h2>
              %s
            </div>
            <div class="card">
              <h2>🛡️ Safe Catch — 被擋下的動作 <span class="pill">%d</span></h2>
              %s
            </div>
            <div class="card">
              <h2>🔓 生效中的例外（override）</h2>
              %s
            </div>
            <div class="card">
              <h2>📊 政策衝突熱點</h2>
              %s
            </div>
            <p class="foot">受治理任務數：%d ｜ <a class="home" href="/gateway/ui">所有任務</a> ｜ <a class="home" href="/">首頁</a></p>
          </div>
          <script>
            // Only a BOOLEAN is emitted here, never the secret itself.
            var SECRET_REQUIRED = %b;
            var operatorSecret = null;   // entered by the operator, kept client-side
            function decide(btn){
              // Read the id from the data attribute (never interpolated into JS),
              // so an AI/restored task id can't break out of a JS string.
              var id = btn.getAttribute('data-task-id');
              var action = btn.getAttribute('data-action');
              var verb = action === 'approve' ? '核准交付' : '退回';
              if(!confirm('確定要「' + verb + '」這個任務嗎？')) return;
              var headers = {};
              if(SECRET_REQUIRED){
                // This deployment gates approvals on an internal secret. Ask the
                // operator for it (the secret is the operator credential here);
                // workspace code that lacks it still cannot self-approve.
                if(!operatorSecret){
                  operatorSecret = prompt('此部署已啟用內部密鑰，請輸入以授權此次核准：');
                  if(!operatorSecret){ return; }
                }
                headers['X-AIF-Internal'] = operatorSecret;
              }
              fetch('/gateway/governance/' + encodeURIComponent(id) + '/' + action, { method:'POST', headers: headers })
                .then(function(r){ if(r.ok){ location.reload(); }
                  else if(r.status===403){ operatorSecret = null; alert('內部密鑰不正確或未授權，請重試。'); }
                  else { alert('操作失敗，請稍後再試'); } })
                .catch(function(){ alert('操作失敗，請稍後再試'); });
            }
          </script>
        </body>
        </html>
        """;
}
