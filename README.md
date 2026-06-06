<p align="center">
  <img src="docs/mascot.jpg" alt="AI Factory 吉祥物：粉圓 🫧" width="180"/>
</p>

<h1 align="center">AI Factory</h1>
<p align="center">
  <b>把一句白話需求，自動變成一個可審查的 Pull Request。</b><br/>
  <sub>吉祥物：粉圓 🫧 — 像牠把口水吹成一個個泡泡，AI Factory 把你的一句需求吹成可審查的成果</sub>
</p>

<p align="center"><b>繁體中文</b> | <a href="README.en.md">English</a></p>

---

你只要用白話描述想要什麼。AI Factory 會幫你規劃、派多個 AI 工程師平行開發、挑出最好的版本、
跑一輪自動審查、修正問題，最後開一個**草稿 Pull Request** 等人類核准。支援
**GitHub、GitLab、Bitbucket**，並設計成能直接套進你既有的系統。

```text
一個需求（Jira / Telegram / 網頁表單 / curl）
   ↓
🧠 規劃  →  👩‍💻 平行開發 ×N  →  🧪 挑最佳  →  📬 開 PR  →  🔍 審查  →  🛠️ 修正
   ↓
✅ 一個標記為 "ai-generated" 的草稿 Pull Request，等待人類合併
```

---

## 誰該看哪份文件

| 你是… | 從這裡開始 |
| --- | --- |
| 在評估這東西值不值得用 | 本頁（往下看） |
| 要**使用**它（不懂程式） | [docs/USER_GUIDE.md](docs/USER_GUIDE.md) |
| 要**安裝**它 | [docs/SETUP.md](docs/SETUP.md) |
| 要擴充它的開發者 | 本頁 →〈技術參考〉 |

---

## 為什麼用它

- **交付更快** — 例行性的修改，不需要工程師從零開始，就能從一句需求變成可審查的 PR。
- **內建品質** — 每個變更在人看到之前都已被 AI 審查並修正過；交易／金流類邏輯一律要求附測試。
- **預設安全** — AI 永遠不會 push 到 `main`；所有產出都是**草稿 PR**、必須人類核准。
  不會破壞你既有的流程。

---

## 三步驟開始

```bash
# 1. 設定（互動式精靈 — 不必手動編輯檔案）
bash scripts/ai-factory-init.sh

# 2. 檢查一切是否連得上
bash scripts/doctor.sh

# 3. 啟動
docker compose up        # 服務於 http://localhost:8080
```

接著送出範例任務並觀看它執行：

```bash
curl -X POST http://localhost:8080/gateway/issue \
  -H 'Content-Type: application/json' \
  -d @examples/hello-world-issue.json
# 打開白話進度頁：
#   http://localhost:8080/gateway/ui/<taskId>
```

想手動設定、或用其他方式部署（純 Java、Kubernetes）？請見 [docs/SETUP.md](docs/SETUP.md)。

---

## 設定集中在一個檔案

除了密鑰以外的所有設定都放在 `ai-factory.yml`（範例見
[config/ai-factory.example.yml](config/ai-factory.example.yml) 以及
[github](examples/ai-factory.github.yml) / [gitlab](examples/ai-factory.gitlab.yml) 版本）：

```yaml
git:
  provider: github          # github | gitlab | bitbucket
  repo: https://github.com/acme/app.git
  targetBranch: main
agents:
  maxAgents: 3
security:
  allowRepositories: [ "https://github.com/acme/*" ]
  protectedBranches: [ main, "release/*" ]
  requireHumanMerge: true
  draftPullRequests: true
```

密鑰留在環境變數／`.env`（見 [.env.example](.env.example)）。設定檔載入順序：
`AI_FACTORY_CONFIG` → `./ai-factory.yml` → `~/.ai-factory/config.yml`；環境變數會覆寫檔案，
方便 CI／Kubernetes 使用。

---

## 技術參考

### 流程

