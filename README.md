<p align="center">
  <img src="docs/mascot.jpg" width="180"/>
</p>

<h1 align="center">LZA 🫧 粉圓軟體工廠</h1>

<p align="center">
  <strong>「你的想法，就是生產線的起點。」—— 粉圓 Fen-Yuan</strong><br>
  讓軟體開發像吹泡泡一樣簡單、優雅且自動化。
</p>

<p align="center">
  <b>正體中文</b> | <a href="README.en.md">English</a>
</p>

---

## 🌟 這能幫我做什麼？

**AI Factory** 是一個專為「想解決問題，但不想（或沒空）寫程式」的人設計的 AI 自動化開發平台。

你只需要用白話文描述你的需求（例如：「幫我在網頁左上角加一個深藍色的回饋按鈕」），AI 就會自動為你：
1. **拆解計畫**：把你的願望變成具體的工程步驟。
2. **多工開發**：派出多個 AI 機器人同時嘗試不同的寫法。
3. **優中選優**：自動挑選出最穩定、最符合規範的成果。
4. **交付成果**：幫你開好 Pull Request（給工程師審查）或直接讓你下載整份專案。

---

## 🚀 三步驟快速啟動

不管你是老闆、PM 還是開發者，只要你的電腦有安裝 Docker，請打開終端機（Terminal）執行以下指令：

1.  **設定精靈**：自動偵測環境並產生設定檔。
    ```bash
    bash scripts/ai-factory-init.sh
    ```
2.  **健康檢查**：確保你的 AI 工具（Claude/Codex）都已準備就緒。
    ```bash
    bash scripts/doctor.sh
    ```
3.  **開工啟動**：
    ```bash
    docker compose up
    ```
    *啟動後，請開啟瀏覽器訪問：[http://localhost:8080](http://localhost:8080)*

---

## 💡 兩種運作模式

### ✨ 模式 A：「做一個全新的東西」(Local 模式)
*   **適合對象**：素人、想快速驗證點子的創業家。
*   **特色**：不需要 Git 帳號，不需要複雜的金鑰。AI 從零開始生成整個專案，完成後直接讓你下載一個 `.zip` 壓縮檔。

### 🔧 模式 B：「修改現有專案」
*   **適合對象**：工程團隊、產品經理。
*   **特色**：需在 `ai-factory.yml` 設定 Repo 位址與 Token。完成後 AI 會自動在 GitHub/GitLab/Bitbucket 開啟 Pull Request (PR) 供人類審查，確保品質。

---

## 🎨 非技術者的使用旅程

1.  **許願**：在首頁填寫標題與詳細描述，並選擇「開發強度」：
    *   ⚡ **快速**：小修小改，幾分鐘搞定。
    *   ⚖️ **穩健**：標準開發流程，兼顧速度與品質。
    *   🔬 **徹底**：深度重構或複雜功能，AI 會進行多輪辯論與測試。
2.  **監看**：進入進度頁，你會看到可愛的 Emoji 進度條，系統會告訴你「⏳ **預計還要約 N 分鐘**」（時間以 UTC+8 顯示），每 3 秒自動更新。
3.  **關卡：📝 開工前確認**：AI 規劃完後會停下來，用白話文告訴你它打算怎麼改。你可以按 **✅ 確認開工** 或 **❌ 不滿意取消**。
4.  **驗收**：
    *   現有專案：點擊「查看成果草案」連結，去 PR 頁面看 AI 寫的程式。
    *   全新專案：點擊「⬇️ **下載你的專案**」，並閱讀 AI 附帶的變更摘要。

---

## 🛠️ 技術參考（給開發團隊）

### 必要工具
系統會自動從你的 `PATH` 或 `~/.local/bin` 偵測以下 AI CLI，若缺漏會在網頁端提示安裝：
*   **Codex**: `npm i -g @openai/codex`
*   **Claude Code**: 詳見 [Claude 官網說明](https://claude.com/claude-code)

### 本機啟動 (Java 模式)
如果你不想用 Docker，也可以直接啟動 Spring Boot 服務：
```bash
cd gateway
export AI_FACTORY_WORK_DIR=$(pwd)/../.work
export AI_FACTORY_PIPELINE_SCRIPT=$(pwd)/../scripts/run-task.sh
./mvnw spring-boot:run
# 若需換埠：-Dspring-boot.run.arguments="--server.port=8081"
```

### 設定檔 `ai-factory.yml` 亮點
你可以透過這個檔案精準控制工廠行為：
*   `agents.maxAgents`: 同時並行開發的最大 AI 數量。
*   `security.confirmBeforeBuild`: 是否開啟「開工前確認」關卡。
*   `security.protectedBranches`: 受保護分支名單，AI 絕不會直接推送到這些地方。
*   `security.requireHumanMerge`: 強制所有產出必須經過人類審查合併。

### API 端點與 Webhooks
*   **UI 路由**: `/` (首頁)、`/gateway/ui` (列表)、`/gateway/ui/{id}` (進度)
*   **任務操作**: `POST /gateway/issue` (建立)、`POST /gateway/confirm/{id}` (確認)
*   **整合 Webhooks**: 支援 `/webhook/jira` 與 `/webhook/telegram`
*   **監控**: `/actuator/health`

### 🛡️ 安全護欄 (Safety Guardrails)
*   **不直推**: AI 永遠不會直接 push 到 `main` 分支。
*   **草稿標記**: 所有 PR 都會標註 `ai-generated` 標籤。
*   **白名單**: 僅允許在 `ai-factory.yml` 指定的 repo 中運作。
*   **自動收斂**: 若 AI 執行過程崩潰，任務會自動轉為失敗並釋放資源，不會卡死生產線。

---

## 📖 延伸閱讀
*   [操作者手冊 (USER_GUIDE.md)](docs/USER_GUIDE.md) - 詳細的功能操作說明。
*   [管理員安裝指南 (SETUP.md)](docs/SETUP.md) - 關於環境變數與權限的進階設定。

---
<p align="center">
  由 LZA 團隊與粉圓 🫧 共同維護。
</p>
