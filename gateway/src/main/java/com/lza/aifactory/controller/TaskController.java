package com.lza.aifactory.controller;

import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import com.lza.aifactory.service.CostEstimateService;
import com.lza.aifactory.service.EtaService;
import com.lza.aifactory.service.TaskService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/gateway")
public class TaskController {
    private final TaskService taskService;
    private final EtaService etaService;
    private final CostEstimateService costEstimateService;

    public TaskController(TaskService taskService, EtaService etaService,
                          CostEstimateService costEstimateService) {
        this.taskService = taskService;
        this.etaService = etaService;
        this.costEstimateService = costEstimateService;
    }

    @GetMapping("/status/{taskId}")
    public ResponseEntity<TaskRecord> status(@PathVariable String taskId) {
        return taskService.findStatus(taskId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/tasks")
    public List<TaskRecord> list() {
        return taskService.listTasks();
    }

    /**
     * Live "what is the AI doing right now" feed: the tail of the task's run.log.
     * The status page polls this so a user can watch real progress instead of a
     * frozen progress bar. Always 200 (empty lines when there's no output yet),
     * so the poller never has to special-case a missing log.
     */
    @GetMapping("/activity/{taskId}")
    public ResponseEntity<Map<String, Object>> activity(@PathVariable String taskId) {
        List<String> lines = taskService.recentActivity(taskId, 24);
        return ResponseEntity.ok(Map.of("taskId", taskId, "lines", lines));
    }

    /** Delete a finished task (record + its work dir). Only terminal tasks. */
    @PostMapping("/tasks/{taskId}/delete")
    public ResponseEntity<?> deleteTask(@PathVariable String taskId) {
        boolean ok = taskService.deleteTask(taskId);
        if (!ok) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "not_deletable",
                            "message", "只能刪除已完成或已結束的任務"));
        }
        return ResponseEntity.ok(Map.of("taskId", taskId, "deleted", true));
    }

    /** Stop a running task. Kills its process tree and marks it CANCELLED. */
    @PostMapping("/tasks/{taskId}/abort")
    public ResponseEntity<?> abortTask(@PathVariable String taskId) {
        return taskService.abortTask(taskId)
                ? ResponseEntity.ok(Map.of("taskId", taskId, "aborted", true))
                : ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "not_running", "message", "這個任務不是進行中，無法中止"));
    }

    /** Soft-pause a running task (takes effect at the next stage boundary). */
    @PostMapping("/tasks/{taskId}/pause")
    public ResponseEntity<?> pauseTask(@PathVariable String taskId) {
        return taskService.pauseTask(taskId)
                ? ResponseEntity.ok(Map.of("taskId", taskId, "paused", true))
                : ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "not_pausable", "message", "這個任務無法暫停"));
    }

    /** Resume a soft-paused task. */
    @PostMapping("/tasks/{taskId}/resume")
    public ResponseEntity<?> resumeTask(@PathVariable String taskId) {
        return taskService.resumeTask(taskId)
                ? ResponseEntity.ok(Map.of("taskId", taskId, "resumed", true))
                : ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "not_resumable", "message", "這個任務無法繼續"));
    }

    /** Download the generated project (local/new-project mode) as a zip. */
    @GetMapping("/result/{taskId}")
    public ResponseEntity<Resource> result(@PathVariable String taskId) {
        // Only new-project tasks may be downloaded; never serve an existing-repo
        // task's artifacts even if a stale result.zip is present.
        if (!taskService.isNewProjectResult(taskId)) {
            return ResponseEntity.notFound().build();
        }
        Path zip = taskService.resultZip(taskId).orElse(null);
        if (zip == null) {
            return ResponseEntity.notFound().build();
        }
        Resource body = new FileSystemResource(zip);
        String filename = "ai-factory-" + taskId.replaceAll("[^A-Za-z0-9._-]", "-") + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(body);
    }

    /**
     * Browser preview of a generated web project (local/new-project mode), so a
     * non-technical user can just look at the result without any dev environment.
     * Serves files from the project tree (index.html by default), read-only and
     * protected against path traversal.
     */
    @GetMapping("/preview/{taskId}/**")
    public ResponseEntity<Resource> preview(@PathVariable String taskId, HttpServletRequest request) {
        String uri = request.getRequestURI();
        String prefix = "/gateway/preview/" + taskId;
        String rel = uri.length() > prefix.length() ? uri.substring(prefix.length()) : "";
        rel = java.net.URLDecoder.decode(rel, java.nio.charset.StandardCharsets.UTF_8).replaceFirst("^/+", "");
        Path file = taskService.resolvePreviewFile(taskId, rel).orElse(null);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(guessContentType(file)))
                .body(new FileSystemResource(file));
    }

    private String guessContentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js") || name.endsWith(".mjs")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".json")) return "application/json; charset=utf-8";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".ico")) return "image/x-icon";
        try {
            String probed = Files.probeContentType(file);
            if (probed != null) return probed;
        } catch (IOException ignored) {
        }
        return "text/plain; charset=utf-8";
    }

    /** Approve the plan and let the pipeline start building. */
    @PostMapping("/confirm/{taskId}")
    public ResponseEntity<?> confirm(@PathVariable String taskId, HttpServletRequest request) {
        // Form-body params: the (possibly edited) plan note can be long/multi-line,
        // so it comes in the POST body, not the query string.
        return decide(taskId, true, request.getParameter("option"), request.getParameter("note"));
    }

    /** Cancel the task while it waits at the confirmation gate. */
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(@PathVariable String taskId) {
        return decide(taskId, false, null, null);
    }

    private ResponseEntity<?> decide(String taskId, boolean approve, String option, String note) {
        TaskRecord record = taskService.findStatus(taskId).orElse(null);
        if (record == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("error", "not_found", "message", "unknown task"));
        }
        if (record.status() != TaskStatus.AWAITING_CONFIRMATION) {
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("error", "conflict",
                            "message", "task is not awaiting confirmation (status=" + record.status().name() + ")"));
        }
        try {
            taskService.writeConfirmMarker(taskId, approve, option, note);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "io_error", "message", "could not record decision"));
        }
        return ResponseEntity.ok(Map.of("taskId", taskId, "decision", approve ? "approved" : "cancelled", "option", option == null ? "" : option));
    }

    /**
     * Human-friendly status page. Shows a progress bar and plain-language status
     * so non-technical users can follow a task without reading JSON or logs.
     * Auto-refreshes until the task is done.
     */
    @GetMapping(value = "/ui/{taskId}", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> ui(@PathVariable String taskId) {
        return taskService.findStatus(taskId)
                .map(record -> ResponseEntity.ok(renderPage(record)))
                .orElseGet(() -> ResponseEntity.status(404).body(renderNotFound(taskId)));
    }

    private String renderPage(TaskRecord r) {
        TaskStatus s = r.status();
        boolean failed = s == TaskStatus.FAILED;
        boolean cancelled = s == TaskStatus.CANCELLED;
        boolean awaiting = s == TaskStatus.AWAITING_CONFIRMATION;
        // Terminal states (COMPLETED/FAILED/CANCELLED) are "done" — they must not
        // be treated as running, or the page would show a live feed for a stopped
        // task.
        boolean done = r.terminal();
        boolean running = !done && !awaiting;
        // No meta-refresh: while running we drive updates with JS (a live activity
        // feed plus reload-on-stage-change), which a 3s full-page refresh would
        // otherwise wipe. Done/awaiting pages are static anyway.
        String refresh = "";
        String barColor = failed ? "#e5484d"
                : cancelled ? "#8b949e"
                : (s == TaskStatus.COMPLETED ? "#30a46c" : (awaiting ? "#d29922" : "#3b82f6"));

        return """
            <!doctype html>
            <html lang="zh-Hant">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              %s
              <title>AI Factory — %s</title>
              <style>
                body{font-family:-apple-system,"Segoe UI",Roboto,"PingFang TC","Microsoft JhengHei",sans-serif;
                     background:#f6f8fa;color:#1f2328;margin:0;padding:40px 16px;}
                .card{max-width:560px;margin:0 auto;background:#fff;border:1px solid #d0d7de;
                      border-radius:14px;padding:28px 32px;box-shadow:0 1px 3px rgba(0,0,0,.06);}
                .task{font-size:13px;color:#656d76;letter-spacing:.04em;}
                .emoji{font-size:42px;line-height:1;margin:10px 0 6px;}
                .headline{font-size:22px;font-weight:600;margin:0 0 4px;}
                .msg{color:#656d76;margin:6px 0 20px;font-size:14px;}
                .bar{height:12px;background:#eaeef2;border-radius:999px;overflow:hidden;}
                .fill{height:100%%;width:%d%%;background:%s;transition:width .5s ease;}
                .pct{font-size:12px;color:#656d76;margin-top:6px;text-align:right;}
                .steps{display:flex;flex-wrap:wrap;gap:6px;margin:14px 0 2px;justify-content:center;}
                .ms{font-size:12px;border-radius:999px;padding:3px 10px;border:1px solid #d0d7de;
                    color:#8b949e;background:#f6f8fa;white-space:nowrap;}
                .ms.done{color:#1a7f37;border-color:#b7ebc9;background:#e9f7ef;}
                .ms.now{color:#0969da;border-color:#9cd1ff;background:#ddf4ff;font-weight:700;}
                .eta{font-size:14px;color:#1f2328;margin-top:10px;text-align:center;}
                .eta b{color:#0969da;}
                .result{margin-top:22px;padding:18px;border-radius:10px;}
                .result.ok{background:#e9f7ef;border:1px solid #b7ebc9;}
                .result.bad{background:#fdeceb;border:1px solid #f5c4c2;}
                .result.await{background:#fff8e6;border:1px solid #f0d999;}
                .result.stopped{background:#f6f8fa;border:1px solid #d0d7de;}
                .confirm-actions{display:flex;gap:10px;margin:14px 0 4px;}
                .confirm-actions .btn{flex:1;text-align:center;border:0;border-radius:8px;padding:12px;
                       font-size:15px;font-weight:600;cursor:pointer;}
                .btn.ok{background:#1f883d;color:#fff;}
                .btn.no{background:#eaeef2;color:#3d4248;}
                .result h2{font-size:16px;margin:0 0 8px;}
                .result p{font-size:14px;color:#3d4248;margin:6px 0;}
                .sumtitle{font-weight:600;font-size:14px;margin:10px 0 4px;}
                .summary{background:#fff;border:1px solid #cfe9da;border-radius:8px;padding:10px 14px;margin:6px 0 10px;}
                .summary h4{font-size:14px;margin:8px 0 4px;}
                .summary ul{margin:4px 0;padding-left:20px;}
                .summary li,.summary p{font-size:14px;color:#3d4248;margin:3px 0;}
                .selreport{margin:6px 0 10px;}
                .selreport>summary{cursor:pointer;font-size:14px;font-weight:600;color:#1a7f4e;}
                .ask{font-size:14px;line-height:1.6;}
                .btn{display:inline-block;margin:12px 0 4px;background:#1f883d;color:#fff;
                     text-decoration:none;padding:11px 18px;border-radius:8px;font-weight:600;}
                .meta{margin-top:22px;font-size:13px;color:#656d76;border-top:1px solid #eaeef2;padding-top:16px;}
                .nav{display:flex;gap:10px;margin-top:18px;}
                .nav a{flex:1;text-align:center;text-decoration:none;border:1px solid #d0d7de;
                       border-radius:8px;padding:10px;font-size:14px;color:#1f2328;background:#f6f8fa;}
                .nav a:hover{background:#eef1f4;}
                .controls{display:flex;gap:10px;margin-top:18px;}
                .ctl{flex:1;border:0;border-radius:8px;padding:11px;font-size:14px;font-weight:600;cursor:pointer;}
                .ctl.pause{background:#fb8500;color:#fff;}
                .ctl.resume{background:#1f883d;color:#fff;}
                .ctl.abort{background:#f3b0b0;color:#7a1f1f;}
                .ctl.abort:hover{background:#ec9a9a;}
                .meta div{margin:4px 0;}
                a{color:#0969da;}
                .activity{margin-top:22px;}
                .act-head{font-size:13px;color:#656d76;margin-bottom:8px;display:flex;align-items:center;gap:8px;}
                .live{color:#fff;background:#e5484d;font-size:11px;font-weight:700;
                      padding:1px 8px;border-radius:999px;animation:blink 1.4s ease-in-out infinite;}
                @keyframes blink{0%%,100%%{opacity:1}50%%{opacity:.35}}
                .feed{background:#0d1117;color:#c9d1d9;font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;
                      font-size:12px;line-height:1.55;padding:14px 16px;border-radius:10px;height:240px;
                      overflow-y:auto;white-space:pre-wrap;word-break:break-word;margin:0;border:1px solid #30363d;}
                .feed .ph{color:#8b949e;}
              </style>
            </head>
            <body>
              <div class="card">
                <div class="task">任務 %s</div>
                <div class="emoji">%s</div>
                <h1 class="headline">%s</h1>
                <p class="msg">%s</p>
                <div class="bar"><div class="fill"></div></div>
                <div class="pct">%d%%</div>
                %s
                %s
                %s
                %s
                %s
                <div class="meta">
                  <div>📝 你的需求：%s</div>
                  <div>🕒 更新時間：%s（UTC+8）</div>
                </div>
                <div class="nav">
                  <a href="/">🏠 返回首頁</a>
                  <a href="/gateway/ui">📋 所有任務</a>
                </div>
              </div>
            </body>
            </html>
            """.formatted(
                refresh,
                esc(r.taskId()),
                s.progress(), barColor,
                esc(r.taskId()),
                s.emoji(),
                esc(s.displayName()),
                esc(friendlyMessage(r)),
                s.progress(),
                milestonesBlock(r),
                resultBlock(r),
                etaBlock(r),
                controlsBlock(r),
                activityBlock(r, running),
                esc(orDash(r.title())),
                esc(formatLocalTime(r.updatedAt())));
    }

    /**
     * Plain-language factory milestones: where the task sits on the production
     * line, as a row of step chips (done ✓ / current ● / upcoming ○). Driven by
     * the status's canonical progress so it can never disagree with the bar.
     * Hidden for FAILED/CANCELLED — the result block already explains those.
     */
    private String milestonesBlock(TaskRecord r) {
        TaskStatus s = r.status();
        if (s == TaskStatus.FAILED || s == TaskStatus.CANCELLED) return "";
        record Step(String label, int progress) {}
        List<Step> steps = List.of(
                new Step("收到需求", 5),
                new Step("構思方案", 25),
                new Step("等你確認", 35),
                new Step("平行開發", 45),
                new Step("評選最佳", 65),
                new Step("安全審查", 85),
                new Step("修正收尾", 95),
                new Step("完成", 100));
        int p = s.progress();
        boolean completed = s == TaskStatus.COMPLETED;
        // Current = the furthest step the pipeline has reached (PAUSED's 50 maps
        // back onto the development step it paused inside of).
        int current = 0;
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).progress() <= p) current = i;
        }
        StringBuilder sb = new StringBuilder("<div class=\"steps\">");
        for (int i = 0; i < steps.size(); i++) {
            String cls;
            String mark;
            if (completed || i < current) {
                cls = "ms done"; mark = "✓";
            } else if (i == current) {
                cls = "ms now"; mark = "●";
            } else {
                cls = "ms todo"; mark = "○";
            }
            sb.append("<span class=\"").append(cls).append("\">")
              .append(mark).append(' ').append(esc(steps.get(i).label())).append("</span>");
        }
        return sb.append("</div>").toString();
    }

    /** Friendly "estimated time remaining" line, only while the task is running. */
    private String etaBlock(TaskRecord r) {
        return etaService.estimateRemaining(r)
                .map(d -> "<div class=\"eta\">⏳ 預計還要：<b>" + humanizeEta(d) + "</b></div>")
                .orElse("");
    }

    private String humanizeEta(Duration d) {
        long seconds = Math.max(0, d.getSeconds());
        if (seconds < 60) return "少於 1 分鐘";
        long minutes = (seconds + 59) / 60; // round up
        if (minutes < 60) return "約 " + minutes + " 分鐘";
        long hours = (minutes + 59) / 60;
        return "約 " + hours + " 小時";
    }

    /**
     * Pause / resume / abort controls for a live task. Running tasks can be
     * soft-paused or stopped; a paused task can be resumed or stopped; terminal
     * and awaiting-confirmation tasks show nothing here.
     */
    private String controlsBlock(TaskRecord r) {
        TaskStatus s = r.status();
        if (r.terminal() || s == TaskStatus.AWAITING_CONFIRMATION) return "";
        String id = esc(r.taskId());
        boolean paused = s == TaskStatus.PAUSED;
        String first = paused
                ? "<button class=\"ctl resume\" onclick=\"ctl('resume')\">▶️ 繼續工作</button>"
                : "<button class=\"ctl pause\" onclick=\"ctl('pause')\">⏸️ 休息一下</button>";
        return """
            <div class="controls">
              %s
              <button class="ctl abort" onclick="ctl('abort')">🛑 停止泡泡</button>
            </div>
            <script>
              function ctl(action){
                if(action === 'abort' && !confirm('現在停下來的話，之前的努力就白費囉～確定要叫粉圓收工嗎？')) return;
                fetch('/gateway/tasks/%s/'+action, {method:'POST'})
                  .then(function(r){ if(r.ok || r.status===409){ location.reload(); } else { alert('操作失敗，請稍後再試'); } })
                  .catch(function(){ alert('操作失敗，請稍後再試'); });
              }
            </script>
            """.formatted(first, id);
    }

    /**
     * Live "what is the AI doing right now" panel. Only rendered while the task
     * is running; it polls /gateway/activity for the run.log tail (smooth, in
     * place) and /gateway/status to reload the page when the stage changes.
     */
    private String activityBlock(TaskRecord r, boolean running) {
        if (!running) return "";
        String id = esc(r.taskId());
        String status = r.status().name();
        return """
            <div class="activity">
              <div class="act-head">🔧 AI 即時執行紀錄 <span class="live">● LIVE</span></div>
              <pre id="feed" class="feed"><span class="ph">正在接上 AI 的工作畫面…</span></pre>
            </div>
            <script>
              (function(){
                var id = "%s";
                var feed = document.getElementById('feed');
                var lastStatus = "%s";
                function esc(s){return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');}
                function loadActivity(){
                  fetch('/gateway/activity/'+id).then(function(r){return r.json();}).then(function(d){
                    if(!d || !d.lines || !d.lines.length) return;
                    var atBottom = feed.scrollTop + feed.clientHeight >= feed.scrollHeight - 28;
                    feed.innerHTML = d.lines.map(esc).join('\\n');
                    if(atBottom){ feed.scrollTop = feed.scrollHeight; }
                  }).catch(function(){});
                }
                function checkStatus(){
                  fetch('/gateway/status/'+id).then(function(r){return r.json();}).then(function(d){
                    if(d && d.status && d.status !== lastStatus){ location.reload(); }
                  }).catch(function(){});
                }
                loadActivity();
                setInterval(loadActivity, 2000);
                setInterval(checkStatus, 3000);
              })();
            </script>
            """.formatted(id, status);
    }

    /** Star rows (開發速度/畫面順暢/未來擴充) for an option's ratings map, if any. */
    private String ratingsHtml(Object ratingsObj) {
        if (!(ratingsObj instanceof Map<?, ?> ratings) || ratings.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<div class=\"opt-ratings\">");
        appendRating(sb, "開發速度", ratings.get("speed"));
        appendRating(sb, "畫面順暢", ratings.get("smoothness"));
        appendRating(sb, "未來擴充", ratings.get("scalability"));
        return sb.append("</div>").toString();
    }

    private void appendRating(StringBuilder sb, String label, Object val) {
        if (val == null) return;
        int n;
        if (val instanceof Number num) {
            n = num.intValue();
        } else {
            try {
                n = Integer.parseInt(String.valueOf(val).trim());
            } catch (NumberFormatException e) {
                return;
            }
        }
        n = Math.max(0, Math.min(5, n));
        String stars = "★★★★★".substring(0, n) + "☆☆☆☆☆".substring(0, 5 - n);
        sb.append("<div class=\"opt-rating\"><span>").append(esc(label))
          .append("</span><span class=\"stars\">").append(stars).append("</span></div>");
    }

    private String resultBlock(TaskRecord r) {
        TaskStatus s = r.status();
        if (s == TaskStatus.AWAITING_CONFIRMATION) {
            // The plan is shown as an EDITABLE draft — the user can rewrite the
            // direction before approving. Pre-filled with the AI's plain-language
            // summary; esc() makes a "</textarea>" in the text harmless.
            String planText = taskService.readPlanSummary(r.taskId()).orElse("");
            String plan = """
                <div class="sumtitle">AI 幫你擬定的執行計畫草稿：</div>
                <textarea id="planDraft" class="plan-draft" placeholder="（如果把計畫都刪光，粉圓會不知道要做什麼喔…）">%s</textarea>
                <textarea id="planOriginal" style="display:none">%s</textarea>
                <div class="plan-tools"><button type="button" class="reset" onclick="resetPlan()">🔄 讓粉圓重寫（還原初版）</button></div>
                <p class="hint">💡 覺得哪裡不對、或想加點新點子？直接在上面改寫就可以囉！粉圓會照著你最後寫的內容去開工。</p>
                """.formatted(esc(planText), esc(planText));
            
            List<Map<String, Object>> options = taskService.readOptions(r.taskId());
            StringBuilder optionsHtml = new StringBuilder();
            if (!options.isEmpty()) {
                optionsHtml.append("<div class=\"sumtitle\">粉圓幫你想了幾個做法，挑一個喜歡的就好 🫧（不確定就用推薦的，最穩）</div>");
                optionsHtml.append("<div class=\"option-cards\">");
                // Pre-select the recommended option (fall back to the first valid
                // one) so the hidden field matches the highlighted card. Values are
                // coerced defensively — options.json is AI-generated.
                String defaultId = "";
                for (Map<String, Object> opt : options) {
                    String id = str(opt.get("id"));
                    if (id.isBlank()) continue;   // can't be selected; skip
                    String title = str(opt.get("title"));
                    String desc = str(opt.get("description"));
                    Object recVal = opt.get("recommended");
                    boolean recommended = Boolean.TRUE.equals(recVal)
                            || "true".equalsIgnoreCase(String.valueOf(recVal));
                    if (defaultId.isEmpty()) defaultId = id;
                    if (recommended) defaultId = id;
                    optionsHtml.append("""
                        <div class="opt-card %s" data-opt-id="%s" onclick="selectOption(this)">
                          %s
                          <div class="opt-title">%s</div>
                          <div class="opt-desc">%s</div>
                          %s
                        </div>
                        """.formatted(
                            recommended ? "selected" : "",
                            esc(id),
                            recommended ? "<div class=\"opt-badge\">👍 粉圓推薦</div>" : "",
                            esc(title),
                            esc(desc),
                            ratingsHtml(opt.get("ratings"))
                        ));
                }
                optionsHtml.append("</div>");
                optionsHtml.append("<input type=\"hidden\" id=\"selectedOption\" value=\"%s\">"
                        .formatted(esc(defaultId)));
            }

            // Honest "price of admission" before the user approves: a rough token
            // range derived from the agent count and plan size. No fabricated
            // dollar figures — pricing depends on the user's own AI plan.
            int agents = taskService.readMaxAgents(r.taskId());
            CostEstimateService.TokenRange cost =
                    costEstimateService.estimate(agents, planText.length());
            String costHtml = """
                <div class="cost">🪙 預估 AI 用量：約 <b>%d ～ %d 萬</b> tokens
                  <div class="cost-sub">tokens 是 AI 的工作量單位——%d 位 AI 工程師會用它來讀計畫、寫程式、跑測試。
                  這是開工前的粗估，實際消耗會依任務複雜度與執行的測試量彈性調整。訂閱制方案通常只是消耗每月額度；
                  用 API 金鑰計費的話，費用請參考你的 AI 供應商定價。</div>
                </div>
                """.formatted(cost.lowWan(), cost.highWan(), agents);

            String id = esc(r.taskId());
            return """
                <style>
                  .option-cards{display:flex;gap:12px;margin:12px 0;flex-wrap:wrap;}
                  .opt-card{flex:1;min-width:200px;border:2px solid #eaeef2;border-radius:12px;
                            padding:16px;cursor:pointer;position:relative;transition:all .2s;}
                  .opt-card:hover{border-color:#d0d7de;background:#fcfcfd;}
                  .opt-card.selected{border-color:#0969da;background:#f0f7ff;}
                  .opt-badge{position:absolute;top:-10px;right:10px;background:#0969da;color:#fff;
                             font-size:11px;font-weight:700;padding:2px 8px;border-radius:999px;}
                  .opt-title{font-weight:700;font-size:15px;margin-bottom:6px;color:#1f2328;}
                  .opt-desc{font-size:13px;color:#656d76;line-height:1.5;}
                  .opt-ratings{margin-top:10px;display:flex;flex-direction:column;gap:3px;}
                  .opt-rating{display:flex;justify-content:space-between;font-size:12px;color:#656d76;}
                  .opt-rating .stars{color:#f5a623;letter-spacing:1px;}
                  .plan-draft{width:100%%;box-sizing:border-box;min-height:140px;resize:vertical;
                              border:1.5px dashed #d0a3c7;border-radius:10px;padding:12px 14px;
                              font:inherit;font-size:14px;line-height:1.6;color:#1f2328;background:#fffdfe;}
                  .plan-draft:focus{outline:none;border:1.5px solid #d6409f;background:#fff;}
                  .plan-tools{margin:6px 0 2px;text-align:right;}
                  .plan-tools .reset{background:#fce7f3;color:#9d174d;border:0;border-radius:8px;
                                     padding:6px 12px;font-size:13px;cursor:pointer;}
                  .plan-tools .reset:hover{background:#fbcfe8;}
                  .hint{font-size:13px;color:#656d76;margin:6px 0 0;}
                  .cost{background:#fffbeb;border:1px solid #f0d999;border-radius:10px;
                        padding:10px 14px;margin:12px 0 4px;font-size:14px;color:#1f2328;}
                  .cost-sub{font-size:12px;color:#8a6d3b;margin-top:4px;line-height:1.5;}
                </style>
                <div class="result await">
                  <h2>📝 粉圓想跟你對一下開工方向！</h2>
                  %s
                  %s
                  %s
                  <div class="confirm-actions">
                    <button class="btn ok" onclick="decide('confirm')">✅ 就照這樣開工！</button>
                    <button class="btn no" onclick="decide('cancel')">❌ 先不做了</button>
                  </div>
                  <p class="ask">按下開工後，粉圓就會照著你上面寫的計畫去做。方向不對也可以「先不做了」，補充想法後重新送出。</p>
                </div>
                <script>
                  function selectOption(el){
                    document.querySelectorAll('.opt-card').forEach(function(c){ c.classList.remove('selected'); });
                    el.classList.add('selected');
                    // Read the id from the data attribute (never interpolated into
                    // JS), so an AI-generated id can't break out of a JS string.
                    document.getElementById('selectedOption').value = el.getAttribute('data-opt-id');
                  }
                  function resetPlan(){
                    var d = document.getElementById('planDraft');
                    var o = document.getElementById('planOriginal');
                    if(d && o){ d.value = o.value; d.focus(); }
                  }
                  function decide(a){
                    var btns = document.querySelectorAll('.confirm-actions .btn');
                    function reenable(){ btns.forEach(function(b){ b.disabled = false; }); }
                    var body = new URLSearchParams();
                    if(a === 'confirm'){
                      var draft = document.getElementById('planDraft');
                      var note = draft ? draft.value.trim() : '';
                      if(draft && !note){ alert('哎呀！計畫不能是空的，不然粉圓會迷路喔 😵‍💫'); return; }
                      var opt = document.getElementById('selectedOption');
                      if(opt){ body.set('option', opt.value); }
                      body.set('note', note);
                    }
                    btns.forEach(function(b){ b.disabled = true; });
                    fetch('/gateway/'+a+'/%s', {
                        method:'POST',
                        headers:{'Content-Type':'application/x-www-form-urlencoded;charset=UTF-8'},
                        body: body
                      })
                      .then(function(r){
                        if(r.ok || r.status === 409){ location.reload(); return; }
                        alert('操作失敗，請稍後重試'); reenable();
                      })
                      .catch(function(){ alert('操作失敗，請稍後重試'); reenable(); });
                  }
                </script>
                """.formatted(plan, optionsHtml.toString(), costHtml, id);
        }
        if (s == TaskStatus.COMPLETED) {
            String summary = taskService.readSummary(r.taskId())
                    .map(md -> "<div class=\"sumtitle\">AI 做了這些變更：</div>" + renderSummaryHtml(md))
                    .orElse("");
            // Evidence-based selection report: collapsed by default so the page
            // stays friendly, but the "why this version won" audit trail is one
            // click away (transparency over black-box selection).
            String selection = taskService.readSelectionReport(r.taskId())
                    .map(md -> "<details class=\"selreport\"><summary>🏆 AI 選手比稿評選報告（為什麼選這一版）</summary>"
                            + renderSummaryHtml(md) + "</details>")
                    .orElse("");
            // New-project (local) result: a downloadable project, no git/PR wording.
            // Gate on the authoritative mode, not the result.zip artifact.
            if (taskService.isNewProjectResult(r.taskId())) {
                // Only link the download when the zip really exists, so we never
                // render a button that 404s.
                String dl = taskService.resultZip(r.taskId()).isPresent()
                        ? "<a class=\"btn\" href=\"/gateway/result/" + esc(r.taskId()) + "\">⬇️ 下載你的專案（zip）</a>"
                        : "<p>成果整理中，稍候重新整理即可下載。</p>";
                // If it's a web project (has index.html), offer a one-click browser preview.
                String preview = taskService.hasPreview(r.taskId())
                        ? "<a class=\"btn\" href=\"/gateway/preview/" + esc(r.taskId()) + "/\" target=\"_blank\" rel=\"noopener\">👀 線上預覽成果</a>"
                        : "";
                // Advertise the plain-language guided tour — but only when this
                // task's deliverable actually contains one (older tasks don't).
                String explainerNote = taskService.hasExplainer(r.taskId())
                        ? "<p class=\"ask\">📖 隨附一份 <b>EXPLAINER.md</b>——粉圓為你準備的白話導覽。解壓縮後請先閱讀它，了解 AI 幫你做了哪些改動！</p>"
                        : "";
                return """
                    <div class="result ok">
                      <h2>✅ 你的全新專案做好了</h2>
                      %s
                      %s
                      %s
                      %s
                      %s
                      <p class="ask">%s想保留或進一步調整，下載 zip 後交給工程師、或之後用「線上發布」功能即可。</p>
                    </div>
                    """.formatted(summary, selection, preview, dl, explainerNote,
                        preview.isEmpty() ? "" : "點「線上預覽」就能直接在瀏覽器看成果。");
            }
            // Existing-repo result: a reviewable pull request.
            String button = (r.prUrl() != null && !r.prUrl().isBlank())
                    ? "<a class=\"btn\" href=\"" + esc(r.prUrl()) + "\" target=\"_blank\" rel=\"noopener\">查看 AI 完成的成果草案 →</a>"
                    : "<p>成果連結整理中，稍候重新整理即可看到。</p>";
            return """
                <div class="result ok">
                  <h2>✅ AI 已經完成你要求的工作了</h2>
                  %s
                  %s
                  %s
                  <p class="ask">下一步：請工程師（或有合併權限的同事）按下「合併」讓變更正式生效。
                  不確定的話，把這個頁面連結傳給工程師就好。AI 不會自己改動正式版本，你可以放心。</p>
                </div>
                """.formatted(summary, selection, button);
        }
        if (s == TaskStatus.FAILED) {
            String msg = orDash(r.message());
            // Surface install hints (e.g. missing AI CLI) directly to the user.
            boolean isInstallHint = msg.startsWith("請先安裝");
            String detail = isInstallHint
                    ? "<p class=\"ask\">" + esc(msg) + "</p>"
                    : "<p class=\"ask\">需要請工程師看一下。把這個頁面的連結傳給工程師即可，他們能看到技術細節。</p>";
            return """
                <div class="result bad">
                  <h2>⚠️ 這次沒有順利完成</h2>
                  %s
                </div>
                """.formatted(detail);
        }
        if (s == TaskStatus.CANCELLED) {
            return """
                <div class="result stopped">
                  <h2>🛑 已停止</h2>
                  <p class="ask">這個任務已經停止了，沒有產生任何變更，放心。需要的話，重新提出一個需求就好。</p>
                </div>
                """;
        }
        return "";
    }

    private String renderNotFound(String taskId) {
        return """
            <!doctype html>
            <html lang="zh-Hant"><head><meta charset="utf-8">
            <title>找不到任務</title>
            <style>body{font-family:sans-serif;text-align:center;padding:80px 16px;color:#1f2328;}</style>
            </head><body>
              <div style="font-size:42px">🔍</div>
              <h1>找不到任務 %s</h1>
              <p style="color:#656d76">請確認任務編號是否正確。</p>
            </body></html>
            """.formatted(esc(taskId));
    }

    private String friendlyMessage(TaskRecord r) {
        // The emoji + plain-language status (headline) and the result block carry
        // the meaning. Keep this sub-line empty rather than leak internal text
        // like "spawning 3 dev agents" to non-technical users.
        return "";
    }

    /** Minimal, safe markdown → HTML for the change summary (headings + bullets). */
    private String renderSummaryHtml(String md) {
        StringBuilder sb = new StringBuilder("<div class=\"summary\">");
        boolean inList = false;
        for (String raw : md.split("\\R")) {
            String line = raw.strip();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (inList) { sb.append("</ul>"); inList = false; }
                sb.append("<h4>").append(esc(line.replaceAll("^#+\\s*", ""))).append("</h4>");
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!inList) { sb.append("<ul>"); inList = true; }
                sb.append("<li>").append(esc(line.substring(2).strip())).append("</li>");
            } else {
                if (inList) { sb.append("</ul>"); inList = false; }
                sb.append("<p>").append(esc(line)).append("</p>");
            }
        }
        if (inList) sb.append("</ul>");
        return sb.append("</div>").toString();
    }

    private static final DateTimeFormatter LOCAL_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Taipei"));

    private String formatLocalTime(java.time.Instant t) {
        return t == null ? "—" : LOCAL_TIME.format(t);
    }

    private String orDash(String v) {
        return (v == null || v.isBlank()) ? "—" : v;
    }

    /** Null-safe coercion for AI-generated option fields that may not be strings. */
    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