```text
Jira / Telegram / 直接提交 issue
   ↓
Issue Gateway  (POST /webhook/jira | /webhook/telegram | /gateway/issue)
   ↓
[ 本機 / docker：bash run-task.sh ]   [ k8s 模式：kubectl apply Job ]
   ↓
Orchestrator
   ├─ codex-plan.sh         → docs/ai/IMPLEMENTATION_PLAN.md  + 分支 ai/<id>/plan
   ├─ claude-dev.sh × N     → 分支 ai/<id>/dev-1..N（以 plan 為基底平行開發）
   ├─ select-best-branch.sh → 分支 ai/<id>/final
   ├─ git/create-pr.sh      → Pull / Merge request（github | gitlab | bitbucket）
   ├─ codex-review.sh       → docs/ai/CODEX_REVIEW.md
   └─ claude-fix.sh         → docs/ai/FIX_SUMMARY.md
   ↓
人類審查並合併
```

### 模組

- `gateway/` — Spring Boot 閘道。接收 issue、對程式庫白名單做驗證、保存 `issue.json`、觸發
  pipeline，並提供狀態與一個對人友善的進度頁。
- `scripts/` — Bash pipeline。每個階段把狀態寫到 `<workdir>/<taskId>/status.txt`。
  - `scripts/git/` — 平台無關的 PR/MR 建立（`create-pr.sh` + `providers/*.sh`）與保護分支推送守衛。
  - `scripts/config/` — `ai-factory.yml` 載入器。
  - `scripts/ai-factory-init.sh`、`scripts/doctor.sh` — 設定精靈與啟動前檢查。
- `config/`、`examples/` — 範例設定與範例 payload。
- `k8s/` — Namespace、secrets 範本、gateway Deployment、orchestrator Job 範本、RBAC。
- `charts/` — gateway 的最小 Helm chart。
- `docs/ai/` — 審查檢查表與計畫範本。真正的每任務產物（`IMPLEMENTATION_PLAN.md`、
  `CODEX_REVIEW.md`、`FIX_SUMMARY.md`、`CLAUDE_SUMMARY_*.md`）是在執行時於**目標**程式庫內產生，
  不在此處。

### 端點

| 方法 | 路徑 | 用途 |
| ------ | -------------------------- | ------------------------------------------- |
| GET    | `/`                        | **給非技術者的提需求表單（HTML）** |
| GET    | `/gateway/ui`              | **所有任務的友善列表（HTML）** |
| GET    | `/gateway/ui/{taskId}`     | **對人友善的進度頁（HTML）** |
| POST   | `/gateway/issue`           | 直接提交結構化的 `IssueDto` |
| POST   | `/webhook/jira`            | Jira webhook（解析 `issue.fields.*`） |
| POST   | `/webhook/telegram`        | Telegram bot webhook（驗證 secret） |
| GET    | `/gateway/status/{taskId}` | 任務最新狀態（JSON） |
| GET    | `/gateway/tasks`           | 列出所有已知任務（JSON） |
| GET    | `/actuator/health`         | 就緒／存活探針 |

### 狀態機

`SUBMITTED → RUNNING → PLANNING → DEVELOPING → SELECTING → MR_CREATED → REVIEWING → FIXING → COMPLETED`

`FAILED` 可取代任何階段；`MESSAGE` 欄位會帶 `stage:<name> rc:<exit-code>`。
每個狀態在進度頁上都有白話說明，進行中還會顯示預估剩餘時間。

### Kubernetes 部署

```bash
make build            # 建置 gateway + agent 映像
make apply-k8s        # namespace、secrets、RBAC、configmaps、gateway deployment
```

gateway pod 以 ConfigMap 掛載 pipeline 腳本與 Job 範本。提交時它會執行 `create-k8s-job.sh`，
將 `issue.json` 以 base64 環境變數（`ISSUE_JSON_B64`）嵌入 orchestrator Job；orchestrator 由
agent 映像執行（內含完整 `scripts/` 樹，包括 `git/` 與 `config/`）。另提供 `charts/` 下的 Helm chart。

### 正式環境護欄

- Agent 永遠只 push `ai/<task-id>/*` 分支；`scripts/git/branch-guard.sh` 即使 token 有權限也會
  拒絕 push 到保護分支。
- 所有產出都是標記 `ai-generated` 的**草稿** Pull/Merge request，必須人類合併。
- gateway 會拒絕不在 `security.allowRepositories`（`AI_FACTORY_ALLOW_REPOSITORIES`）內的程式庫、
  限制 `maxAgents` 上限，並在 pipeline 崩潰時把任務收斂為 `FAILED` 而非永遠卡住。
- `select-best-branch.sh` 對 `final` 分支使用 `--force-with-lease`（絕不用 `--force`）。
