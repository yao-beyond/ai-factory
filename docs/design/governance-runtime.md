# 設計：AI 變更控制 Runtime（Phase 1 MVP）

> 狀態：v2（已納 Codex 工程把關 + Gemini 產品把關修訂）。本文把「AI Factory 作為 AI 治理框架」三輪 CCG 腦力激盪的收斂結果，落成可實作的 Phase 1 計畫。
> 來源討論：`.omc/artifacts/ask/`（codex/gemini × 三輪 + 計畫審查）。

## 0. 一句話與誠實邊界

**AI Factory 是 AI 產出的變更控制 runtime：沒有任何 AI 變更能被標記為「可交付 / 待人類合併」，除非政策、證據、獨立審查與晉升規則都同意，且每個決定與例外都有可驗證的雜湊鏈完整性紀錄。**

誠實邊界（**務必**寫進 README，這是商業競爭力不是缺陷；對外稱「治理深度 Governance Depth」分層，不是「未完成功能」）：

- **可強制（Phase 1 就真，GATEWAY 層）**：晉升控制——AI 永不合併到保護分支（既有 branch-guard + draft PR + git provider branch protection）；任務不會進入「可交付 / COMPLETED」狀態，除非 gate 全過 + 證據包齊全。註：config 的 `requireHumanMerge` 目前只是宣告值、腳本未實際強制——Phase 1b 須把它接成真正的檢查，否則不得列為現行控制。
- **依賴部署設定才完整（標明，非本產品強制）**：保護分支的「合併」本身由 **git provider 的 branch protection + 人類審查** 把關；本 runtime 是 PR 的「准入＋舉證」層，不是 merge 的執行者。文件把 provider branch protection 列為部署前提。
- **需 sandbox 才真（Phase 3+，CDP 回 `ALLOWED_UNENFORCED`，UI/docs 明示「政策聲明但本階段未強制」）**：執行層——禁 prod secrets、禁網路 egress、禁寫保護路徑外。需 container/seccomp/egress proxy。
- **本質只能偵測（永不宣稱可阻止）**：模型語意層——「不准寫出隱晦不安全的程式」「不准串通」。靠隔離＋獨立審查＋掃描降低風險。

**人類核准端點的認證邊界（誠實標明）**：`approve` / `reject` / `override` / `promote-check`（operator 路徑）一律要求 operator 密鑰，**fail-closed**——未設 `AIF_INTERNAL_SECRET` 時 gateway 啟動會生成 ephemeral 密鑰、只印到 gateway console（不落地、不進 pipeline 環境）。因 `AIF_GATEWAY_URL` 會被 pipeline 子程序繼承，這道驗證是防止 workspace 內不可信程式（AI agent、專案測試）自我核准的關鍵；少了它就等於閘門 fail-open。**殘留風險**：在無真實 sandbox（Phase 3）下，若操作者把 gateway stdout 導向 workspace 可讀的檔（如 `nohup > /tmp/...`），生成的密鑰仍可能被同主機的 task 程式讀走。最穩做法是明設 `AIF_INTERNAL_SECRET`（留在 operator 環境、永不進 pipeline）；ephemeral + console 只是免設定的 fallback。

## 1. 範圍（Phase 1 做什麼 / 不做什麼）

做：
- `GovernanceProfile` 物件 + classpath JSON 庫（仿 `DiscoveryCardLibrary`），4 個寫死 profile。
- 能力決策點（CDP）：純函式判斷，發 `capability-allowed` / `capability-allowed-unenforced` / `boundary-violation-blocked` 事件。
- append-only 雜湊鏈治理事件日誌（每任務 `governance-events.jsonl`）。
- 證據包（Evidence Bundle）+ **人讀版 GEP 報告**（Markdown）。
- 最小獨立性檢查：implementer 與 reviewer 不同 principal、不同 vendor（high/critical）、reviewer 唯讀（由 pipeline 契約佐證）。
- 一頁攔截儀表板（含「Safe Catch」措辭 + 政策摩擦熱點）+ 查詢端點。

