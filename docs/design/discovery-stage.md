# Discovery 前置設計：幫「沒頭緒」的用戶找到要做的軟體

> 狀態：**前置設計（尚未實作）**。把「我不知道要做什麼」的用戶，變成一句**保守、可編輯、可建造**的白話需求，然後沿用既有 `recommend → confirm → dev` 流程。
> 來源：CCG 五輪三方綜合（Codex＝架構/安全、Gemini＝UX/對話、Claude＝取捨）。日期 2026-06-09。
> 姊妹文件：[`phase-b-publish.md`](./phase-b-publish.md)（出口的「一鍵上線」）。Discovery 是入口、Phase B 是出口，兩者是「同一個 YES 的兩半」。

---

## 0. 這份在解什麼問題

現有流程假設用戶**已經有**一句白話需求（首頁 textarea →「想要什麼成品」）。但很多真實用戶落在首頁時是**沒頭緒**的：「我想幫小店做點東西」「我只是好奇能做什麼」——空白輸入框讓他卻步而離開。Discovery 就是補這個入口前置：**把模糊意圖塑形成可建造需求**，不分叉既有 pipeline。

```
模糊的人 → discovery（前置）→ 一句白話需求（預填回 textarea）→ 既有 recommend/confirm/dev
```

## 1. 誠實裁決：這套能交付到什麼程度（PARTIAL）

> CCG 第五輪兩位顧問獨立給出同一個字：**PARTIAL**。本節是定位線，**請勿在行銷/文案上越過它**（違反本專案「宣稱與實作一致、不過度」原則）。

- **沒頭緒 → 拿到「一個簡單頁面或自用小工具」：可以**，而且體驗不錯。
- **沒頭緒 → 拿到「會收件/自動化的商業系統」：還不行**，要等 Phase B 補後端/發布層。
- **根因**：卡片庫**同時是發掘機制又是能力邊界**，所以只能發掘出「長得像 v1 已經會做的東西」的需求。
- **邊界一句話**：**「有人送出東西、我要收到」——v1 在這裡停住。** 這也是第一大缺口（lead capture / 收件）。

可以說的定位：**「先做一個簡單的頁面或小工具」**。
不能說的定位：**「幫你打造你的商業系統」**。

## 2. 流程總覽

```
首頁一顆「幫我想想 / 隨便聊聊」
  ↓
[新增·期待校準] 1 題分流：「你想用它來做什麼？」
   顯示資訊 / 自己追蹤管理 / 讓訪客送出·報名·預約·下單 / 只是看看
   └─ 選「讓訪客送出…」者：先告知靜態限制（v1 表單是訪客自己傳、不會自動收件），可早點誠實出場
  ↓
2 題粗篩（audience × intent，純前端、0 AI）
  ↓
後端用 (audience × intent) 查封閉卡片庫 → 顯示 3–4 張具體卡（給所有人看，非 fallback）
  ↓
用戶選 1 張（後端查 canonical 卡，拒絕偽造/停用/不符 ID）
  ↓
[handoff 卡] 顯示「你接受這個靜態版本嗎？」確認
  ↓
選填一句補充（≤120 字，當不可信資料，只影響用詞）
  ↓
[新增·擁有感] 取名：「你這個小軟體要叫什麼名字？」
  ↓
V1 確定性模板組 draftRequest + 帶 included/excluded/constraints
  ↓
confirm 頁顯示「先這樣假設 / 這版先不做」（包裝成專注，非閹割）
  ↓
結構化約束一路傳進既有 recommend（不只傳白話句）
```

**便宜路徑**：問題階段 0 次 AI；整個 session v1 **無 model call**（確定性模板填空）。AI 潤飾留到 v1.1，且只能 rewrite 不能 expand。

## 3. 架構定位（Codex）

