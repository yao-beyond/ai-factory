# Phase B 前置設計：一鍵上線 / 線上發布（live URL）

> 狀態：**前置設計（尚未實作）**。刻意先不接真實託管基礎設施，本文件定義抽象層、狀態機、端點與安全邊界，讓未來任一 provider 可插拔。
> 來源：CCG 三方綜合（Codex＝架構/安全、Gemini＝UX/文案、Claude＝取捨）。日期 2026-06-09。

## 0. 一句話目標

讓 `mode=new`（新全新專案）的成果，從目前的「行程內暫時預覽 + zip 下載」進一步變成**可分享的連結**；介面設計成可在未來換成真正的公網託管 provider，而 v0 先用同源的 `LocalStaticPublisher` 把整條流程（UI／狀態／端點／測試）跑通。

## 1. 現況（baseline）

- `mode=new` 產出落在 per-task work dir：`{workDir}/{taskId}/`。
- 既有產出：
  - `result.zip` 下載：`GET /gateway/result/{taskId}`
  - 行程內靜態預覽：`GET /gateway/preview/{taskId}/**`（直接 serve result 目錄，`hasPreview` 以 `index.html` 是否存在為閘）
- 既有安全基礎：path-traversal 防護、`ArchiveExtractor`（zip-bomb/slip 安全）、log 機密 redaction、`sanitizeText` 輸入清理、狀態機含 `PAUSED`/`CANCELLED` 終態。
- 核心原則：**AI 不碰 main、不自動合併，人類最終把關**；**宣稱與實作一致、不過度**。
- UI 完成頁（`TaskService` 約 599 行）已埋伏筆文案：「…或之後用『線上發布』功能即可。」

## 2. 架構決策

### 2.1 獨立服務，不混進 task 狀態機

新增獨立的 `DeploymentService` + provider 外掛。**deployment 狀態與 `TaskStatus` 完全分離**：一個 task 可以是 `COMPLETED`，同時 deployment 為 `LIVE` / `PUBLISH_FAILED` / `TAKEN_DOWN`。

### 2.2 狀態存放

子記錄落地檔案系統，與現有 filesystem 狀態一致（v0 不引入 DB）：

```
{workDir}/{taskId}/deployment.json
```

```java
public record DeploymentRecord(
        String deploymentId,
        String taskId,
        DeploymentStatus status,
        String providerId,
        String providerDeploymentId,
        String liveUrl,
        String idempotencyKey,
        Instant createdAt,
        Instant updatedAt,
        Instant publishedAt,
        Instant takenDownAt,
        String message
) {}
```

### 2.3 Provider 介面與模型（Codex 提供，可直接落地）

```java
package com.lza.aifactory.deploy;

public interface DeployProvider {
    String providerId();
    CompletionStage<DeploymentResult> publish(DeploymentDescriptor descriptor);
    CompletionStage<DeploymentResult> status(String providerDeploymentId);
    CompletionStage<DeploymentResult> takedown(String providerDeploymentId);
    boolean supports(PublishTarget target);
}

public record PublishTarget(
        String providerId,           // "local-static"，未來 "cloudflare-pages" / "s3" …
        String siteName,
        Map<String, String> options  // provider 專屬、非機密 設定
) {}

public record DeploymentDescriptor(
        String deploymentId,
        String taskId,
        Path artifactRoot,           // 已由 TaskService path-check 過的 result 根目錄
        PublishTarget target,
        String idempotencyKey,
        Instant requestedAt
) {}

public record DeploymentResult(
        String deploymentId,
        DeploymentStatus status,
        Optional<String> providerDeploymentId,
        Optional<URI> liveUrl,
        String message,
        Instant updatedAt
) {}

public enum DeploymentStatus {
    NOT_DEPLOYED,
    PUBLISH_REQUESTED, PUBLISHING, LIVE, PUBLISH_FAILED,
    TAKEDOWN_REQUESTED, TAKING_DOWN, TAKEN_DOWN, TAKEDOWN_FAILED
}
```