不做（明確延後）：客戶自寫 YAML、通用政策語言、OPA/Rego、執行層 sandbox、模型血統 registry、密碼學簽章（Phase 1 用雜湊鏈，留簽章介面）、SIEM、SSO、多組織委派。

## 2. 套件與檔案佈局

新增套件 `com.lza.aifactory.governance`，鏡射 `discovery` 套件慣例：

```
governance/
  GovernanceProfile.java        record（+ 巢狀 records：Capabilities, Gates, Independence）
  GovernanceProfileLibrary.java @Component，@PostConstruct fail-fast 載入+驗證
  Capability.java               enum（@JsonValue/@JsonCreator wire-name 映射）+ EnforcementLayer
  CapabilityResult.java         enum：ALLOWED / ALLOWED_UNENFORCED / BLOCKED
  CapabilityDecision.java       record：result + 命中規則 + 理由 + enforcementLayer
  CapabilityDecisionPoint.java  @Service：decide(profile, role, principal, capability)
  GovernanceEvent.java          record：事件信封（雜湊鏈欄位）
  GovernanceEventLog.java       @Service：append-only 寫入（per-task 序列化）+ 讀取驗鏈
  EvidenceBundle.java           record + isComplete(profile)
  EvidenceService.java          @Service：收集/驗證 + 產人讀版 GEP.md
  IndependenceCheck.java        record + checker
  PromotionState.java           enum（治理事件用，非 TaskStatus）
  GovernanceController.java     @RestController：查詢 + promote-check + override

resources/governance-profiles.json
controller/GovernanceDashboardController.java  伺服器渲染攔截儀表板（仿 WebUiController）
```

## 3. Schema

### 3.1 governance-profiles.json（classpath，啟動驗證）

四個 profile：`standard-app`（低敏感、human-approval optional）、`regulated-service`（high）、`compliance-patcher`（critical，旗艦 demo）、**`emergency-hotfix`**（critical-但-可特批：允許繞過獨立審查，**強制** `override-with-rationale` + 24h 內補證據；防「系統失火時阻礙我」造成的開發者起義）。

profile 結構（以 compliance-patcher 為例）：

```json
{
  "id": "compliance-patcher",
  "version": 1,
  "enabled": true,
  "riskTier": "critical",
  "title": "合規修復員",
  "description": "資安掃描發現 CVE/不合規配置時，自動生成修復並產出完整證據包。",
  "protectedPaths": ["src/**", "infra/**", "db/migrations/**"],
  "capabilities": {
    "implementer": {
      "allow": ["read:repo", "write:workspace", "run:tests", "propose:pr"],
      "deny": ["merge:main", "access:prod-secrets", "approve:own-change", "modify:policy"]
    },
    "reviewer": {
      "allow": ["read:diff", "read:test-results", "run:static-analysis", "emit:review-decision"],
      "deny": ["write:source", "merge:main", "approve:own-review"]
    }
  },
  "gates": { "beforeDeliverable": ["tests-pass", "independent-review-pass", "human-approval", "evidence-bundle-complete"] },
  "independence": { "requireDistinctPrincipal": true, "requireReviewerReadOnly": true, "requireDistinctVendor": true },
  "humanApproval": "required",
  "allowReviewBypass": false
}
```

`emergency-hotfix` 差異：`gates.beforeDeliverable` 移除 `independent-review-pass`、加 `override-rationale-recorded`；`allowReviewBypass: true`；`humanApproval: required`。

