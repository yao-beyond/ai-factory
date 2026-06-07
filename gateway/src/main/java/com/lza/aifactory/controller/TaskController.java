package com.lza.aifactory.controller;

import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
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

    public TaskController(TaskService taskService, EtaService etaService) {
        this.taskService = taskService;
        this.etaService = etaService;
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
    public ResponseEntity<?> confirm(@PathVariable String taskId) {
        return decide(taskId, true);
    }

    /** Cancel the task while it waits at the confirmation gate. */
    @PostMapping("/cancel/{taskId}")
    public ResponseEntity<?> cancel(@PathVariable String taskId) {
        return decide(taskId, false);
    }

    private ResponseEntity<?> decide(String taskId, boolean approve) {
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
            taskService.writeConfirmMarker(taskId, approve);
        } catch (IOException e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "io_error", "message", "could not record decision"));
        }
        return ResponseEntity.ok(Map.of("taskId", taskId, "decision", approve ? "approved" : "cancelled"));
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
        boolean awaiting = s == TaskStatus.AWAITING_CONFIRMATION;
        boolean done = s == TaskStatus.COMPLETED || failed;
        // Hold still while waiting for the user's decision; auto-refresh otherwise.
        String refresh = (done || awaiting) ? "" : "<meta http-equiv=\"refresh\" content=\"3\">";
        String barColor = failed ? "#e5484d"
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
                .eta{font-size:14px;color:#1f2328;margin-top:10px;text-align:center;}
                .eta b{color:#0969da;}
                .result{margin-top:22px;padding:18px;border-radius:10px;}
                .result.ok{background:#e9f7ef;border:1px solid #b7ebc9;}
                .result.bad{background:#fdeceb;border:1px solid #f5c4c2;}
                .result.await{background:#fff8e6;border:1px solid #f0d999;}
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
                .ask{font-size:14px;line-height:1.6;}
                .btn{display:inline-block;margin:12px 0 4px;background:#1f883d;color:#fff;
                     text-decoration:none;padding:11px 18px;border-radius:8px;font-weight:600;}
                .meta{margin-top:22px;font-size:13px;color:#656d76;border-top:1px solid #eaeef2;padding-top:16px;}
                .meta div{margin:4px 0;}
                a{color:#0969da;}
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
                <div class="meta">
                  <div>📝 你的需求：%s</div>
                  <div>🕒 更新時間：%s（UTC+8）</div>
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
                resultBlock(r),
                etaBlock(r),
                esc(orDash(r.title())),
                esc(formatLocalTime(r.updatedAt())));
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

    /** Plain-language "what now" block shown when the task finishes (or waits). */
    private String resultBlock(TaskRecord r) {
        TaskStatus s = r.status();
        if (s == TaskStatus.AWAITING_CONFIRMATION) {
            String plan = taskService.readPlanSummary(r.taskId())
                    .map(md -> "<div class=\"sumtitle\">AI 打算這樣做：</div>" + renderSummaryHtml(md))
                    .orElse("<p>正在整理計畫摘要，稍候重新整理即可看到。</p>");
            String id = esc(r.taskId());
            return """
                <div class="result await">
                  <h2>📝 請先確認開工方向</h2>
                  %s
                  <div class="confirm-actions">
                    <button class="btn ok" onclick="decide('confirm')">✅ 確認開工</button>
                    <button class="btn no" onclick="decide('cancel')">❌ 取消</button>
                  </div>
                  <p class="ask">確認後 AI 才會開始開發。方向不對就按「取消」，補充需求後重新送出即可。</p>
                </div>
                <script>
                  function decide(a){
                    var btns = document.querySelectorAll('.confirm-actions .btn');
                    btns.forEach(function(b){ b.disabled = true; });
                    fetch('/gateway/'+a+'/%s',{method:'POST'})
                      .then(function(r){
                        // 409 = the task already moved on (e.g. a double click after
                        // it started). That's not an error — just show its progress.
                        if(r.ok || r.status === 409){ location.reload(); return; }
                        alert('操作失敗，請稍後重試');
                        btns.forEach(function(b){ b.disabled = false; });
                      })
                      .catch(function(){
                        alert('操作失敗，請稍後重試');
                        btns.forEach(function(b){ b.disabled = false; });
                      });
                  }
                </script>
                """.formatted(plan, id);
        }
        if (s == TaskStatus.COMPLETED) {
            String summary = taskService.readSummary(r.taskId())
                    .map(md -> "<div class=\"sumtitle\">AI 做了這些變更：</div>" + renderSummaryHtml(md))
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
                return """
                    <div class="result ok">
                      <h2>✅ 你的全新專案做好了</h2>
                      %s
                      %s
                      %s
                      <p class="ask">%s想保留或進一步調整，下載 zip 後交給工程師、或之後用「線上發布」功能即可。</p>
                    </div>
                    """.formatted(summary, preview, dl,
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
                  <p class="ask">下一步：請工程師（或有合併權限的同事）按下「合併」讓變更正式生效。
                  不確定的話，把這個頁面連結傳給工程師就好。AI 不會自己改動正式版本，你可以放心。</p>
                </div>
                """.formatted(summary, button);
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

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
