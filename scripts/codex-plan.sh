#!/usr/bin/env bash
set -euo pipefail

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
cp "$ISSUE_FILE" docs/ai/issue.json

# Prefer the CLI resolved by run-task (CODEX_BIN), else fall back to PATH.
CODEX="${CODEX_BIN:-$(command -v codex 2>/dev/null || true)}"

if [ -n "$CODEX" ]; then
  aif_ai_retry 3 20 -- "$CODEX" exec --skip-git-repo-check --color never -o docs/ai/IMPLEMENTATION_PLAN.md - <<'PROMPT'
你是資深交易系統架構師與技術主管。

請根據 docs/ai/issue.json 的需求產生實作計畫，不要直接大量寫程式碼。

請輸出：
1. 背景與目標
2. 系統設計
3. 模組拆分
4. API / DB schema / config 變更
5. 交易所風險檢查：funding rate、liquidation、margin、precision、rounding、idempotency、concurrency
6. 測試策略：unit / integration / regression
7. Claude Code 執行任務清單
8. 驗收條件
PROMPT
else
  echo "WARN: codex CLI not found, writing placeholder plan" >&2
  cat > docs/ai/IMPLEMENTATION_PLAN.md <<EOF
# Implementation Plan (placeholder)

\`codex\` CLI was not available on PATH; install it to generate a real plan.

Issue: see docs/ai/issue.json
EOF
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