**啟動驗證規則**（fail-fast，補足 DiscoveryCardLibrary 等級嚴格度）：
- `schemaVersion == 1`、catalog 非空；profile id 唯一非空、`version >= 1`、title/description 非空。
- `riskTier ∈ {standard, high, critical}`、`humanApproval ∈ {optional, required}`。
- capability `allow`/`deny` 只能用 `Capability` enum wire-name（未知→啟動失敗）；同角色 allow/deny **互斥**；角色只能是 `implementer`/`reviewer`；兩角色區塊都必須存在。
- `gates.beforeDeliverable` 只能用已知 gate 名；`protectedPaths` 為合法 glob。
- **high/critical 強制**：gates 含 `human-approval` + 證據完整；`requireDistinctPrincipal=true`、`requireReviewerReadOnly=true`；若 `requireDistinctVendor` 宣告則須為 true（防把高風險 profile 設成形同虛設）。除非 `allowReviewBypass=true`（僅 emergency 類），否則 gates 須含 `independent-review-pass`。
- 查詢只回 enabled profile（仿 `enabledById`）。

### 3.2 Capability / 結果 enum（明確 Jackson 映射）

```java
public enum Capability {
    READ_REPO("read:repo", GATEWAY), WRITE_WORKSPACE("write:workspace", EXECUTION_SANDBOX),
    RUN_TESTS("run:tests", GATEWAY), PROPOSE_PR("propose:pr", GATEWAY),
    MERGE_MAIN("merge:main", GATEWAY), ACCESS_PROD_SECRETS("access:prod-secrets", EXECUTION_SANDBOX),
    APPROVE_OWN_CHANGE("approve:own-change", GATEWAY), MODIFY_POLICY("modify:policy", GATEWAY),
    READ_DIFF("read:diff", GATEWAY), READ_TEST_RESULTS("read:test-results", GATEWAY),
    RUN_STATIC_ANALYSIS("run:static-analysis", GATEWAY), EMIT_REVIEW_DECISION("emit:review-decision", GATEWAY),
    WRITE_SOURCE("write:source", EXECUTION_SANDBOX), APPROVE_OWN_REVIEW("approve:own-review", GATEWAY);
    private final String wire; private final EnforcementLayer layer;
    @JsonValue public String wire() { return wire; }
    @JsonCreator public static Capability fromWire(String w) { /* lookup, 未知拋 IllegalArgumentException */ }
}
public enum EnforcementLayer { GATEWAY, EXECUTION_SANDBOX, DETECTION_ONLY }
public enum CapabilityResult { ALLOWED, ALLOWED_UNENFORCED, BLOCKED }
```

CDP 規則：`deny` 命中且 `layer==GATEWAY`→`BLOCKED`；`deny` 命中但 `layer!=GATEWAY`→`ALLOWED_UNENFORCED`（發事件記「政策聲明但本階段未強制」，**不假裝擋**）；`allow` 命中→`ALLOWED`；未列出→預設 `BLOCKED`（白名單語意）。`approve:own-*` 永遠 `BLOCKED`。

### 3.3 治理事件信封（每任務 `governance-events.jsonl`，雜湊鏈）

```json
{
  "eventId": "evt_<ulid>", "seq": 7, "eventType": "boundary-violation-blocked",
  "occurredAt": "2026-06-13T08:42:11Z", "taskId": "abc-123",
  "profile": "compliance-patcher", "policyHash": "sha256:...",
  "actor": { "principalId": "ai.claude.session.x", "role": "implementer", "vendor": "anthropic" },
  "decision": { "capability": "merge:main", "result": "blocked", "enforcementLayer": "GATEWAY",
                "matchedRule": "capabilities.implementer.deny.merge:main", "reason": "..." },
  "integrity": { "prevEventHash": "sha256:...", "eventHash": "sha256:...", "signature": null }
}
```

