# 設計：GEP 證據鏈瀏覽器（Governance Evidence Explorer）

> 狀態：**設計草案（尚未實作）**。來源：CCG 發想（Gemini 提案、Claude 以可行性×誠實定位收斂）。日期 2026-06-18。
> 姊妹文件：[`governance-runtime.md`](./governance-runtime.md)（治理 runtime 本體；本功能是其唯讀呈現層）。

---

## 0. 一句話與定位

**把每個任務既有的「雜湊鏈治理事件」變成一頁可點開、可追溯的視覺化證據鏈**——每個動作基於哪條政策、誰做的、被擋還是放行、鏈有沒有被竄改，一眼看完。

- 對**開發者／稽核者**：這是與 autonomy-first 工具的護城河——AI 改了什麼、為什麼、誰准的，全部攤在陽光下，不是黑盒。
- 對**素人**：用白話時間軸看「工廠替我擋下了什麼、我在哪一步點了頭」。
- **本質**：純**唯讀呈現層**。資料全部來自既有 `GovernanceEventLog` / `EvidenceService`，**不新增事件、不寫入、不改 schema**。低風險、可獨立交付。

### 誠實邊界（務必維持，沿用 runtime §0）
- 雜湊鏈是 **tamper-EVIDENT（可偵測竄改），不是 tamper-PROOF**：能擋意外編輯／部分竄改，擋不住能重寫整個檔的 host-level 攻擊者（需 Phase 2 外部簽章）。瀏覽器要**如實顯示** `signature`（目前多為「未簽章」）而非假裝有密碼學保證。
- 「逐行程式碼 ↔ 事件」的深度溯源**本期不做**（需 pipeline 產出 per-file/per-line 事件參照，目前沒有）。本期只呈現事件本身已有的 `policyHash / matchedRule / reason / extra.gate`。不得在 UI 上暗示已能逐行溯源。

---

## 1. 範圍

**做（Phase 1，純加法、唯讀）**
- 新唯讀端點 `GET /gateway/governance/{taskId}/explorer` → HTML（深色控制面風格，沿用 dashboard）。
- 垂直時間軸／鏈狀視覺：每個 `GovernanceEvent` 一個節點，依 `seq` 串接，畫出 `prevEventHash → eventHash` 的連線。
- 頂部完整性橫幅：✅ 驗證通過 / ⚠️ 在 seq N 處鏈斷裂（直接用 `ReadResult.tampered()` / `brokenSeq()`）。
- 每節點顯示：`seq`、時間、事件類型（含圖示）、actor（principalId·role·vendor）、政策雜湊（截短）、decision（result / matchedRule / reason）、`extra` 重點（如 gate、to-state）。
- 與既有 GEP / dashboard 互連：頁面提供「📄 下載證據包（GEP）」與「← 回控制台」；反向在 dashboard 的 Safe Catch／待核准列、以及任務進度頁加「🔍 證據鏈」連結。

**不做（明確延後）**
- 不新增/修改治理事件或 schema、不寫任何檔。
- 不做逐行 diff ↔ 事件對應（未來，需 pipeline 支援）。
- 不改認證模型（此頁與 `/events`、`/evidence`、dashboard 同屬唯讀，不需 operator 密鑰）。
- 不做即時推播／WebSocket（靜態頁 + 手動重整即可；如需可後加 `?poll=`）。

---

## 2. 資料來源（全部既有，零新依賴）

| 需要的東西 | 來源（已存在） |
|---|---|
| 事件鏈 + 竄改旗標 + 斷裂 seq | `GovernanceEventLog.read(taskId)` → `ReadResult{events, tampered, brokenSeq}` |
| 事件欄位 | `GovernanceEvent{seq, eventType, occurredAt, actor, policyHash, decision{result,matchedRule,reason,enforcementLayer}, extra, integrity{prevEventHash,eventHash,signature}}` |
| profile（標題/風險等級/是否需人類核准） | `GovernanceProfileLibrary.enabledById(taskService.readGovernanceProfileId(taskId))` |
| 證據完整性 / 缺什麼 | `EvidenceService.assemble(...)` + `EvidenceBundle.missingFor(profile)` |
| 機密遮蔽 | `SecretRedactor.redact(...)`（沿用 EvidenceService 的 `red()` 慣例） |

> 結論：本功能是把上述既有資料**換一種人看得懂的呈現**，不碰寫入路徑。

---

## 3. 端點與контракт

```
GET /gateway/governance/{taskId}/explorer   → text/html（唯讀）
```
- `taskId` 先過 `taskService.normalizeTaskId()` 再做任何磁碟存取（沿用既有防穿越慣例）。
- 任務不存在（`taskService.findStatus(id).isEmpty()`）→ 404（與 `/gep` 一致）。
- 無事件 → 正常渲染「尚無治理事件」空態，非 500。
- 讀檔失敗 → 降級顯示，不 500（沿用 dashboard 的 graceful degrade）。

實作建議：新增 `GovernanceExplorerController`（`@RestController`，回 HTML 字串），或併入 `GovernanceDashboardController`。注入既有 `GovernanceEventLog` / `EvidenceService` / `GovernanceProfileLibrary` / `TaskService`。

---

## 4. UI 結構（深色控制面，沿用 dashboard 視覺）

