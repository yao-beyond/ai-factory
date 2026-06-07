#!/usr/bin/env bash
set -euo pipefail

# Inputs:
#   $1 TASK_ID
#   $2 AGENT_NO  (1..MAX_AGENTS)
# Pre-condition: the CURRENT working directory is this agent's own git worktree,
#                already checked out on branch ai/${TASK_ID}/dev-${AGENT_NO} (off
#                the plan branch), set up by run-task.sh. Running each agent in its
#                own worktree is what lets them build in parallel without sharing a
#                working tree / git index.

TASK_ID="${1:?TASK_ID required}"
AGENT_NO="${2:?AGENT_NO required}"
TARGET_BRANCH="${TARGET_BRANCH:-main}"
BRANCH="ai/${TASK_ID}/dev-${AGENT_NO}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/git/branch-guard.sh"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/ai-retry.sh"

# IMPLEMENTATION_PLAN.md is generated at runtime by codex-plan.sh inside the
# cloned target repo. It is *not* a static file in the ai-factory repo. If we
# get here without it, the plan stage failed silently.
if [ ! -f docs/ai/IMPLEMENTATION_PLAN.md ]; then
  echo "ERROR: docs/ai/IMPLEMENTATION_PLAN.md missing. Did codex-plan.sh run?" >&2
  exit 5
fi

case "$AGENT_NO" in
  1) STYLE="保守實作版：最小修改、低風險、優先通過測試" ;;
  2) STYLE="乾淨重構版：維持需求範圍內，改善可讀性與模組邊界" ;;
  *) STYLE="快速交付版：聚焦最小可用功能與測試補齊" ;;
esac

CLAUDE="${CLAUDE_BIN:-$(command -v claude 2>/dev/null || command -v claude-code 2>/dev/null || true)}"
if [ -n "$CLAUDE" ]; then
  aif_ai_retry 3 20 -- "$CLAUDE" -p --permission-mode acceptEdits <<PROMPT
你是 Claude Code 實作工程師。

請依照 docs/ai/IMPLEMENTATION_PLAN.md 開發。

實作策略：${STYLE}

規則：
1. 僅修改計畫範圍內檔案。
2. 不可修改 main/release 分支。
3. 每完成一個 task，跑 ./gradlew test；若不是 Gradle 專案，使用專案既有測試命令。
4. 交易/金流/撮合/風控邏輯必須補測試。
5. 完成後產出 docs/ai/CLAUDE_SUMMARY_${AGENT_NO}.md。
PROMPT
else
  echo "WARN: claude-code CLI not found, leaving placeholder summary" >&2
  mkdir -p docs/ai
  printf "# Candidate %s placeholder\n\nClaude Code CLI not available; no implementation produced.\nStyle: %s\n" \
    "$AGENT_NO" "$STYLE" > "docs/ai/CLAUDE_SUMMARY_${AGENT_NO}.md"
fi

git add -A
git commit -m "feat(${TASK_ID}): implement candidate ${AGENT_NO}" || true
if [ "${PROJECT_MODE:-existing}" != "local" ]; then
  aif_assert_push_allowed "$BRANCH"
  git push -u origin "$BRANCH"
fi