- `eventHash = sha256(canonicalJSON(event minus integrity.eventHash/signature) + prevEventHash)`。canonical = key 排序、UTF-8、無多餘空白（用 Jackson `SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS` + 固定設定）。
- `seq` 從 0 起，每任務單調遞增；**per-task 寫入序列化**（同任務的 append 走單一鎖 / synchronized，避免交錯）；append 用 `StandardOpenOption.APPEND` 單行寫入。
- **威脅模型（誠實標明）**：雜湊鏈防「意外編輯 / 部分竄改」可被偵測，**不防** 能重寫整個檔案的主機級攻擊者（那需 Phase 2 的外部簽章 / WORM 儲存）。
- 驗鏈：回傳已驗證事件 + `tampered` 旗標（不中斷，讓儀表板顯示警告 + 斷裂點 seq）。
- Phase 1 `signature` 恆 null，欄位保留給 Phase 2。

### 3.4 證據包 / 晉升狀態（PromotionState ≠ TaskStatus）

```java
public record EvidenceBundle(String baseCommit, String proposedDiffRef, String testResultsRef,
    String reviewReportRef, String policyHash, List<String> gateDecisions, Instant assembledAt) {
  public boolean isComplete(GovernanceProfile p) { /* 依 p.gates.beforeDeliverable 檢查必備項 */ }
}
public enum PromotionState { DRAFT, IMPLEMENTATION_COMPLETE, REVIEW_PENDING, HUMAN_APPROVAL_PENDING,
    DELIVERABLE_ELIGIBLE, DELIVERED, BLOCKED, OVERRIDDEN }  // 僅治理事件用
```

**關鍵：不新增 `TaskStatus.BLOCKED`**（避免動到終態語意 / reconcile / 既有 133 測試 / UI）。治理擋下時：task 仍走既有 `FAILED`，但 `message` 明寫 `promotion_blocked:<gate>`，且治理事件記 `PromotionState.BLOCKED` + `gate-failed`。完成頁/儀表板用治理事件顯示「這是 Safe Catch，不是錯誤」。是否升級為獨立 `BLOCKED` 終態，留 Phase 1c 視 demo 反饋再議。

## 4. 整合點（修正：對齊實際 pipeline 順序）

實際順序（`run-task.sh`）：plan→confirm→dev→select→**(existing: 開 draft PR)**→review(codex)→fix(claude)→summary→**(local: explainer.sh 寫 EXPLAINER.md → package zip)**→`COMPLETED`。
PR 是 **draft**（review/fix 在其後對 draft 分支補 commit）。因此「晉升 / 信任邊界跨越」**不是開 PR**，而是 **任務進入「可交付 / 待人類合併」終態**（`COMPLETED` / zip 出貨）。

**關鍵時序（修正 explainer 漏洞）**：`explainer.sh` 也是 AI 步驟、會寫 commit `EXPLAINER.md` 進交付物。Gate **必須在所有交付物變動（含 explainer）之後、zip/`COMPLETED` 之前**，否則 zip 會含 gate 後未審查的 AI 內容。因此 local 模式重排為：summary→**explainer（最後一個交付物變動）**→**promote-check gate**→zip→COMPLETED。existing 模式 gate 在 fix/summary 之後、標 COMPLETED 之前。

1. **submit 時**：`IssueDto` 加 `governanceProfileId`（預設 `standard-app`，與既有行為等價）；`submitInternal` 解析 profile（未知→400，仿 discovery）、寫 `policyHash` 進 task-meta、發 `profile-selected`。
2. **晉升閘門（在 summary 之後、寫 `COMPLETED` / package 之前）**：`run-task.sh` 呼叫 `POST /gateway/governance/{taskId}/promote-check`：
   - 組證據包（baseCommit、diff、test 結果、codex review 報告、fix 摘要、policyHash）、跑 `gates.beforeDeliverable` 評估、跑獨立性檢查；
   - human-approval gate（high/critical）：若尚未核准→發 `promotion-state-change→HUMAN_APPROVAL_PENDING`，pipeline 停下等待（**用獨立治理 marker `governance.approve` / `governance.reject`，不可重用 confirm.approve**——開工前確認與交付前核准是兩個不同的人類決策點），人類於儀表板核准；
   - 任一 gate fail 且不可補→task `FAILED` + `message=promotion_blocked:<gate>` + 治理事件 `gate-failed`；
   - 全過→發 `promotion-state-change→DELIVERABLE_ELIGIBLE`，pipeline 才寫 `COMPLETED` / package。
