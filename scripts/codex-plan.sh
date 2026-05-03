#!/usr/bin/env bash
set -euo pipefail

# Inputs:
#   $1                : path to issue.json (required)
#   env TASK_ID       : task id (defaults to dirname of issue.json)
#   env TARGET_BRANCH : base branch (default: main)

ISSUE_FILE="${1:?issue file required}"
TASK_ID="${TASK_ID:-$(basename "$(dirname "$ISSUE_FILE")")}"
TARGET_BRANCH="${TARGET_BRANCH:-main}"

mkdir -p docs/ai
cp "$ISSUE_FILE" docs/ai/issue.json

if command -v codex >/dev/null 2>&1; then
  codex run > docs/ai/IMPLEMENTATION_PLAN.md <<'PROMPT'
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

PLAN_BRANCH="ai/${TASK_ID}/plan"
git checkout -B "$PLAN_BRANCH" "$TARGET_BRANCH"
git add docs/ai/IMPLEMENTATION_PLAN.md docs/ai/issue.json
git commit -m "docs(${TASK_ID}): add AI implementation plan" || true
# Leave plan branch checked out; dev candidates branch from here.