**非同步**：長時間 deploy 由 `DeploymentService` 用 executor 處理，不阻塞 HTTP。`POST /publish` 建立/續用 `DeploymentRecord`、提交工作、回 `202 Accepted`，瀏覽器輪詢狀態。

## 3. 狀態機

```
NOT_DEPLOYED ──▶ PUBLISH_REQUESTED ──▶ PUBLISHING ──▶ LIVE
                                       PUBLISHING ──▶ PUBLISH_FAILED

LIVE ──▶ PUBLISH_REQUESTED                 // 重新發布同一 task（產生新版本）
LIVE ──▶ TAKEDOWN_REQUESTED ──▶ TAKING_DOWN ──▶ TAKEN_DOWN
                                TAKING_DOWN ──▶ TAKEDOWN_FAILED

PUBLISH_FAILED  ──▶ PUBLISH_REQUESTED
TAKEDOWN_FAILED ──▶ TAKEDOWN_REQUESTED
TAKEN_DOWN      ──▶ PUBLISH_REQUESTED
```

**Idempotency**：
- 相同 `taskId + idempotencyKey` 且處於 `PUBLISH_REQUESTED/PUBLISHING/LIVE` → 回既有記錄。
- 無 idempotency key → server 端為 UI 表單自動產生一個。
- 從 `LIVE` 重新發布 → 產生新 `deploymentId`（或遞增 version）；v1 只保留單一「current」deployment。
- Takedown 具冪等性：`TAKEN_DOWN` 再 takedown 回 `200` + 現有記錄。

## 4. 端點

專用 controller（例如 `PublishController`）：

```
POST   /gateway/publish/{taskId}                       → 202 + DeploymentRecord
GET    /gateway/publish/{taskId}                        → 輪詢狀態
GET    /gateway/publish/{taskId}/{deploymentId}         → 指定 deployment 狀態
POST   /gateway/publish/{taskId}/takedown
GET    /gateway/site/{deploymentId}/{publicToken}/**    → v0 LocalStaticPublisher serve
```

```java
public record PublishRequest(
        String providerId,    // 預設 "local-static"
        String idempotencyKey
) {}
```

**Publish 前置條件**（全部滿足才放行）：
- task 存在
- task 為 `COMPLETED`
- `taskService.isNewProjectResult(taskId)`（只允許 new-project，**不發布 existing-repo 產物**）
- `taskService.hasPreview(taskId)`（有 `index.html`）
- result 根目錄 resolve 後落在 `workspace/repo` 內
- owner token 有效

## 5. 安全

### 5.1 Ownership token（無登入系統下的關鍵）

目前無 per-user auth。**不可**把可猜測的 `taskId` 當作權限憑證。

- task 建立時產生 `ownerToken = 256-bit 隨機`。
- **只**儲存 `SHA-256(ownerToken + serverSecret)` 於 `task-meta.json`。
- submit 回應**一次性**回傳原始 token；UI 存 `localStorage`（key = taskId）。
- 所有「會改變狀態」的端點要求 header：`X-AIFactory-Owner-Token`。
- **不用 cookie**：v0 同源 serve 任意 AI 生成 JS，cookie 會被惡意內容濫用；header token 由父層 UI 持有，風險較小。
- 匯入/還原的舊 task 無 owner token → publish/takedown 預設停用，直到本機操作者明確輪替一個。

### 5.2 v1 阻斷項（must-fix before ship）

