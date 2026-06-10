#!/usr/bin/env bash
set -euo pipefail

# Least-privilege env for the AI CLI: the codex/claude child only needs its model
# key (OPENAI_API_KEY / ANTHROPIC_API_KEY). Strip git-provider and messaging
# secrets so the agent can never read — and accidentally echo into its output —
# them. Git transport uses the remote URL / credential helper, not these env
# vars, so unsetting them here does not affect any git operation this script runs.
unset GITHUB_TOKEN GIT_TOKEN GITLAB_TOKEN BITBUCKET_TOKEN BITBUCKET_USERNAME \
      GITLAB_PROJECT_ID TELEGRAM_BOT_TOKEN TELEGRAM_WEBHOOK_SECRET 2>/dev/null || true

# Inputs:
#   $1                : path to issue.json (required)
#   $2                : path to write the plain-language plan summary (optional)
#   env TASK_ID       : task id (defaults to dirname of issue.json)
#   env TARGET_BRANCH : base branch (default: main)

ISSUE_FILE="${1:?issue file required}"
PLAN_SUMMARY_FILE="${2:-}"
TASK_ID="${TASK_ID:-$(basename "$(dirname "$ISSUE_FILE")")}"
TARGET_BRANCH="${TARGET_BRANCH:-main}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/ai-retry.sh"

mkdir -p docs/ai
# Copy the issue spec for the agent, but strip any credentials embedded in the
# repo URL (https://user:token@host) — docs/ai/issue.json is committed onto the
# PR branch below, so a token-in-URL here would leak into the delivered repo.
# This does not touch REPO_URL (the value used for clone/push), only the copy.
if command -v jq >/dev/null 2>&1 && \
   jq 'if (.repo | type) == "string" then .repo |= gsub("://[^/@[:space:]]+@"; "://") else . end' \
      "$ISSUE_FILE" > docs/ai/issue.json 2>/dev/null; then
  :
else
  cp "$ISSUE_FILE" docs/ai/issue.json
fi

PROJECT_TYPE="${PROJECT_TYPE:-recommend}"
# options.json must live where the gateway reads it (the task BASE dir, next to
# plan_summary.md), NOT in the repo CWD — otherwise the confirm page never finds
# the proposed options, and the project's result.zip would leak the file.
OPTIONS_FILE="$(dirname "${PLAN_SUMMARY_FILE:-.}")/options.json"

# Prefer the CLI resolved by run-task (CODEX_BIN), else fall back to PATH.
CODEX="${CODEX_BIN:-$(command -v codex 2>/dev/null || true)}"

if [ -n "$CODEX" ]; then
  aif_ai_retry 3 20 -- "$CODEX" exec --skip-git-repo-check --color never -o docs/ai/IMPLEMENTATION_PLAN.md - <<PROMPT
你是資深軟體架構師與技術主管。

請根據 docs/ai/issue.json 的需求產生實作計畫，不要直接大量寫程式碼。
請依需求所屬領域與技術棧調整內容，不要預設特定產業。

當前指定的專案類型目標：${PROJECT_TYPE}
（recommend=智慧推薦, web=簡約網頁, interactive=互動工具, mobile=手機感頁面, backend=純工具）

請輸出：
1. 背景與目標
2. 系統設計（請根據專案類型選擇合適的技術棧，例如 web 優先使用 HTML/JS/CSS）
3. 模組拆分
4. 介面 / 資料結構 / 設定變更（API、DB schema、config 等，視專案而定）
5. 風險與正確性檢查：邊界條件、資料一致性、錯誤處理、安全性、並行/冪等性；若涉及金流、交易、權限等高風險領域，補上該領域對應的專門檢查
6. 測試策略：unit / integration / regression
7. Claude Code 執行任務清單
8. 驗收條件

交付物硬性要求（依專案類型 ${PROJECT_TYPE}）：
- web / interactive / mobile：必須交付可在瀏覽器直接開啟的前端頁面（入口 index.html）。
- 若需求或所選方案包含後端服務：必須同時交付（1）可瀏覽前端頁面（2）可啟動後端服務（3）至少一條「前端實際呼叫後端 API」的完整流程；並在計畫中明確寫出前端入口、後端入口、install/build/test/start 指令與預覽方式。
- backend（純工具/自動化）：不要硬塞前端，以 zip + README 執行說明交付即可。
- 預設優先選擇低依賴、免複雜建置即可預覽的技術（純 HTML/JS、Python 腳本），確保 result.zip 取出即可運作。
PROMPT
else
  echo "WARN: codex CLI not found, writing placeholder plan" >&2
  cat > docs/ai/IMPLEMENTATION_PLAN.md <<EOF
