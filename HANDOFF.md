# AI Factory — 接手狀態（context 壓縮前快照）

> 給下一個工作階段：讀這份就能接續。日期 2026-06-07。

## 專案一句話
讓不懂程式的人用一句白話需求 → AI 自動 規劃→開工前確認→多 AI 並行開發→挑最佳→審查→修正→交付。Codex 規劃/審查、Claude Code 開發/修正。

## Git 狀態（重要）
- 在 `main`，`origin/main` 同步到 **`18ce52d`**（已 push 的最後一個 commit）。
- **使用者偏好（已記入 memory `no-auto-commit-push`）：不要自動 commit/push，只在明確指示時才做。**
- **有未提交變更（已驗證、待使用者指示才 commit）：**
  - `scripts/run-task.sh` — dev 階段改用 **git worktree**（每個 agent 獨立工作目錄，消除 `maxAgents>1` 的 git index.lock 撞鎖）+ **重試安全清理**（中斷後重跑能清掉殘留 worktree 註冊）。
  - `scripts/claude-dev.sh` — 改為在「自己的 worktree 內」運作（移除原本的 `git checkout` 分支建立，改由 run-task 的 worktree add 提供）。
  - `gateway/.../controller/TaskController.java` — 確認頁 JS：按確認後**禁用按鈕防重複點擊**，收到 **409 視為「已開始」直接刷新**（不再跳「操作失敗」）。

## 驗證現況
- gateway **40 測試全綠**（`cd gateway && ./mvnw -o test`）。
- worktree 併發 + 重試安全：standalone bash 實測通過（**尚未**用真實 maxAgents=3 任務端到端跑過）。
- 服務運行於 **http://localhost:8081**（本機 Java；啟動要 export `AI_FACTORY_WORK_DIR` 與 `AI_FACTORY_PIPELINE_SCRIPT`，**不要用 -D**，否則 pipeline 子程序讀不到）。

## 三種模式（git 全程選項化）
1. ✨ 全新專案（mode=new）：從零，免 git/token，產 result.zip + 線上預覽。
2. 📦 匯入既有專案（mode=import）：zip 上傳（`POST /gateway/import`，安全解壓 ArchiveExtractor）或本機資料夾（`sourcePath`，預設停用，需 `security.importRootDir`）。同樣 local 引擎、可預覽/下載。
3. 🔧 連 git 專案（mode=existing）：clone + 開草稿 PR（需 token）。

## 已完成的大塊（都已 push 在歷史裡）
Phase 1–4、白話摘要、開工前確認關卡、ETA(UTC+8)、成果預覽/下載、CLI 自動偵測+暫時性錯誤重試、AI CLI 非互動模式(codex exec / claude -p)、匯入模式、雙語 README、LinkedIn demo 文件（`docs/demo/`）。

## Codex stop-time review gate（已啟用）連續抓到並修掉的真實問題
8 個安全漏洞（symlink 逃逸、私有 clone 外洩、殘留 zip 繞過 ×2、壞下載連結、容器 .omc 洩漏、sourcePath 主機目錄外洩、繼承 SOURCE_PATH 繞過）+ 鑰匙圈根因（git 安裝層 osxkeychain → repo 層停用 credential.helper）+ worktree 併發鎖 + worktree 重試安全。**結束時 review gate 會再跑**，若再有發現要繼續修。

## 已知未完成 / 待辦
1. **commit 上面 3 個未提交變更**（等使用者說）。
2. **用真實 maxAgents=3 任務端到端驗證 worktree 修正**（目前只 standalone 測過）。
3. Phase B：成果「一鍵上線 live URL」（Gemini 點名、需託管基礎設施，刻意延後）。
4. dev/codex prompt 仍偏「交易系統」用語，對一般/全新專案可改通用。
5. 舊任務 `08fa60c5`（餐飲pos, maxAgents=3）是用舊碼跑、已踩 index.lock，可忽略/重送。

## 重點操作
- 跑測試：`cd gateway && ./mvnw -o test`
- 重啟服務：kill 8081 → `cd gateway; export AI_FACTORY_WORK_DIR=$(cd ..&&pwd)/.work; export AI_FACTORY_PIPELINE_SCRIPT=$(cd ..&&pwd)/scripts/run-task.sh; ./mvnw -o spring-boot:run -Dspring-boot.run.arguments="--server.port=8081"`
- CCG 分工：Codex=架構/把關、Gemini=UX/文案、Claude=實作。
