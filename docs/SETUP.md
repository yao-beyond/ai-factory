# 安裝指南（給管理員）

這份文件給負責安裝的人。目標：**用一個設定檔 + 一行指令把 AI Factory 跑起來**，並能接到你既有的 GitHub / GitLab / Bitbucket。

---

## 你需要準備

- Docker（建議）或 Java 21。
- 目標程式庫的 **API token**：
  - **GitHub**：fine-grained PAT，對目標 repo 給 Contents + Pull requests 寫入。
  - **GitLab**：project access token，scope 勾 `api` 與 `write_repository`。
  - **Bitbucket**：access token（或 app password）。
- （選用）`OPENAI_API_KEY`、`ANTHROPIC_API_KEY` 給 AI 規劃與開發使用。

---

## 最快的方式：設定精靈

```bash
bash scripts/ai-factory-init.sh
```

精靈會逐步問你平台、程式庫網址、分支、token，然後產生 `ai-factory.yml` 與 `.env`，並可立即做一次健康檢查。

---

## 手動設定

1. 複製範例設定並編輯：

   ```bash
   cp config/ai-factory.example.yml ai-factory.yml
   cp .env.example .env
   ```

   - `ai-factory.yml`：平台、程式庫、分支、安全規則（**不放密鑰**）。
   - `.env`：放 token 與 API key（**不要提交到版本控制**）。

2. 檢查設定是否正確（強烈建議在跑任務前先做）：

   ```bash
   bash scripts/doctor.sh
   ```

   doctor 會檢查工具、設定、token 權限與程式庫連線，並用白話告訴你哪裡要修。
   只想做離線檢查（不打 API）可加 `--offline`。

---

## 啟動

### 方式 A：Docker Compose（建議）

```bash
docker compose up
```

服務會在 `http://localhost:8080`。

### 方式 B：本機 Java

```bash
cd gateway
./mvnw spring-boot:run \
  -Dspring-boot.run.jvmArguments="\
    -DAI_FACTORY_WORK_DIR=$(pwd)/../.work \
    -DAI_FACTORY_PIPELINE_SCRIPT=$(pwd)/../scripts/run-task.sh"
```

### 方式 C：Kubernetes（正式多租戶）

```bash
make build
make apply-k8s
```

詳見根目錄 README 的 K8s 章節。

---

## 設定檔載入順序

1. `AI_FACTORY_CONFIG` 環境變數指定的路徑
2. 目前目錄的 `./ai-factory.yml`
3. `~/.ai-factory/config.yml`

環境變數（如 `REPO_URL`、`GIT_PROVIDER`、`MAX_AGENTS`）會覆寫設定檔，方便 CI / k8s。

---

## 安全護欄（預設開啟）

- `security.allowRepositories`：只有清單內的程式庫能被處理。建議務必設定。
  （gateway 端也可用環境變數 `AI_FACTORY_ALLOW_REPOSITORIES`，逗號分隔。）
- `security.protectedBranches`：AI 永遠不會 push 到這些分支，即使 token 有權限。
- `security.draftPullRequests: true`：所有產出都是草稿 PR，標上 `ai-generated`。
- `security.requireHumanMerge: true`：一定要人類合併。

---

## 疑難排解

| 症狀 | 處理 |
| --- | --- |
| `doctor` 報 token 被拒 (401/403) | 確認 token 有效、且有 repo + pull request 寫入權限 |
| `doctor` 報找不到程式庫 (404) | 檢查 `git.repo` 拼字，以及 token 看得到該 repo |
| 任務卡在 ⚠️ FAILED | 看 `<workDir>/<taskId>/run.log` 與 `status.txt` 的 `MESSAGE` |
| 計畫/開發產出 placeholder | 沒安裝 `codex` 或 `claude-code` CLI；裝好再跑 |