```
┌ 🔍 證據鏈 — 任務 {taskId}                         [📄 下載 GEP] [← 控制台] ┐
│ 完整性：✅ 雜湊鏈驗證通過        （或）⚠️ 在 seq 3 處斷裂，可能遭竄改       │
│ Profile：合規修復員（compliance-patcher · critical）｜ 人類核准：必須      │
│ 證據完整性：⚠️ 缺少 test-results、independent-review-report               │
├───────────────────────────────────────────────────────────────────────┤
│  ● seq 0  gate-passed            2026-…Z                                  │
│  │        actor: system.gateway · gateway                                │
│  │        gate: tests-pass ｜ policy: sha256:47f1c9…                      │
│  │        hash: bf5943…  (prev: genesis)                                  │
│  ●—chain— seq 1  independence-check-passed                               │
│  │        implementer: claude ｜ reviewer: codex                          │
│  ●        seq 2  🛡️ boundary-violation-blocked                            │
│  │        reason: write outside workspace ｜ rule: write:protected-path   │
│  ●        seq 3  🧑‍⚖️ promotion-state-change → HUMAN_APPROVAL_PENDING       │
└───────────────────────────────────────────────────────────────────────┘
```

事件圖示對應（沿用 dashboard 既有語彙）：
- `boundary-violation-blocked` / `gate-failed` / `independence-check-failed` → 🛡️（攔截，紅）
- `gate-passed` / `independence-check-passed` → ✅（綠）
- `promotion-state-change` → 🧑‍⚖️（顯示 `extra.to`）
- `override-with-rationale` → 🔓（顯示 rationale/ticket/expiry）
- 其他 → •
- `integrity.signature` 為 null → 顯示「未簽章」灰標（誠實，不假裝）。

---

## 5. 安全（沿用既有護欄，無新攻擊面）

- **唯讀**：純 GET、不接受任何寫入參數；與 `/events`、`/evidence`、dashboard 同層，**不需密鑰**。
- **機密遮蔽**：所有輸出值一律過 `SecretRedactor.redact()`（reason、matchedRule、extra、actor、refs…）。
- **XSS**：每個值 HTML escape；`taskId` 走 data 屬性或 escaped 文字（沿用 dashboard 的 `esc()`／`data-task-id` 慣例，不做 JS 字串插值）。hash hex 雖安全仍 escape。
- **路徑**：`normalizeTaskId` 先正規化；只讀 `workDir/{taskId}/governance-events.jsonl`，`NOFOLLOW_LINKS`／名稱正規化沿用 dashboard service 的防 symlink 慣例。
- **完整性如實**：直接用 `ReadResult.tampered()/brokenSeq()`，不自行重算、不掩飾。

---

## 6. 測試計畫（仿 `GovernanceDashboardTest`）

新增 `GovernanceExplorerTest`（`@SpringBootTest` + MockMvc，noop-pipeline）：
1. **基本渲染**：submit 任務 → 經 `promotion.check`（service 層）產生事件 → `GET /explorer` 200，HTML 含 `taskId`、含某事件類型、含「雜湊鏈驗證通過」橫幅。
2. **竄改顯示**：直接改寫 `governance-events.jsonl` 中間一行 → `GET /explorer` 顯示「在 seq N 處斷裂」橫幅（對齊 `GovernanceEventLogTest` 既有竄改案例）。
3. **空態**：剛建、無事件的任務 → 200 + 「尚無治理事件」，非 500。
4. **未知任務** → 404。
5. **XSS**：構造帶 `<script>` 的 reason/extra（或任務 title）→ 斷言 raw `<script>` 不出現（已 escape）。
6. **機密遮蔽**：事件 reason 含類密鑰字串 → 斷言頁面已遮蔽（沿用 `SecretRedactor` 既有樣式）。

---

## 7. 實作順序（小步、純加法，預期不動既有測試）

1. `GovernanceExplorerController`：`GET /gateway/governance/{taskId}/explorer`，注入既有 4 個 bean；normalize + 404 + 空態。
2. 私有 HTML builder：頂部摘要（profile / 完整性 / 缺漏）＋ 事件鏈節點（圖示、actor、policy、decision、hash 連線）；全程 `red()` + escape。
3. 互連：dashboard 的 Safe Catch／待核准列、任務進度頁 `/gateway/ui/{id}` 各加一個「🔍 證據鏈」連結；explorer 頁加「📄 下載 GEP」「← 控制台」。
4. `GovernanceExplorerTest`（上述 6 案）。
5. （選用）README 中英「治理」段、Endpoints 補一行 `/{id}/explorer`（依誠實措辭，標明唯讀呈現）。

**驗收**：gateway 全綠（既有 + 新測試）；`make test-scripts` 不受影響（純 gateway 變更）；瀏覽器對一個有攔截/核准事件的任務走查，竄改一行後橫幅正確轉紅。

---

## 8. 為什麼是這個（取捨紀錄）

- **基礎最齊**：雜湊鏈、竄改驗證、actor/policy/decision、GEP markdown 全已存在，本功能 ≈ 換呈現，工時集中在一個唯讀 HTML view + 測試。
- **打在護城河上**：直接強化「透明治理 vs 黑盒自主」這個與 Harness 的根本分野。
- **零誠實風險（若守住 §0 邊界）**：不新增任何「強制」宣稱，只把既有事實視覺化；signature/逐行溯源都如實標示「未做」。
- 與 Gemini 另兩個 top pick 的關係：「誠實標籤」可日後接在 explorer 頂部摘要；「虛擬大紅按鈕」是 dashboard 核准動作的儀式化，與本頁互補但獨立。