- Discovery 是**前置塑形工具，不是工廠的一部分**：**不是** task state、**不是** mode、**不是** 通用 chatbot。
- 獨立輕量端點 + 短命 `DiscoverySession`（**還沒建 task**），cheap / abortable / rate-limitable。
- 真正的範圍圍欄 = **卡片當可執行政策 + 後端查表 + 結構化約束往下游傳 + 合成後驗證**（不是靠 prompt 指令攔）。

### 端點骨架

```
POST /api/discovery/start
POST /api/discovery/answer
POST /api/discovery/finalize   → DiscoveryResult（draftRequest + 結構化約束）
```

`DiscoveryResult.draftRequest` 預填回首頁 textarea → 既有 `/recommend` → `options.json` → confirm。**Discovery 不自己產 options.json**。

### 護欄

session 15–30 分過期、最多 5 題、每 session 最多 1 次合成（v1.1 才有 AI）、IP/session rate-limit、每答案長度上限、required 最小集（`domain` + `audience OR pain`）。

## 4. 卡片資料模型（可執行政策，非顯示文字）

```json
{
  "id": "appointment_request",
  "version": 1,
  "enabled": true,
  "audiences": ["customers"],
  "intents": ["collect"],
  "projectType": "form",
  "titleZhTw": "預約資料小幫手",
  "oneLinerZhTw": "客人填好，網頁幫他整理成一段文字，他複製後自己傳給您（LINE / Email 都行）。",
  "draftTemplateZhTw": "建立一個靜態頁面，讓訪客填寫聯絡資料與偏好時段，於瀏覽器產生一段可複製的預約摘要，並提供列印區塊與選用的 mailto 連結。不得儲存到伺服器、不得自動寄信、不得提供管理後台。",
  "submissionMode": "visitor_manual_handoff",
  "ownerReceivesData": false,
  "included": ["表單欄位", "時段選擇", "產生可複製摘要", "列印區塊"],
  "excluded": ["真寄信", "行事曆同步", "付款", "會員", "自動通知", "管理後台"],
  "constraints": {
    "actors": 1, "workflows": 1, "dataSources": ["manual_entry"],
    "externalIntegrations": false, "auth": false, "payment": false
  }
}
```

**維護鐵則**：穩定 ID（行為絕不依賴在地化標題）、`version`/`enabled` 旗標、顯示文字與政策欄位分離（i18n 預留）、便宜測試（每張啟用卡必有必填欄位/合法 audience-intent/合法 projectType/至少一個 exclusion/無禁用能力標籤）。**加卡 = code review = 產品能力審查**——「工廠能做什麼」變成人類可審清單。卡片可用性必須**追蹤真實實作能力，不是產品野心**。

### `submissionMode`（資料去向，真正的邊界）

```
static_display | local_browser_storage | local_file_parse | visitor_manual_handoff
```
`ownerReceivesData: false` 對 v1 所有卡都成立——**沒有任何卡會「自動收到別人填的資料」**。

## 5. 卡片庫定稿（18 張，3×3 無空格）

> 標題/簡介＝Gemini ｜ included/excluded/constraints＝Codex ｜ ✏️=誠實改寫的 handoff 卡 ｜ 🆕=新增卡
> chip 文案：**給誰用** = `給客人的` · `我自己·家人用的` · `給同事夥伴的`；**想做什麼** = `美美地展示` · `幫忙收資料/記錄` · `生活省時小工具`

### 🧑‍🤝‍🧑 給客人的

