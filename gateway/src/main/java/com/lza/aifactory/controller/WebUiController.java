package com.lza.aifactory.controller;

import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import com.lza.aifactory.service.TaskService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Server-rendered HTML pages for non-technical users: a simple "submit a
 * request" form and a list of all tasks. No build step or JS framework —
 * plain HTML with a tiny fetch() call, consistent with the per-task page in
 * {@link TaskController}.
 */
@RestController
public class WebUiController {
    private final TaskService taskService;

    public WebUiController(TaskService taskService) {
        this.taskService = taskService;
    }

    /** Submission form — the front door for people who don't write code. */
    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return """
            <!doctype html>
            <html lang="zh-Hant">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>AI Factory — 提出一個需求</title>
              <style>
                body{font-family:-apple-system,"Segoe UI",Roboto,"PingFang TC","Microsoft JhengHei",sans-serif;
                     background:#f6f8fa;color:#1f2328;margin:0;padding:40px 16px;}
                .card{max-width:600px;margin:0 auto;background:#fff;border:1px solid #d0d7de;
                      border-radius:14px;padding:28px 32px;box-shadow:0 1px 3px rgba(0,0,0,.06);}
                .mascot{display:block;margin:0 auto 12px;width:96px;height:96px;border-radius:50%;
                        object-fit:cover;border:3px solid #eaeef2;}
                h1{font-size:22px;margin:0 0 4px;text-align:center;}
                .sub{color:#656d76;font-size:14px;margin:0 0 22px;text-align:center;}
                label{display:block;font-weight:600;margin:16px 0 6px;font-size:14px;}
                input[type=text],textarea{width:100%;box-sizing:border-box;padding:10px 12px;
                      border:1px solid #d0d7de;border-radius:8px;font-size:15px;font-family:inherit;}
                textarea{min-height:120px;resize:vertical;}
                .hint{color:#8b949e;font-size:12px;margin-top:4px;}
                .strength,.mode,.project-type{display:flex;gap:8px;margin-top:6px;flex-wrap:wrap;}
                .strength label,.mode label,.project-type label{flex:1;min-width:120px;font-weight:500;border:1px solid #d0d7de;border-radius:8px;
                      padding:10px;text-align:center;cursor:pointer;margin:0;}
                .strength input,.mode input,.project-type input{display:none;}
                .strength input:checked + span,.mode input:checked + span,.project-type input:checked + span{font-weight:700;color:#0969da;}
                .mode label{padding:12px;}
                button{margin-top:24px;width:100%;background:#1f883d;color:#fff;border:0;border-radius:8px;
                      padding:13px;font-size:16px;font-weight:600;cursor:pointer;}
                button:hover{background:#1a7f37;}
                .foot{text-align:center;margin-top:16px;font-size:13px;}
                a{color:#0969da;}
                #err{color:#e5484d;font-size:14px;margin-top:12px;display:none;}
              </style>
            </head>
            <body>
              <div class="card">
                <img class="mascot" src="/mascot.jpg" alt="AI Factory 吉祥物 粉圓">
                <h1>請 AI Factory 幫你做一件事</h1>
                <p class="sub">嗨，我是粉圓 🫧 不管你會不會寫程式——用白話描述想法就好。我會交給你一份<b>可審查的 AI 草稿</b>，最後由你把關、由你決定。我是來幫忙的，不是來取代你的。</p>
                <form id="f" onsubmit="return submitForm(event)">
                  <label>你想做什麼？</label>
                  <div class="mode">
                    <label><input type="radio" name="mode" value="new" checked onchange="onMode()"><span>✨ 做全新的</span></label>
                    <label><input type="radio" name="mode" value="import" onchange="onMode()"><span>📦 改我的檔案</span></label>
                    <label><input type="radio" name="mode" value="existing" onchange="onMode()"><span>🔧 連 git 專案</span></label>
                  </div>
                  <div class="hint">「做全新的」與「改我的檔案」都不需要 git 帳號或金鑰。</div>

                  <div id="uploadRow" style="display:none">
                    <label for="file">上傳你現有的專案（.zip）</label>
                    <input type="file" id="file" accept=".zip">
                    <div class="hint">把你現有的檔案壓成一個 zip 上傳，AI 會在上面改，完成後給你可下載／預覽的成果。</div>
                  </div>

                  <label for="title">標題</label>
                  <input type="text" id="title" required placeholder="例如：結帳頁面加上儲存常用地址">
                  <div class="hint">一句話講清楚要做什麼。</div>

                  <label for="description">詳細描述</label>
                  <textarea id="description" required placeholder="背景、想解決的問題、期待的結果。越具體越好。"></textarea>

                  <label>想要什麼樣的成品？</label>
                  <div class="project-type">
                    <label><input type="radio" name="projectType" value="recommend" checked><span>🔵 智慧推薦</span></label>
                    <label><input type="radio" name="projectType" value="web"><span>📄 簡約網頁</span></label>
                    <label><input type="radio" name="projectType" value="interactive"><span>🕹️ 互動工具</span></label>
                    <label><input type="radio" name="projectType" value="mobile"><span>📱 手機感頁面</span></label>
                    <label><input type="radio" name="projectType" value="backend"><span>⚙️ 純工具</span></label>
                  </div>
                  <div class="hint">如果不確定，選「智慧推薦」由粉圓幫你決定最合適的技術組合。</div>

                  <label>開發強度</label>
                  <div class="strength">
                    <label><input type="radio" name="strength" value="1"><span>⚡ 快速</span></label>
                    <label><input type="radio" name="strength" value="3" checked><span>⚖️ 穩健</span></label>
                    <label><input type="radio" name="strength" value="5"><span>🔬 徹底</span></label>
                  </div>
                  <div class="hint">越徹底會嘗試越多種做法，花的時間也越久。</div>

                  <button type="submit">🚀 開始</button>
                  <div id="err"></div>
                </form>
                <div class="foot"><a href="/gateway/ui">查看所有任務 →</a></div>
              </div>
              <script>
                function onMode(){
                  const m = document.querySelector('input[name=mode]:checked').value;
                  document.getElementById('uploadRow').style.display = (m === 'import') ? 'block' : 'none';
                }
                async function submitForm(e){
                  e.preventDefault();
                  const err = document.getElementById('err');
                  err.style.display='none';
                  const mode = document.querySelector('input[name=mode]:checked').value;
                  const title = document.getElementById('title').value;
                  const description = document.getElementById('description').value;
                  const projectType = document.querySelector('input[name=projectType]:checked').value;
                  const maxAgents = parseInt(document.querySelector('input[name=strength]:checked').value, 10);
                  try{
                    let r;
                    if(mode === 'import'){
                      const f = document.getElementById('file').files[0];
                      if(!f){ throw new Error('請先選擇一個 .zip 檔'); }
                      const fd = new FormData();
                      fd.append('file', f); fd.append('title', title);
                      fd.append('description', description); fd.append('maxAgents', maxAgents);
                      fd.append('projectType', projectType);
                      r = await fetch('/gateway/import', { method:'POST', body: fd });
                    } else {
                      r = await fetch('/gateway/issue', {
                        method:'POST', headers:{'Content-Type':'application/json'},
                        body: JSON.stringify({ source:"web", mode, title, description, maxAgents, projectType })
                      });
                    }
                    if(!r.ok){ const t = await r.json().catch(()=>({})); throw new Error(t.message || ('HTTP '+r.status)); }
                    const rec = await r.json();
                    window.location.href = '/gateway/ui/' + encodeURIComponent(rec.taskId);
                  }catch(ex){ err.textContent = '送出失敗：' + ex.message; err.style.display='block'; }
                }
              </script>
            </body>
            </html>
            """;
    }

    /** All tasks as a sortable, paginated table with timing columns. */
    @GetMapping(value = "/gateway/ui", produces = MediaType.TEXT_HTML_VALUE)
    public String tasks(@RequestParam(defaultValue = "submittedAt") String sort,
                        @RequestParam(defaultValue = "desc") String dir,
                        @RequestParam(defaultValue = "1") int page) {
        final int size = 20;
        final Instant now = Instant.now();
        final boolean desc = !"asc".equalsIgnoreCase(dir);
        final String sortKey = switch (sort) {
            case "duration", "completedAt", "submittedAt" -> sort;
            default -> "submittedAt";
        };
        List<TaskRecord> all = new ArrayList<>(taskService.listTasks());
        all.sort(comparatorFor(sortKey, now, desc));

        int total = all.size();
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) size));
        int p = Math.min(Math.max(1, page), totalPages);
        int from = Math.min((p - 1) * size, total);
        int to = Math.min(from + size, total);

        StringBuilder body = new StringBuilder();
        if (total == 0) {
            body.append("<p class=\"empty\">還沒有任何任務。<a href=\"/\">提出第一個需求 →</a></p>");
        } else {
            body.append("<table class=\"tbl\"><thead><tr>");
            body.append("<th class=\"c-task\">任務</th><th class=\"c-st\">狀態</th>");
            body.append(sortHeader("建立時間", "submittedAt", sortKey, desc));
            body.append(sortHeader("耗時", "duration", sortKey, desc));
            body.append(sortHeader("完成時間", "completedAt", sortKey, desc));
            body.append("<th class=\"c-act\"></th></tr></thead><tbody>");
            for (TaskRecord r : all.subList(from, to)) {
                TaskStatus s = r.status();
                String done = r.completedAt() != null ? relativeTime(r.completedAt(), now) : "—";
                String delBtn = r.terminal()
                        ? "<button class=\"del\" data-task-id=\"" + esc(r.taskId())
                          + "\" onclick=\"delTask(this)\" title=\"清理這個任務\">🗑️</button>"
                        : "";
                body.append("""
                    <tr>
                      <td class="c-task"><a href="/gateway/ui/%s"><span class="emoji">%s</span><span class="t">%s</span></a></td>
                      <td class="c-st"><span class="st">%s</span><span class="bar"><span class="fill" style="width:%d%%"></span></span></td>
                      <td class="c-time" title="%s">%s</td>
                      <td class="c-time">%s</td>
                      <td class="c-time" title="%s">%s</td>
                      <td class="c-act">%s</td>
                    </tr>
                    """.formatted(
                        esc(r.taskId()), s.emoji(), esc(orDash(r.title())),
                        esc(s.displayName()), s.progress(),
                        esc(absolute(r.submittedAt())), esc(relativeTime(r.submittedAt(), now)),
                        esc(durationText(r, now)),
                        esc(absolute(r.completedAt())), esc(done),
                        delBtn));
            }
            body.append("</tbody></table>");
            if (totalPages > 1) {
                String prev = p > 1 ? pageLink(sortKey, desc, p - 1, "← 上一頁")
                        : "<span class=\"pg-dim\">← 上一頁</span>";
                String next = p < totalPages ? pageLink(sortKey, desc, p + 1, "下一頁 →")
                        : "<span class=\"pg-dim\">下一頁 →</span>";
                body.append("<div class=\"pager\">").append(prev)
                        .append("<span class=\"pg-mid\">第 ").append(p).append(" / ").append(totalPages)
                        .append(" 頁（共 ").append(total).append(" 筆）</span>").append(next).append("</div>");
            }
        }
        return """
            <!doctype html>
            <html lang="zh-Hant">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta http-equiv="refresh" content="5">
              <title>AI Factory — 所有任務</title>
              <style>
                body{font-family:-apple-system,"Segoe UI",Roboto,"PingFang TC","Microsoft JhengHei",sans-serif;
                     background:#f6f8fa;color:#1f2328;margin:0;padding:40px 16px;}
                .card{max-width:760px;margin:0 auto;background:#fff;border:1px solid #d0d7de;
                      border-radius:14px;padding:24px 28px;box-shadow:0 1px 3px rgba(0,0,0,.06);}
                .head{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;}
                h1{font-size:20px;margin:0;}
                .acts{display:flex;gap:8px;align-items:center;}
                .home{text-decoration:none;color:#1f2328;background:#f6f8fa;border:1px solid #d0d7de;
                      padding:8px 14px;border-radius:8px;font-size:14px;}
                .home:hover{background:#eef1f4;}
                .new{background:#1f883d;color:#fff;text-decoration:none;padding:8px 14px;border-radius:8px;font-size:14px;}
                .tbl{width:100%%;border-collapse:collapse;font-size:14px;}
                .tbl th,.tbl td{text-align:left;padding:10px 8px;border-top:1px solid #eaeef2;vertical-align:middle;}
                .tbl thead th{border-top:0;font-size:13px;color:#656d76;font-weight:600;white-space:nowrap;}
                .th-sort a{color:#656d76;text-decoration:none;}
                .th-sort.active a{color:#0969da;}
                .c-task a{text-decoration:none;color:#1f2328;display:flex;align-items:center;gap:8px;}
                .c-task .t{font-weight:600;}
                .emoji{font-size:20px;}
                .c-st .st{display:block;color:#656d76;font-size:12px;margin-bottom:4px;white-space:nowrap;}
                .c-st .bar{display:block;width:80px;height:6px;background:#eaeef2;border-radius:999px;overflow:hidden;}
                .c-st .fill{display:block;height:100%%;background:#3b82f6;}
                .c-time{color:#656d76;white-space:nowrap;font-size:13px;}
                .c-act{text-align:right;}
                .del{background:none;border:0;cursor:pointer;font-size:16px;opacity:.6;}
                .del:hover{opacity:1;}
                .pager{display:flex;justify-content:center;align-items:center;gap:14px;margin-top:18px;
                       font-size:14px;color:#656d76;}
                .pg{text-decoration:none;background:#f6f8fa;border:1px solid #d0d7de;padding:6px 12px;
                    border-radius:8px;color:#1f2328;}
                .pg-dim{color:#c0c6cd;}
                .empty{color:#656d76;}
                a{color:#0969da;}
              </style>
            </head>
            <body>
              <div class="card">
                <div class="head"><h1>所有任務</h1><div class="acts"><a class="home" href="/">🏠 返回首頁</a><a class="new" href="/">+ 新需求</a></div></div>
                %s
              </div>
              <script>
                function delTask(el){
                  var id = el.getAttribute('data-task-id');
                  if(!confirm('汪！要粉圓忘記這個任務嗎？\\n會一起清掉這個任務的紀錄與產出檔，這個動作無法復原喔。')) return;
                  fetch('/gateway/tasks/'+encodeURIComponent(id)+'/delete',{method:'POST'})
                    .then(function(r){ if(r.ok){ location.reload(); } else { alert('刪除失敗，請稍後再試'); } })
                    .catch(function(){ alert('刪除失敗，請稍後再試'); });
                }
              </script>
            </body>
            </html>
            """.formatted(body.toString());
    }

    private Comparator<TaskRecord> comparatorFor(String sortKey, Instant now, boolean desc) {
        Comparator<TaskRecord> c = switch (sortKey) {
            case "duration" -> Comparator.comparing((TaskRecord r) -> r.duration(now));
            case "completedAt" -> Comparator.comparing(TaskRecord::completedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
            default -> Comparator.comparing(TaskRecord::submittedAt,
                    Comparator.nullsFirst(Comparator.naturalOrder()));
        };
        return desc ? c.reversed() : c;
    }

    /** A sortable column header; clicking the active column toggles direction. */
    private String sortHeader(String label, String key, String activeKey, boolean desc) {
        boolean active = key.equals(activeKey);
        boolean newDesc = active ? !desc : true;   // new column defaults to desc
        String arrow = active ? (desc ? " ▼" : " ▲") : "";
        String cls = active ? "th-sort active" : "th-sort";
        return "<th class=\"" + cls + "\"><a href=\"/gateway/ui?sort=" + key + "&dir="
                + (newDesc ? "desc" : "asc") + "&page=1\">" + esc(label) + arrow + "</a></th>";
    }

    private String pageLink(String sortKey, boolean desc, int page, String label) {
        return "<a class=\"pg\" href=\"/gateway/ui?sort=" + sortKey + "&dir=" + (desc ? "desc" : "asc")
                + "&page=" + page + "\">" + esc(label) + "</a>";
    }

    private static final DateTimeFormatter ABS_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.of("Asia/Taipei"));

    private String absolute(Instant t) {
        return t == null ? "" : ABS_TIME.format(t);
    }

    /** Friendly relative time ("3 分鐘前"); falls back to absolute past a week. */
    private String relativeTime(Instant t, Instant now) {
        if (t == null) return "—";
        long sec = Math.max(0, Duration.between(t, now).getSeconds());
        if (sec < 60) return "剛剛";
        long min = sec / 60;
        if (min < 60) return min + " 分鐘前";
        long hr = min / 60;
        if (hr < 24) return hr + " 小時前";
        long day = hr / 24;
        if (day < 7) return day + " 天前";
        return absolute(t);
    }

    private String durationText(TaskRecord r, Instant now) {
        String d = humanDuration(r.duration(now));
        return r.terminal() ? d : "已努力 " + d;
    }

    private String humanDuration(Duration d) {
        long sec = Math.max(0, d.getSeconds());
        if (sec < 60) return sec + " 秒";
        long min = sec / 60;
        long remSec = sec % 60;
        if (min < 60) return remSec == 0 ? min + " 分鐘" : min + " 分 " + remSec + " 秒";
        long hr = min / 60;
        long remMin = min % 60;
        return remMin == 0 ? hr + " 小時" : hr + " 小時 " + remMin + " 分";
    }

    private String orDash(String v) {
        return (v == null || v.isBlank()) ? "—" : v;
    }

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