1. **任意 HTML/JS 變公開內容**：v0 同源 serve → 加保護 headers（見 6.1）。真實託管時，**必須部署到與 gateway 不同的 origin，絕不掛 `/gateway` 同源**。
2. **發布前機密掃描**：阻擋 `.env`、私鑰、`.git`、SSH 材料、雲端憑證、已知 token pattern、過大 binary blob。掃到就拒絕 publish。
3. **濫用/成本限制**：最大產物大小、最大檔數、單 task 最大 publish 次數、**單 task 僅一個 live deployment**。
4. **Takedown 可靠且即時**：持有 owner token 者必須能立刻移除 live URL。
5. **Provider 憑證**絕不出現在生成檔、log、request DTO。

### 5.3 SSRF 防線

- 介面**不提供**「provider 去 fetch 這個 URL」模式。
- provider 只收信任的本地 `Path` 或由 gateway 可信程式上傳的 bytes。
- 未來雲端 provider 應**推送**產物，而非要 provider 去爬使用者提供的任意 URL。

### 5.4 可延後，但現在預留 hook

內容審核佇列（釣魚/惡意/仿冒）、自訂網域、CDN/cache purge、完整帳號/RBAC、provider 端分析、publish 時 server-side build（v0 只發布 task 已產好的靜態檔）。

## 6. v0 Provider：`LocalStaticPublisher`

最小可出貨，把整條流程跑通而不需任何雲端基礎設施。

行為：
- 產生 `deploymentId`。
- 產生**公開隨機 path token**（與 owner token 分離）。
- 寫 `deployment.json`。
- 透過 `GET /gateway/site/{deploymentId}/{publicToken}/**` serve `workspace/repo` 內檔案。
- Takedown → 狀態翻為 `TAKEN_DOWN`；serve 端點回 `410 Gone`。
- 沿用 preview 既有的 path-traversal / symlink escape 檢查。

### 6.1 保護 headers

```
X-Content-Type-Options: nosniff
Referrer-Policy: no-referrer
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

### 6.2 ⚠️ 命名/文案裁決（重要）

v0 的 `LocalStaticPublisher` 跑在 **localhost:8081 同源**，**並非公網可達**。

- 內部 providerId 叫 `local-static`。
- **對外 UI 文案一律用「🔗 產生分享連結」，不可用「發布到網路上 / 公開上線 / 任何人都看得到」**——避免宣稱超過實作（違反本專案「宣稱與實作一致、不過度」原則）。
- 真正的公網「🚀 一鍵發布上線」保留給未來的**獨立 origin provider**；在 v0 顯示為 `⏳ 一鍵上線（即將推出）`，點擊說明可先用「分享連結」。

> 此裁決採 Codex 立場，否決 Gemini 文案中「專案已在網路上公開 / 正式的公開網址 / 任何人點擊都能看到」等過度承諾用語。Gemini 的暖心文案（複製鈕、QR、下架確認框、誠實提醒）保留，僅把「公開上線」降級為「分享連結」。

## 7. UX / 文案（Gemini 提供，已套用第 6.2 降級）

> 全部 zh-TW，面向非技術使用者：**「發布」=「分享給別人看」**，避免伺服器/主機/環境/DNS 字眼。

### 7.1 按鈕狀態與微文案

| 狀態 | 按鈕標籤 | 輔助微文案 |
|---|---|---|
| 未發布 | `🔗 產生分享連結` | 讓專案變成一個連結，直接傳給別人看。 |
| 發布中 | `⚙️ 正在建立連結…`（loading） | 正在為您的成果準備空間，請稍等約 1 分鐘。 |
| 已上線 | `✅ 連結已就緒` | 您的成果已經可以透過連結打開了。 |
| 失敗 | `⚠️ 遇到一點問題` | 別擔心，您可以〔再試一次〕。 |
| 已下架 | `🔗 重新產生連結` | 此連結目前已關閉，您可以隨時再次開啟。 |

> 公網版（未來）：未發布態用 `⏳ 一鍵上線（即將推出）`。

### 7.2 連結呈現與信任

- 明顯區塊框住網址，上方標：`🔗 您的專屬分享連結：`
- 工具：`📋 複製網址`、`📱 手機掃碼查看`（彈 QR Code）
- 信任文案（**降級版**）：「這個連結可以分享給別人，只要對方能連到這台服務就能打開。」
  - （未來公網版才可說：「這是正式的公開網址，可以放心貼給客戶、同事或朋友。」）

### 7.3 重新發布 / 下架

- 改過內容後：`🔄 把新修改更新到連結`，輔助：「更新後，原本連結的內容也會跟著改變。」
- 下架按鈕：`🚫 停止分享並關閉連結`
- 下架確認框：
  - 標題：要暫時關閉這個連結嗎？
  - 內容：關閉後，其他人點擊連結會看到「目前無法瀏覽」。您的專案檔案不會消失，隨時可以再次開啟。
  - 按鈕：`確認關閉` / `先不要，保持開啟`

### 7.4 誠實預期管理（發布鈕下方小字）

> 💡 溫馨提醒：這個連結主要用於成果預覽與快速分享。這是由 AI 自動生成的原型草案，適合展示與溝通。若需要正式、長期、大量使用，建議下載原始碼（zip）交由工程師正式部署。

### 7.5 功能未配置（公網版尚未開放）

- 按鈕：`⏳ 一鍵上線（即將推出）`
- 微文案：此功能正在開放中！現在您可以先用「🔗 產生分享連結」或「⬇️ 下載 zip」查看成果。

### 7.6 非網頁專案（無 index.html）

- 不顯示發布鈕，改顯示：
  - `📦 這是純程式邏輯專案`
  - 「此專案主要是程式碼與邏輯，無法當網頁直接開啟。請下載專案壓縮檔，交給工程師安裝與測試。」
  - 引導點 `⬇️ 下載你的專案（zip）`

## 8. 最小可出貨切片（v0 收斂）

```
DeploymentService
  ├─ deployment.json（filesystem-backed 子記錄）
  ├─ DeployProvider 介面 + 模型 + DeploymentStatus（純設計，無行為）
  ├─ LocalStaticPublisher（唯一 provider）
  ├─ owner-token guard
  ├─ PublishController（publish / status / takedown / site serve）
  └─ 測試：ownership、traversal、idempotency、takedown、機密掃描