| id | 標題 | 永遠可見的簡介 | mode | included | excluded |
|---|---|---|---|---|---|
| `mobile_namecard` | 我的專屬電子名片 | 不用印紙本，一鍵傳給客人，電話、LINE、地圖都在裡面。 | static_display | 基本資料、聯絡方式、外連社群/地圖 | 付款、會員、後台、資料收集 |
| `link_hub` 🆕 | 社群傳送門 | 把 LINE、IG、臉書、地址放在同一頁，客人點一下就通。 | static_display | 多個外連按鈕、頭像、簡介 | 點擊統計、帳號、後台 |
| `shop_intro_onepage` | 美美的店家介紹頁 | 把店家故事、營業時間、服務圖文做成漂亮網頁分享出去。 | static_display | 店家故事、時間、圖文、外連地圖 | 下單、購物車、付款、庫存 |
| `menu_browse` | 線上美圖菜單 | 漂亮照片配清楚價錢，讓客人在手機上輕鬆翻看。 | static_display | 分類菜單、圖片、價格展示 | 下單、結帳、購物車、庫存 |
| `faq_selfserve` | 常見問題自助區 | 把常被問的事都寫好，客人自己看，省下您重複回答。 | static_display | 可搜尋/展開問答、靜態內容 | AI 客服、即時對話、後台 |
| `appointment_request` ✏️ | 預約資料小幫手 | 客人填好，網頁幫他整理成一段文字，他**複製後自己傳**給您（LINE/Email 都行）。 | visitor_manual_handoff · `ownerReceivesData:false` | 表單欄位、時段選擇、產生可複製摘要、列印 | 真寄信、行事曆同步、付款、會員、自動通知、後台 |
| `event_signup_helper` ✏️ | 活動報名小幫手 | 客人在手機填好報名，網頁整理成一段話，他**複製後自己傳**給您。 | visitor_manual_handoff · `ownerReceivesData:false` | 活動資訊、報名欄位、產生摘要、列印 | 名額即時計算、收款、報到、寄信、儲存 |

### 🙋 我自己·家人用的

| id | 標題 | 簡介 | mode | included | excluded |
|---|---|---|---|---|---|
| `portfolio` | 我的作品展示牆 | 把得意作品整理成漂亮相簿，隨時拿給朋友或客戶看。 | static_display | 圖文網格、分類、關於我、外連聯絡 | 付款、會員、留言後台、上傳 |
| `travel_memory` | 旅遊回憶筆記 | 用照片和文字記錄每趟旅行，像本專屬數位相本。 | static_display | 照片、文字、時間軸、靜態地點卡 | 帳號、雲端同步、分享權限、地圖 API |
| `expense_tracker` | 簡單記帳小工具 | 專屬這台手機的記帳本，幫您算清楚每天花多少。 | local_browser_storage | 新增/刪除/分類/總計、本機儲存 | 多人、雲端、銀行串接、外部匯出 |
| `todo_list` | 隨手待辦清單 | 今天要做的事隨手記、隨手勾，不漏勾。 | local_browser_storage | 新增/勾選/刪除/分類、本機儲存 | 多人協作、推播提醒、雲端 |
| `habit_tracker` | 每天進步一點點 | 每天點一下打卡，看自己堅持了多久（連續天數、月曆格）。 | local_browser_storage | 每日打卡、連續天數、月檢視 | 推播提醒、帳號、雲端 |
| `countdown_timer` 🆕 | 重要日子倒數 | 離開幕、生日還有幾天？漂亮大數字幫您倒數。 | static_display | 目標日期、倒數顯示、訊息 | 帳號、通知、多事件後台 |
| `price_calculator` 🆕 | 快速算錢小計算機 | 幫您算折扣價或單位換算，不用拿計算機按老半天。 | static_display | 自訂計算欄位、即時結果 | 帳號、雲端、外部串接 |

> `price_calculator` 從「同事×省時」**移到「自己×省時」**：阿姨站櫃檯每天算錢，會在「我自己用的」找它。

### 👔 給同事夥伴的