# Implementation Plan (placeholder)

\`codex\` CLI was not available on PATH; install it to generate a real plan.

Issue: see docs/ai/issue.json
EOF
fi

# If PROJECT_TYPE is "recommend", generate options.json for the user to pick.
if [ "$PROJECT_TYPE" = "recommend" ] && [ -n "$CODEX" ]; then
  aif_ai_retry 3 20 -- "$CODEX" exec --skip-git-repo-check --color never -o "$OPTIONS_FILE" - <<'PROMPT' || true
請根據 docs/ai/issue.json 與 docs/ai/IMPLEMENTATION_PLAN.md，提供 2-3 個推薦的技術實作方案，給完全不懂程式的人挑選。
請優先選擇低依賴、免複雜建置即可預覽的主流技術（如純 HTML/JS/CSS、Python 腳本），確保產出 result.zip 取出即可運作。
輸出必須是純 JSON 陣列，不要任何 markdown 程式碼區塊標籤，格式如下：
[
  {
    "id": "方案代號(例如 fast/modern/stable)",
    "title": "方案名稱(如：輕巧耐用型、華麗專業型)",
    "description": "一句白話描述優點，給完全不懂程式的人看",
    "stack": "實際技術組合(例如 HTML + Vanilla JS + Tailwind)",
    "ratings": { "speed": 4, "smoothness": 3, "scalability": 3 },
    "recommended": false
  }
]
規則：
- 恰好一個方案的 recommended 設為 true（最穩妥、最容易預覽的那個）。
- ratings 用 1 到 5 的整數：speed=開發速度, smoothness=畫面順暢度, scalability=未來擴充性。
PROMPT
  # Clean up potential markdown blocks if AI ignored instructions
  if grep -q '^[[:space:]]*```' "$OPTIONS_FILE"; then
    sed -i '' 's/^\s*```json//g; s/^\s*```//g' "$OPTIONS_FILE" || sed -i 's/^\s*```json//g; s/^\s*```//g' "$OPTIONS_FILE"
  fi
fi

# Produce a plain-language plan summary for the "confirm before build" gate, so a
# non-technical user can sanity-check the direction before development starts.
if [ -n "$PLAN_SUMMARY_FILE" ]; then
  if [ -n "$CODEX" ]; then
    aif_ai_retry 3 20 -- "$CODEX" exec --skip-git-repo-check --color never -o "$PLAN_SUMMARY_FILE" - <<'PROMPT' || true
請根據 docs/ai/issue.json 與 docs/ai/IMPLEMENTATION_PLAN.md，用非工程師也看得懂的繁體中文，
輸出 3–5 點「開工前計畫摘要」。

要求：
- 每點一句話，條列（以 - 開頭）
- 說明會改哪些地方、預期結果、主要風險
- 不要放任何程式碼
- 不要承諾一定成功
PROMPT
  fi
  # Fallback (no codex, or empty output): derive a friendly summary from the issue.
  if [ ! -s "$PLAN_SUMMARY_FILE" ]; then
    title="$(jq -r '.title // "（未命名需求）"' docs/ai/issue.json 2>/dev/null || echo "（未命名需求）")"
    desc="$(jq -r '.description // ""' docs/ai/issue.json 2>/dev/null || echo "")"
    {
      echo "# 開工前計畫摘要"
      echo
      echo "- 你的需求：${title}"
      [ -n "$desc" ] && echo "- 內容：${desc}"
      echo "- AI 會先建立技術計畫，再進行平行開發、自動審查與修正。"
      echo "- 若方向不對，請點「取消」補充需求後重新送出。"
    } > "$PLAN_SUMMARY_FILE"
  fi
fi

PLAN_BRANCH="ai/${TASK_ID}/plan"
git checkout -B "$PLAN_BRANCH" "$TARGET_BRANCH"
git add docs/ai/IMPLEMENTATION_PLAN.md docs/ai/issue.json
git commit -m "docs(${TASK_ID}): add AI implementation plan" || true
# Leave plan branch checked out; dev candidates branch from here.
