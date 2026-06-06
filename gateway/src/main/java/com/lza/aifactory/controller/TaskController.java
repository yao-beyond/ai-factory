package com.lza.aifactory.controller;

import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import com.lza.aifactory.service.EtaService;
import com.lza.aifactory.service.TaskService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

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
        boolean done = s == TaskStatus.COMPLETED || s == TaskStatus.FAILED;
        boolean failed = s == TaskStatus.FAILED;
        String refresh = done ? "" : "<meta http-equiv=\"refresh\" content=\"3\">";
        String barColor = failed ? "#e5484d" : (s == TaskStatus.COMPLETED ? "#30a46c" : "#3b82f6");

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
                .result h2{font-size:16px;margin:0 0 8px;}
                .result p{font-size:14px;color:#3d4248;margin:6px 0;}
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
                  <div>🕒 更新時間：%s</div>
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
                esc(r.updatedAt() == null ? "—" : r.updatedAt().toString()));
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

    /** Plain-language "what now" block shown when the task finishes. */
    private String resultBlock(TaskRecord r) {
        TaskStatus s = r.status();
        if (s == TaskStatus.COMPLETED) {
            String button = (r.prUrl() != null && !r.prUrl().isBlank())
                    ? "<a class=\"btn\" href=\"" + esc(r.prUrl()) + "\" target=\"_blank\" rel=\"noopener\">查看 AI 完成的成果草案 →</a>"
                    : "<p>成果連結整理中，稍候重新整理即可看到。</p>";
            return """
                <div class="result ok">
                  <h2>✅ AI 已經完成你要求的工作了</h2>
                  %s
                  <p class="ask">下一步：請工程師（或有合併權限的同事）按下「合併」讓變更正式生效。
                  不確定的話，把這個頁面連結傳給工程師就好。AI 不會自己改動正式版本，你可以放心。</p>
                </div>
                """.formatted(button);
        }
        if (s == TaskStatus.FAILED) {
            return """
                <div class="result bad">
                  <h2>⚠️ 這次沒有順利完成</h2>
                  <p class="ask">需要請工程師看一下。把這個頁面的連結傳給工程師即可，他們能看到技術細節。</p>
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