| id | 標題 | 簡介 | mode | included | excluded |
|---|---|---|---|---|---|
| `project_status` | 夥伴進度看板 | 誰負責什麼、進度到哪，看一眼就懂，不用一直問。 | static_display（手動維護） | 手動進度區塊、狀態色、里程碑 | 帳號、權限、即時更新、留言、Jira/GitHub 同步 |
| `csv_preview` | 表格資料查看器 | 把長長的表格丟進去，在手機上輕鬆翻看、找資料。 | local_file_parse | 瀏覽器本機解析 CSV、檢視/篩選/排序 | 上傳伺服器、資料庫、多人、登入、巨檔、Excel 巨集 |
| `internal_form_draft` ✏️ | 內部表單草稿/列印表 | 同事填好，產生乾淨摘要，可直接**複製或列印**交接。 | visitor_manual_handoff · `ownerReceivesData:false` | 表單欄位、產生摘要、列印 | 真寄信/儲存、帳號、權限 |
| `work_checklist` | 工作檢查清單 | 把每次一定要做的步驟列出來，做完一項勾一項。 | local_browser_storage | 可勾選清單、本機儲存、匯出文字範本 | **真多人即時同步**、帳號、通知、指派流程 |

> `work_checklist` 由原 `shared_checklist` 改名、**拿掉「共用」**（不能讓用戶誤以為有多人共享狀態）。

### 列表類三卡的區隔（避免重複感）

三張都留，靠行為+所在格區隔，**模板必須不同**：`todo_list`＝個人任務/分類；`habit_tracker`＝每日重複/連續天數/月曆格（長期堅持）；`work_checklist`＝固定 SOP 步驟/可匯出範本（每次照做）。

### Ship-first 10（最小、100% 可建造、零改寫，可先出）

`mobile_namecard` · `shop_intro_onepage` · `faq_selfserve` · `menu_browse` · `portfolio` · `expense_tracker` · `todo_list` · `habit_tracker` · `project_status` · `csv_preview`
（`link_hub`/`countdown_timer`/`price_calculator` 同為 100% 靜態可加；**handoff 三卡列第二批**，確認誠實文案到位再上。）

## 6. handoff 卡誠實收嚴（CCG 第四輪）

> 靜態頁收不到資料。誠實作法：訪客填→頁面產生摘要→**訪客自己**複製/傳/列印；老闆**不會自動收到**、無收件匣、無清單、無後端。

### 機制：主「複製文字」、次 mailto

- **主要**：`複製文字`（零設定、幾乎所有裝置都行）。
- 次要：`列印 / 另存 PDF`。
- 選用：`用 Email 開啟草稿`，**明標「可能打不開」**——mailto 在很多裝置無郵件 app，**不可當主機制**。
- 卡片小標籤：`整理好，由對方自行傳送`。
- 送出後狀態文字（**禁用「報名成功/已送出/已收到」**）：
  > 已幫你整理好摘要 ✓ 請複製、傳送或列印——**網站不會自動送出，也不會留存**。

### 硬約束（往下游傳，讓生成碼絕不 POST/fetch/遠端儲存）

```json
{
  "submissionMode": "visitor_manual_handoff",
  "ownerReceivesData": false,
  "networkSubmissionAllowed": false,
  "backendRequired": false,
  "allowedHandoffMethods": ["copy_text", "print", "mailto_optional"],
  "forbiddenBehaviors": ["form_post","fetch_submit","xhr_submit",
    "third_party_form_endpoint","automatic_email_send","remote_storage",
    "analytics_on_personal_data"],
  "requiredUserFacingDisclosure": "visitor_must_send_manually_owner_receives_nothing_automatically"
}
```
> 鐵則：**生成碼絕不可 POST、fetch、遠端儲存，或暗示老闆已收到。**

### 隱私護欄（這三卡牽涉他人個資）

- mailto body 別塞敏感資料（會進瀏覽器/OS 歷史、proxy log）。
- 剪貼簿不私密 → 複製敏感資料時加提醒。
- 列印/PDF 會殘留在共用裝置/印表佇列/下載夾。
- **這三卡不要用 localStorage**（會在裝置留下他人個資），除非有明確本機保存說明。
- **不得加** analytics、遠端字型、CDN script、圖片 beacon、第三方嵌入。
- 情境限制：handoff 卡只給「團購/私廚報名」等輕情境，**絕不給「專業診所預約」**這種高期待自動化的場景。

## 7. 兩個新增改動（把 PARTIAL 推向 YES，CCG 第五輪）