3. **GATEWAY 強制的真實面**：`merge:main` 由既有 branch-guard + draft PR + **provider branch protection**（部署前提）共同保證 AI 不合併（`requireHumanMerge` 尚未接成真正檢查，Phase 1b 接好前不算控制）。本步把「draft 已 ready」升級為「有政策依據 + 證據包 + 獨立審查通過才標記可交付」。
4. **獨立性檢查**：implementer=`agents.developer`、reviewer=`agents.reviewer`（既有 config 字串）。Phase 1 vendor 由已知 agent id 推導（`claude→anthropic`、`codex→openai`、`gemini→google`；未知→`unknown`，`requireDistinctVendor` 下視為不通過）。reviewer 唯讀由 pipeline 契約佐證：reviewer 走 `codex-review.sh`（產報告、不 commit source）；若契約被違反（review 階段改了 source diff）→`independence-check-failed`。typed vendor config 留 Phase 2。

## 5. 端點與 shell 契約

| 方法 | 路徑 | 用途 |
|---|---|---|
| `GET` | `/gateway/governance/profiles` | 列可選 profile（display-safe）|
| `GET` | `/gateway/governance/{taskId}/events` | 治理事件（驗鏈 + redaction）|
| `GET` | `/gateway/governance/{taskId}/evidence` | 證據包狀態 + 人讀版 GEP.md 連結 |
| `POST` | `/gateway/governance/{taskId}/promote-check` | 內部：晉升前 gate+獨立性（pipeline 呼叫）|
| `POST` | `/gateway/governance/{taskId}/approve` | 人類核准 human-approval gate（限有權群組）|
| `POST` | `/gateway/governance/{taskId}/override` | 例外：須 rationale+ticket+expiry，發 `override-with-rationale`|
| `GET` | `/gateway/governance/dashboard` | 攔截儀表板（Safe Catch、待審、生效 override、摩擦熱點、覆蓋率）|

**promote-check shell 契約（fail-closed）**：
- 環境變數：`AIF_GATEWAY_URL`（預設 `http://127.0.0.1:8080`）、`AIF_INTERNAL_SECRET`（共享密鑰，沿用既有 webhook 簽章模式；缺則 promote-check 視為設定錯誤→fail-closed）。
- 逾時：預設 30s。gateway 無回應 / 非 2xx / 連線失敗 → **fail-closed**：寫 `FAILED` + `message=promotion_blocked:gateway_unreachable`、盡力寫一筆本地事件、exit 非零，**絕不**繼續 package / 標 COMPLETED。
- `promote-check`/`approve`/`override` 為高權限，須帶內部密鑰 / 限 localhost；匿名→401/403。錯誤沿用 `GlobalExceptionHandler`（`IllegalArgumentException`→400）。

## 6. 測試計畫

### 單元（仿 DiscoveryLibraryTest）
- `GovernanceProfileLibraryTest`：合法載入；壞 schemaVersion / 未知 capability / 重複 id / 未知 gate / allow∩deny 非空 / high-tier 缺 human-approval / critical 非 emergency 缺 independent-review → 啟動失敗（bad-*.json fixtures）。
- `CapabilityDecisionPointTest`：implementer 拒 merge:main（BLOCKED）；reviewer 拒 write:source（BLOCKED）；`access:prod-secrets`→`ALLOWED_UNENFORCED`（不假裝擋）；未列出→預設 BLOCKED；`approve:own-*` 恆 BLOCKED。
- `GovernanceEventLogTest`：雜湊鏈正確；竄改中間事件→驗鏈回 `tampered` + 斷裂 seq；redaction 套用；per-task 並發 append 不交錯。
- `EvidenceBundleTest` / `IndependenceCheckTest`（同 principal→fail、distinctVendor 同 vendor→fail、reviewer 違反唯讀→fail）/ `PromotionStateTest`。
- `EvidenceServiceGepTest`：人讀版 GEP.md 含「做了什麼/測試/誰審/政策依據/雜湊鏈驗證」且 redaction。

