<p align="center">
  <img src="../mascot.jpg" alt="AI Factory 吉祥物：粉圓 🫧" width="140"/>
</p>

# AI Factory Demo ②：當 AI 開始幫 AI 做安全審查

> 一份可直接貼上 LinkedIn 的 demo 文件 — 主題：**改既有系統 × 雙 AI 協作開發**

---

## 📣 LinkedIn 貼文（可直接複製）

**「如果你讓 AI 寫程式，誰來幫 AI 檢查安全漏洞？」**

這是我開發開源專案 **AI Factory（粉圓軟體工廠）** 時最核心的思考。

除了能從零生成新專案，AI Factory 也能 **改動你既有的系統** — 你用白話描述需求，它會自動分析、開發，並開出一個 **草稿 Pull Request** 給工程師審核，完全融入現有的 Git 工作流，不破壞任何流程。

但真正讓我興奮的是開發這座工廠的 **幕後**：

我用了 **Claude Code + Codex 雙模型協作** —
Claude 負責高效率的邏輯開發，Codex 扮演冷靜的「**最終把關者**」做 stop-time security review。

結果很驚人：Codex 在收尾時 **連續抓出 6 個連我都漏掉的真實安全漏洞**（symlink 逃逸、私有 repo 外洩、權限繞過…），我逐一修好、補上回歸測試才放行。

> 💡「最好的安全性，不是預防錯誤，而是建立一套能相互制衡、自我修正的系統。」
> 💡「AI 開發的未來，不在於單一模型多強，而在於多代理人（multi-agent）協作的深度。」

\#GenerativeAI #軟體安全 #MultiAgent #Claude #Codex #AI工程師

💬 你會放心讓 AI 直接提交 PR 改動你的正式環境嗎？你更傾向哪種審查機制？

---

## 🔧 它怎麼運作（改既有專案模式）

```text
你的一句需求（網頁表單 / Jira / Telegram）
   ↓
🧠 規劃（codex 產出實作計畫 + 白話計畫摘要）
   ↓
📝 開工前確認 ── 你看白話計畫，按「確認開工」或「取消」（避免白等做錯方向）
   ↓
👩‍💻 多個 AI 並行開發（Claude Code，各自一個分支）
   ↓
🧪 自動挑出最佳版本
   ↓
📬 開草稿 Pull Request（標 ai-generated）
   ↓
🔍 codex 自動審查 → 🛠️ Claude 依審查修正
   ↓
✅ 工程師收到一個「已被 AI 審查過」的 PR，按合併鍵即可
```

**對團隊的意義**：例行性需求不必工程師從零開始；每個變更在人看到之前已被 AI 審查＋修正；交易/金流類邏輯要求附測試。

---

## 🛡️ 安全護欄（預設開啟）

- AI **永遠只 push `ai/<task>/*` 分支**，絕不碰 `main` / 保護分支（即使 token 有權限，程式層也會擋）。
- 所有產出都是 **草稿 PR**，標 `ai-generated`，**必須人類合併**。
- **Repo 白名單**：只允許設定檔指定的程式庫。
- 崩潰自動收斂為「失敗」、不卡死；狀態檔原子寫入。

---

## 🤖 開發幕後：Codex 連抓 6 個真實漏洞

我把 Codex 設成 stop-time review gate（結束前一定先過一次安全審查）。在開發「成果預覽 / 下載」功能時，它連續攔下 6 個我漏掉的問題：

| # | Codex 抓到的漏洞 | 我的修法 |
|---|---|---|
| 1 | 預覽端點可被 **symlink 逃逸** 讀到專案外檔案 | 用 `toRealPath()` 追蹤符號連結後再檢查邊界 |
| 2 | 預覽會暴露 **既有 repo 的私有 clone** | 限定只服務「全新專案」結果 |
| 3 | local 判斷可被 **殘留 zip 繞過** | 改讀權威 `issue.json` mode |
| 4 | 下載 / UI 路徑 **同款繞過** | 一併改用權威 mode |
| 5 | 完成頁可能渲染 **壞掉的下載連結** | 只在檔案存在時才顯示按鈕 |
| 6 | 容器路徑（無 zip）會 **洩漏 tracked `.omc`** | git-archive 也排除 + 容器裝 zip |

> 每一個都修好、加回歸測試、實機驗證後才放行。這種「AI 互相把關」的開發過程，本身就是這個專案最想證明的事。

---

## 🔗 試試看

- 開源專案：`AI Factory（LZA 粉圓軟體工廠）`
- 一鍵啟動：`bash scripts/ai-factory-init.sh` → `bash scripts/doctor.sh` → `docker compose up`

<sub>由 AI Factory 團隊與粉圓 🫧 共同維護。</sub>