兩者互補：①修「期待落差」（理智誠實）②修「情感擁有」（滿意度）。皆便宜、確定性、零 chatbot。

### ① 期待校準分流題（Codex）— 卡片『之前』

```
「你想用它來做什麼？」
  顯示資訊 / 自己追蹤管理 / 讓訪客送出·報名·預約·下單 / 只是看看
```
選「讓訪客送出…」者，**在看卡片前**先告知靜態限制（「v1 的表單是訪客自己傳、不會自動收件」），讓做不到的人**早點誠實出場**，而不是領一張將就的卡。再配選卡後的「你接受這個靜態版本嗎？」確認。把「最近一張卡就贏」變成「最近一張卡、但用戶接受了它的真實行為」。

### ② 取名擁有感（Gemini）— 確認『之前』

```
「對了，你這個小軟體要叫什麼名字？（例如：美霞的私房菜菜單、阿芬的省錢帳本）」
```
用戶輸入自己的名字那刻，成品就從「那張卡」變成「他的東西」——**將就→認同**的心理轉移。

### 甜點

- 給「只是好奇」的人一顆 **「開驚喜包」** 鈕（驚艷 + 可炫耀的分享連結）。
- 「都不太對」的暖心逃生：
  > 「看了看，好像都不是我心裡想的那樣耶…」
  > 「沒關係，有時候我也會想不出來。不然你隨便跟我說一句話就好，剩下的我來想辦法。」

## 8. 合成契約（v1 無 model call）

- **V1**：確定性模板填空（用 `draftTemplateZhTw`），無 AI 呼叫。可測、免疫 prompt injection、讓「卡片即邊界」真的成立。
- **V1.1**：可選 AI「在卡片邊界內潤飾」——**rewrite 不是 expand**，潤飾後對卡片約束做後驗證，違規退回模板。
- **永不**：讓模型從備註發明能力。
- 備註欄：≤120 字、標籤「補充場合或內容」非「需求描述」、只影響用詞/領域名詞、當不可信資料引號包起來、套既有 `sanitizeText`。
- **下游**：傳 `cardId / projectType / constraints / included / excluded / submissionMode / ownerReceivesData`，**不只傳 draftRequest**。

## 9. 明確 Anti-Scope（先不做）

持久化聊天記錄、帳號、跨 session 記憶、通用 chatbot、discovery 專屬 task state、discovery 專屬 options.json、獨立 ideation DB、多分支想法板、想法評分排序、背景 job、自主 PM agent、市場研究、商業計畫生成、AI 生成卡片庫、卡片 admin UI、per-user 個人化、無對應卡的自由文字記錄（真要記只記 `audience/intent/日期桶/版本`）。

## 10. 最小可出貨切片

```
首頁「幫我想想」鈕
 + 期待校準分流題（含 handoff 早期勸退）
 + 2 題單選（前端題庫，0 AI）
 + 後端封閉卡片庫（JSON/常數，含政策欄位）+ 查表/驗證
 + 選卡 → handoff 確認 → 選填備註 → 取名
 + V1 確定性模板組 draftRequest + 結構化約束
 + 預填回 textarea + confirm 頁顯示假設/排除
 + 結構化約束傳入既有 recommend
護欄：session 過期、最多 5 題、rate-limit、答案長度上限、required 最小集
測試：卡片完整性、禁用能力、偽造/停用 cardId 拒絕、handoff 不得產生網路提交、下游約束傳遞
```

## 11. 建議實作順序

1. 後端卡片庫（JSON/常數 + schema 驗證 + 完整性測試）— 先 Ship-first 10。
2. `DiscoverySession` + 三端點 + 護欄。
3. 2 題題庫 + 期待校準分流（純前端 + 後端查表）。
4. 確定性模板合成 + 結構化約束組裝 + 下游傳遞。
5. 首頁入口鈕 + 卡片 UI（暖文案、取名、handoff 確認）。
6. handoff 三卡（第二批）+ 誠實文案 + 硬約束驗證測試。
7. （v1.1）AI 潤飾 + 後驗證；驚喜包 + 逃生艙。