### 整合（`GovernanceControllerTest` + WebUiTest 家族）
- `GET profiles` 回 4 個、display-safe（不洩 capability 內部）。
- submit 未知 profileId→400；合法→issue.json + `profile-selected` 事件。
- `promote-check`：缺證據→`gate-failed`、task FAILED message=promotion_blocked；齊全→`DELIVERABLE_ELIGIBLE`；gateway 模擬 down→fail-closed。
- `approve` 未授權→403；`override` 缺 rationale/ticket/expiry→400；齊全→`override-with-rationale`。
- 匿名 promote-check/approve/override→401/403。
- 儀表板含「Safe Catch」措辭、摩擦熱點、redaction、無 XSS（capability/reason 經 esc()）。

### Shell（`scripts/tests/`，仿 explainer-test.sh，掛 make test-scripts）
- `governance-promote-test.sh`：mock gateway 回 BLOCKED→run-task.sh 不 package、不標 COMPLETED、status 寫明原因；回 DELIVERABLE_ELIGIBLE→繼續；gateway 不可達→fail-closed exit 非零。fixtures 用假 curl 回應檔。

### 端到端 demo（旗艦 compliance-patcher）
選 profile→implementer 寫修復→試 merge→**被擋（事件+儀表板 Safe Catch）**→獨立審查→reviewer 試改 source→**被擋**→審查過→人類核准→`DELIVERABLE_ELIGIBLE`→產**人讀版 GEP 結案報告**。

## 7. 分階段交付（1a 純加法，保 133 測試綠）

- **Phase 1a（純加法地基）**：profile library + Capability/結果 enum + CDP + 事件日誌 + 證據包 + GEP 產生器 + 唯讀端點（profiles/events/evidence）+ `IssueDto.governanceProfileId`（預設值＝既有行為）。不動 pipeline、不動狀態機。單元+整合測試。
- **Phase 1b（接線）**：promote-check 閘門接進 run-task.sh（fail-closed，放在 explainer 之後、zip/COMPLETED 之前）+ 獨立性檢查 + human-approval 等待（**獨立 `governance.approve` marker**）+ approve/override 端點 + **把 `requireHumanMerge` 接成真正的檢查**（否則不得宣稱為控制）。聚焦更新 WebUi/TaskService 測試。
- **Phase 1c（可見）**：攔截儀表板（Safe Catch + 摩擦熱點）+ 人讀版 GEP 一鍵匯出 + compliance-patcher demo 腳本。
- 每 sub-phase 結束跑全測試 + Codex 安審把關（CCG 慣例）。

## 8. 風險與緩解

- **最危險假設**：治理摩擦不殺生產力（否則開發者貼 code 繞過→shelfware）。緩解：promote-check 要快、override / approve 能一鍵（Slack/Telegram，重用既有通知）、攔截要準。`emergency-hotfix` profile 專為「系統失火別擋我、但會記帳」而設。先用 compliance-patcher 單一 profile 驗證此命題再擴張。
- **誠實風險**：不宣稱 EXECUTION/DETECTION 已強制。緩解：CDP 回 `ALLOWED_UNENFORCED`、UI/docs/README 明示分層（「治理深度」）。
- **既有測試不退化**：1a 全加法；profile 預設 `standard-app`（gates 僅 tests-pass/evidence、human-approval optional）對既有任務等價；不新增 TaskStatus；治理擋下沿用 FAILED + message。
- **GATEWAY 強制的部署前提**：保護分支的 merge 防護需 git provider branch protection 配合，文件須列為安裝必要步驟，否則「AI 不合併」只靠 AI 自律＋branch-guard，不夠硬。