```

## 9. 明確 Anti-Scope（先不做）

Cloudflare/Vercel/S3 整合、自訂網域、OAuth/登入系統、計費/配額儀表板、publish 時 server-side build、多版本 deployment 歷史 UI、AI 完成後自動發布、自動內容審核決策、existing-repo 模式產物的發布、（除非已在搬離 filesystem 狀態，否則）DB migration。

## 10. 建議實作順序（下一步 checklist）

1. 落地 `deploy/` package：model + `DeployProvider` 介面 + `DeploymentStatus`（純設計，無行為）。
2. `DeploymentService` + `deployment.json` 讀寫 + owner-token guard。
3. `LocalStaticPublisher` + `/gateway/site/...` serve（沿用 preview 防護 + 6.1 安全 headers）。
4. `PublishController` 四個端點。
5. 完成頁 UI：第 7 節文案（「🔗 產生分享連結」，**非**「公開上線」）。
6. 測試：ownership、traversal、idempotency、takedown、機密掃描。

---

### 附錄：CCG 三方分工與衝突紀錄

- **Codex（架構/安全）**：提供 §2–§6 的介面、狀態機、端點、安全模型、v0 provider、anti-scope。
- **Gemini（UX/文案）**：提供 §7 的 zh-TW 按鈕/狀態/下架/提醒文案。
- **衝突點**：Gemini 文案稱 v0 為「公開上線、任何人都看得到」；Codex 指出 v0 同源非公網，建議降級為「產生分享連結」。
- **裁決（Claude）**：採 Codex，理由＝本專案「宣稱與實作一致、不過度」原則。v0 對外＝分享連結；公網「一鍵上線」標示為即將推出，保留給未來獨立 origin provider。