## 12. 實作狀態（2026-06-10）

**已實作並驗證（gateway，88 測試綠 + 瀏覽器走完整流程）：**
- 後端卡片庫 `discovery-cards.json`（18 張，啟動驗證 = backend-owned truth）；`discovery/{Card,DiscoveryCardLibrary,DiscoveryService,DiscoveryResult}`。
- 啟動驗證：必填欄位、合法 audience/intent/formProjectType/submissionMode、`ownerReceivesData=false`、`auth/payment/externalIntegrations=false`、`included/excluded` 非空、`actors/workflows>=1`、`schemaVersion==1`、handoff metadata 一致、**`dataSources` 與 `submissionMode` 一致**。
- 端點：`GET /gateway/discovery/cards`、`POST /gateway/discovery/finalize`（偽造/停用 cardId → 400；`@Size` 濫用守衛 + sanitize 截斷 note≤120/name≤60）。
- **權威邊界**：finalize 在 draftRequest **最後**附上卡片衍生的「【硬性限制…】」塊（在 user note 之後 → 邊界有最終話語權，note/name 無法放寬）。
- 前端：首頁「🤔 還不知道要做什麼？」入口 + `/gateway/discovery`（2 題 → 卡片 → 收資料誠實提醒 → handoff 確認閘門 → 取名 → 預填回首頁）。

**結構化約束已下傳（P1 已修，Codex stop-gate 要求）：**
- finalize 回傳 `cardId`；前端只把 **`discoveryCardId`** 帶進 `/gateway/issue`（**不送 client boundary**）。
- 伺服器在 `TaskService.submitInternal` 用 cardId **從卡片庫重新推導**權威 `capabilityBoundary`，寫進 **`issue.json`** + 設 **`DISCOVERY_CARD_ID`/`CAPABILITY_BOUNDARY` env**（直接設在子程序、無 shell escape 問題）。偽造/停用 id → 兩欄位清空。
- 三層防線：①description 內的權威文字塊（AI dev 直接讀到）②issue.json 機器可讀結構化約束 ③env 供 pipeline 腳本使用。整合測試 `DiscoveryIssueIntegrationTest` 證明寫入與偽造清除。

**已知限制 / 後續（誠實揭露，非靜默）：**
- **尚未**做自動 post-generation 驗證（產出後比對程式碼是否真的沒 POST/fetch/遠端儲存）；目前靠權威文字塊 + 結構化約束讓 AI dev 遵循，machine-enforced 驗證列 v1.1。
- 期待校準分流題目前以「customers×collect 顯示誠實提醒 + handoff 確認閘門」實現；尚未做卡片前的獨立「你想用它來做什麼」勸退題（設計 §7①）。
- 尚未做：session 過期/rate-limit（端點為無狀態 + `@Size` 守衛，暫足）、v1.1 AI 潤飾、驚喜包、無對應卡的匿名統計。

---

### 附錄：CCG 五輪決策軌跡

| 輪 | 主題 | 關鍵裁決 |
|---|---|---|
| 1 | 架構/UX 初探 | Discovery = 前置塑形工具，非 task state/mode；recognition over recall |
| 2 | 卡片矩陣 + 合成契約 | 「選單即圍欄」只對一半 → 真圍欄＝卡片政策+查表+下游約束；**v1 砍 model call 改模板** |
| 3 | 卡片庫逐卡 | 18 張定稿；handoff 卡誠實改寫不砍；新增 `submissionMode`/`ownerReceivesData` |
| 4 | handoff 收嚴 + 擺位 | 機制改「複製文字」；硬約束下傳；隱私護欄；`price_calculator` 移位 |
| 5 | 能否真正交付 | **PARTIAL**；加期待校準分流題 + 取名擁有感；守「不宣稱商業系統」定位線 |
