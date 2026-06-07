package com.lza.aifactory.controller;

import com.lza.aifactory.dto.TaskRecord;
import com.lza.aifactory.dto.TaskStatus;
import com.lza.aifactory.service.TaskService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

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
                <p class="sub">嗨，我是粉圓 🫧 用白話描述你要什麼就好，不需要懂程式。完成後會給你一個可審查的成果。</p>
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

    /** All tasks, newest activity surfaced as friendly rows. */
    @GetMapping(value = "/gateway/ui", produces = MediaType.TEXT_HTML_VALUE)
    public String tasks() {
        List<TaskRecord> all = taskService.listTasks();
        StringBuilder rows = new StringBuilder();
        if (all.isEmpty()) {
            rows.append("<p class=\"empty\">還沒有任何任務。<a href=\"/\">提出第一個需求 →</a></p>");
        } else {
            rows.append("<div class=\"list\">");
            for (TaskRecord r : all) {
                TaskStatus s = r.status();
                rows.append("""
                    <a class="row" href="/gateway/ui/%s">
                      <span class="emoji">%s</span>
                      <span class="info"><span class="t">%s</span><span class="st">%s</span></span>
                      <span class="bar"><span class="fill" style="width:%d%%"></span></span>
                    </a>
                    """.formatted(esc(r.taskId()), s.emoji(), esc(orDash(r.title())),
                        esc(s.displayName()), s.progress()));
            }
            rows.append("</div>");
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
                .card{max-width:640px;margin:0 auto;background:#fff;border:1px solid #d0d7de;
                      border-radius:14px;padding:24px 28px;box-shadow:0 1px 3px rgba(0,0,0,.06);}
                .head{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px;}
                h1{font-size:20px;margin:0;}
                .new{background:#1f883d;color:#fff;text-decoration:none;padding:8px 14px;border-radius:8px;font-size:14px;}
                .row{display:flex;align-items:center;gap:14px;padding:14px 8px;border-top:1px solid #eaeef2;
                     text-decoration:none;color:inherit;}
                .row:hover{background:#f6f8fa;}
                .emoji{font-size:24px;}
                .info{flex:1;display:flex;flex-direction:column;}
                .t{font-weight:600;}
                .st{color:#656d76;font-size:13px;}
                .bar{width:90px;height:8px;background:#eaeef2;border-radius:999px;overflow:hidden;}
                .fill{display:block;height:100%%;background:#3b82f6;}
                .empty{color:#656d76;}
                a{color:#0969da;}
              </style>
            </head>
            <body>
              <div class="card">
                <div class="head"><h1>所有任務</h1><a class="new" href="/">+ 新需求</a></div>
                %s
              </div>
            </body>
            </html>
            """.formatted(rows.toString());
    }

    private String orDash(String v) {
        return (v == null || v.isBlank()) ? "—" : v;
    }

    private String esc(String v) {
        if (v == null) return "";
        return v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
