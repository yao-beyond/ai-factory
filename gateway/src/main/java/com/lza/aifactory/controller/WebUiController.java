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
                .upload-link{margin:0;font-weight:normal;color:#0969da;cursor:pointer;font-size:13px;}
                .upload-link:hover{text-decoration:underline;}
                button{margin-top:24px;width:100%;background:#1f883d;color:#fff;border:0;border-radius:8px;
                      padding:13px;font-size:16px;font-weight:600;cursor:pointer;}
                button:hover{background:#1a7f37;}
                .foot{text-align:center;margin-top:16px;font-size:13px;}
                a{color:#0969da;}
                #err{color:#e5484d;font-size:14px;margin-top:12px;display:none;}
                .discover-entry{display:flex;align-items:center;gap:12px;text-decoration:none;color:#1f2328;
                      background:#fff8e6;border:1px solid #f0d999;border-radius:12px;padding:14px 16px;margin:0 0 20px;}
                .discover-entry:hover{background:#fff3d6;}
                .de-emoji{font-size:26px;}
                .de-text{flex:1;font-size:14px;line-height:1.4;}
                .de-sub{color:#8a6d3b;font-size:12px;}
                .de-arrow{font-size:20px;color:#bf8700;}
                .prefill-banner{display:none;background:#e9f7ef;border:1px solid #b7ebc9;border-radius:10px;
                      padding:12px 14px;margin:0 0 18px;font-size:13px;color:#1a7f37;}
              </style>
            </head>
            <body>
              <div class="card">
                <img class="mascot" src="/mascot.jpg" alt="AI Factory 吉祥物 粉圓">
                <h1>請 AI Factory 幫你做一件事</h1>
                <p class="sub">嗨，我是粉圓 🫧 不管你會不會寫程式——用白話描述想法就好。我會交給你一份<b>可審查的 AI 草稿</b>，最後由你把關、由你決定。我是來幫忙的，不是來取代你的。</p>
                <a class="discover-entry" href="/gateway/discovery">
                  <span class="de-emoji">🤔</span>
                  <span class="de-text"><b>還不知道要做什麼？</b><br><span class="de-sub">沒關係，回答兩個小問題，粉圓陪你一起找靈感 🫧</span></span>
                  <span class="de-arrow">→</span>
                </a>
                <div class="prefill-banner" id="prefillBanner"></div>
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

                  <div style="display:flex;justify-content:space-between;align-items:center;margin:16px 0 6px;">
                    <label for="description" style="margin:0;">詳細描述</label>
                    <label for="descFile" class="upload-link">📎 上傳描述檔</label>
                    <input type="file" id="descFile" style="display:none" accept=".txt,.md,.markdown,.json,.yml,.yaml">
                  </div>
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
                document.getElementById('descFile').addEventListener('change', function(e){
                  var file = e.target.files[0];
                  if(!file){ return; }
                  if(file.size > 256 * 1024){
                    alert('汪！這個檔案太大了，粉圓讀不動（限 256KB 內的文字檔）。');
                    e.target.value = ''; return;
                  }
                  var reader = new FileReader();
                  reader.onload = function(ev){
                    var raw = String(ev.target.result || '');
                    // Looks binary? (a NUL byte) — bail out, we only want text.
                    if(raw.indexOf('\\u0000') >= 0){
                      alert('汪！這看起來不是純文字檔，粉圓只看得懂文字喔。'); e.target.value=''; return;
                    }
                    // Normalise newlines + drop control chars (keep \\n and \\t); cap length.
                    var text = raw.replace(/\\r\\n/g,'\\n').replace(/\\r/g,'\\n')
                                  .replace(/[\\u0000-\\u0008\\u000B\\u000C\\u000E-\\u001F\\u007F]/g,'');
                    var capped = false;
                    if(text.length > 32000){ text = text.slice(0, 32000); capped = true; }
                    var area = document.getElementById('description');
                    if(area.value.trim().length > 0){
                      if(!confirm('汪！詳細描述已經有內容了，要用檔案內容取代它嗎？')){ e.target.value=''; return; }
                    }
                    area.value = text;
                    area.focus();
                    alert('汪！已經幫你把「' + file.name + '」的內容填進去囉！'
                          + (capped ? '（內容有點長，先幫你保留前面的部分）' : ''));
                    e.target.value = '';
                  };
                  reader.onerror = function(){ alert('汪！讀取檔案失敗了，請再試一次。'); e.target.value=''; };
                  reader.readAsText(file);
                });
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
                        body: JSON.stringify({ source:"web", mode, title, description, maxAgents, projectType,
                          discoveryCardId: window.__discoveryCardId || null })
                      });
                    }
                    if(!r.ok){ const t = await r.json().catch(()=>({})); throw new Error(t.message || ('HTTP '+r.status)); }
                    const rec = await r.json();
                    window.location.href = '/gateway/ui/' + encodeURIComponent(rec.taskId);
                  }catch(ex){ err.textContent = '送出失敗：' + ex.message; err.style.display='block'; }
                }
                // Prefill from a discovery session (set in sessionStorage by /gateway/discovery).
                (function(){
                  try{
                    var raw = sessionStorage.getItem('aifactory_discovery');
                    if(!raw){ return; }
                    sessionStorage.removeItem('aifactory_discovery');
                    var d = JSON.parse(raw);
                    window.__discoveryCardId = d.cardId || null;
                    var mn = document.querySelector('input[name=mode][value=new]');
                    if(mn){ mn.checked = true; onMode(); }
                    if(d.title){ document.getElementById('title').value = d.title; }
                    if(d.draftRequest){ document.getElementById('description').value = d.draftRequest; }
                    if(d.formProjectType){
                      var pt = document.querySelector('input[name=projectType][value="' + d.formProjectType + '"]');
                      if(pt){ pt.checked = true; }
                    }
                    var b = document.getElementById('prefillBanner');
                    if(b){ b.textContent = '🫧 粉圓已經幫你把想法填好了，看一下、改一下都可以，然後就能開工！'; b.style.display='block'; }
                  }catch(e){}
                })();
              </script>
            </body>
            </html>
            """;
    }

    /**
     * Discovery flow for users with no idea what to build: two friendly questions
     * (audience x intent), then concrete starter cards from the backend-owned
     * library, an honest note for "collect" cards, an optional name, and a
     * deterministic hand-off that prefills the homepage form. See
     * docs/design/discovery-stage.md. The card library and finalize live in
     * DiscoveryController; this page only drives the UX.
     */
    @GetMapping(value = "/gateway/discovery", produces = MediaType.TEXT_HTML_VALUE)
    public String discovery() {
        return """
            <!doctype html>
            <html lang="zh-Hant">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <title>AI Factory — 一起想想要做什麼</title>
              <style>
                body{font-family:-apple-system,"Segoe UI",Roboto,"PingFang TC","Microsoft JhengHei",sans-serif;
                     background:#f6f8fa;color:#1f2328;margin:0;padding:40px 16px;}
                .card{max-width:600px;margin:0 auto;background:#fff;border:1px solid #d0d7de;
                      border-radius:14px;padding:28px 32px;box-shadow:0 1px 3px rgba(0,0,0,.06);}
                .mascot{display:block;margin:0 auto 12px;width:88px;height:88px;border-radius:50%;
                        object-fit:cover;border:3px solid #eaeef2;}
                h1{font-size:21px;margin:0 0 4px;text-align:center;}
                .sub{color:#656d76;font-size:14px;margin:0 0 22px;text-align:center;line-height:1.5;}
                .q{font-weight:700;font-size:15px;margin:22px 0 10px;}
                .chips{display:flex;gap:8px;flex-wrap:wrap;}
                .chip{flex:1;min-width:130px;border:1px solid #d0d7de;border-radius:10px;padding:12px;
                      text-align:center;cursor:pointer;font-size:14px;background:#fff;transition:.1s;}
                .chip:hover{border-color:#0969da;}
                .chip.sel{border-color:#0969da;background:#ddf4ff;font-weight:700;color:#0969da;}
                .note{background:#fff8e6;border:1px solid #f0d999;border-radius:10px;padding:12px 14px;
                      font-size:13px;color:#8a6d3b;line-height:1.5;margin:16px 0 4px;}
                .cards{display:flex;flex-direction:column;gap:10px;margin-top:6px;}
                .pcard{border:1px solid #d0d7de;border-radius:12px;padding:14px 16px;cursor:pointer;background:#fff;}
                .pcard:hover{border-color:#1f883d;}
                .pcard.sel{border-color:#1f883d;background:#e9f7ef;}
                .pcard .pt{font-weight:700;font-size:15px;}
                .pcard .pl{font-size:13px;color:#656d76;margin-top:4px;line-height:1.5;}
                .pcard .tag{display:inline-block;margin-top:8px;font-size:11px;color:#8a6d3b;
                      background:#fff8e6;border:1px solid #f0d999;border-radius:6px;padding:2px 8px;}
                .handoff-confirm{display:none;background:#fff8e6;border:1px solid #f0d999;border-radius:10px;
                      padding:12px 14px;margin-top:12px;font-size:13px;color:#8a6d3b;line-height:1.5;}
                .handoff-confirm label{display:flex;gap:8px;align-items:flex-start;cursor:pointer;margin-top:8px;font-weight:600;}
                label.fld{display:block;font-weight:600;margin:18px 0 6px;font-size:14px;}
                input[type=text]{width:100%;box-sizing:border-box;padding:10px 12px;border:1px solid #d0d7de;
                      border-radius:8px;font-size:15px;font-family:inherit;}
                .hint{color:#8b949e;font-size:12px;margin-top:4px;}
                .go{margin-top:22px;width:100%;background:#1f883d;color:#fff;border:0;border-radius:8px;
                      padding:13px;font-size:16px;font-weight:600;cursor:pointer;}
                .go:disabled{background:#bcd9c4;cursor:not-allowed;}
                .hidden{display:none;}
                .foot{text-align:center;margin-top:16px;font-size:13px;}
                a{color:#0969da;}
                #err{color:#e5484d;font-size:14px;margin-top:12px;display:none;}
              </style>
            </head>
            <body>
              <div class="card">
                <img class="mascot" src="/mascot.jpg" alt="AI Factory 吉祥物 粉圓">
                <h1>沒關係，我們一起想 🫧</h1>
                <p class="sub">不用先想好要做什麼。回答兩個小問題，我挑幾個現成的點子給你看看，<br>看到喜歡的「就是這個！」再開始就好。</p>

                <div class="q">1. 這個東西主要是給誰用的呢？</div>
                <div class="chips" id="qAudience">
                  <div class="chip" data-k="customers">給客人的</div>
                  <div class="chip" data-k="self">我自己·家人用的</div>
                  <div class="chip" data-k="coworkers">給同事夥伴的</div>
                </div>

                <div class="q">2. 你最希望它幫你做什麼？</div>
                <div class="chips" id="qIntent">
                  <div class="chip" data-k="showcase">美美地展示</div>
                  <div class="chip" data-k="collect">幫忙收資料 / 記錄</div>
                  <div class="chip" data-k="automate">生活省時小工具</div>
                </div>

                <div id="collectNote" class="note hidden">💡 小提醒：這類「收資料」的成品，目前是<b>客人填完、自己複製傳給你</b>——網站不會自動收到、也不會幫你存起來。如果你要的是「自動收件」，那要等之後的進階功能喔，我們先做能馬上用的版本。</div>

                <div id="cardsWrap" class="hidden">
                  <div class="q" id="cardsTitle">我幫你挑了幾個，看看哪個最接近你腦袋裡的樣子？</div>
                  <div class="cards" id="cards"></div>
                  <div class="foot"><a href="#" id="noneFit">這些好像都不是我想的…我自己打字描述 →</a></div>
                </div>

                <div id="handoffConfirm" class="handoff-confirm">
                  你選的是「填完自己傳」型的成品。
                  <label><input type="checkbox" id="handoffOk"> 我了解：訪客填完要自己複製傳給我，網站不會自動收到、也不會留存資料。</label>
                </div>

                <div id="finalWrap" class="hidden">
                  <label class="fld" for="prodName">要幫這個小成品取個名字嗎？（選填）</label>
                  <input type="text" id="prodName" maxlength="60" placeholder="例如：美霞的私房菜菜單、阿芬的省錢帳本">
                  <div class="hint">取了名字，它就成了「你的東西」🫧 想不到也沒關係。</div>
                  <label class="fld" for="prodNote">有沒有想補充的小細節？（選填）</label>
                  <input type="text" id="prodNote" maxlength="120" placeholder="例如：底色用溫暖的橘色、想多一個電話欄位">
                  <div class="hint">這只會影響用字與細節，不會多加複雜功能。</div>
                  <button class="go" id="goBtn" disabled>就用這個！幫我準備好 →</button>
                  <div id="err"></div>
                </div>

                <div class="foot"><a href="/">← 回首頁自己打字</a></div>
              </div>
              <script>
                var state = { audience:null, intent:null, cardId:null, isHandoff:false };
                function pick(group, k, el){
                  group.querySelectorAll('.chip').forEach(function(c){ c.classList.remove('sel'); });
                  el.classList.add('sel');
                }
                var qA = document.getElementById('qAudience');
                var qI = document.getElementById('qIntent');
                qA.querySelectorAll('.chip').forEach(function(c){
                  c.onclick = function(){ pick(qA, c.dataset.k, c); state.audience = c.dataset.k; maybeLoadCards(); };
                });
                qI.querySelectorAll('.chip').forEach(function(c){
                  c.onclick = function(){ pick(qI, c.dataset.k, c); state.intent = c.dataset.k; maybeLoadCards(); };
                });
                function esc(s){ var d=document.createElement('div'); d.textContent = s==null?'':String(s); return d.innerHTML; }
                function resetDownstream(){
                  state.cardId = null; state.isHandoff = false;
                  document.getElementById('handoffConfirm').style.display = 'none';
                  document.getElementById('handoffOk').checked = false;
                  document.getElementById('finalWrap').classList.add('hidden');
                  document.getElementById('goBtn').disabled = true;
                }
                function maybeLoadCards(){
                  if(!state.audience || !state.intent){ return; }
                  resetDownstream();
                  var showCollectNote = (state.audience === 'customers' && state.intent === 'collect');
                  document.getElementById('collectNote').classList.toggle('hidden', !showCollectNote);
                  fetch('/gateway/discovery/cards?audience=' + encodeURIComponent(state.audience)
                        + '&intent=' + encodeURIComponent(state.intent))
                    .then(function(r){ return r.json(); })
                    .then(function(data){ renderCards(data.cards || []); })
                    .catch(function(){ renderCards([]); });
                }
                function renderCards(cards){
                  var wrap = document.getElementById('cardsWrap');
                  var box = document.getElementById('cards');
                  box.innerHTML = '';
                  if(!cards.length){
                    box.innerHTML = '<div class="pl">這個組合我這邊還沒有現成的點子 🫧 你可以換個選擇，或直接自己打字描述。</div>';
                    wrap.classList.remove('hidden');
                    return;
                  }
                  cards.forEach(function(c){
                    var el = document.createElement('div');
                    el.className = 'pcard';
                    var tag = c.handoff ? '<span class="tag">整理好，由對方自行傳送</span>' : '';
                    el.innerHTML = '<div class="pt">' + esc(c.title) + '</div><div class="pl">' + esc(c.oneLiner) + '</div>' + tag;
                    el.onclick = function(){ selectCard(c, el); };
                    box.appendChild(el);
                  });
                  wrap.classList.remove('hidden');
                }
                function selectCard(c, el){
                  document.querySelectorAll('.pcard').forEach(function(p){ p.classList.remove('sel'); });
                  el.classList.add('sel');
                  state.cardId = c.id; state.isHandoff = !!c.handoff;
                  var hc = document.getElementById('handoffConfirm');
                  hc.style.display = state.isHandoff ? 'block' : 'none';
                  document.getElementById('finalWrap').classList.remove('hidden');
                  updateGo();
                  document.getElementById('finalWrap').scrollIntoView({behavior:'smooth', block:'nearest'});
                }
                document.getElementById('handoffOk').onchange = updateGo;
                function updateGo(){
                  var ok = !!state.cardId && (!state.isHandoff || document.getElementById('handoffOk').checked);
                  document.getElementById('goBtn').disabled = !ok;
                }
                document.getElementById('noneFit').onclick = function(e){ e.preventDefault(); window.location.href = '/'; };
                document.getElementById('goBtn').onclick = function(){
                  var err = document.getElementById('err'); err.style.display='none';
                  var body = { cardId: state.cardId,
                               name: document.getElementById('prodName').value,
                               note: document.getElementById('prodNote').value };
                  fetch('/gateway/discovery/finalize', { method:'POST',
                      headers:{'Content-Type':'application/json'}, body: JSON.stringify(body) })
                    .then(function(r){ if(!r.ok){ return r.json().then(function(t){ throw new Error(t.message || ('HTTP '+r.status)); }); } return r.json(); })
                    .then(function(res){
                      sessionStorage.setItem('aifactory_discovery', JSON.stringify({
                        cardId: res.cardId, title: res.title, draftRequest: res.draftRequest,
                        formProjectType: res.formProjectType }));
                      window.location.href = '/';
                    })
                    .catch(function(ex){ err.textContent = '出了點問題：' + ex.message; err.style.display='block'; });
                };
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
