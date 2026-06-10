#!/usr/bin/env bash
set -euo pipefail

# Least-privilege env for the AI CLI: the codex/claude child only needs its model
# key (OPENAI_API_KEY / ANTHROPIC_API_KEY). Strip git-provider and messaging
# secrets so the agent can never read — and accidentally echo into its output —
# them. Git transport uses the remote URL / credential helper, not these env
# vars, so unsetting them here does not affect any git operation this script runs.
unset GITHUB_TOKEN GIT_TOKEN GITLAB_TOKEN BITBUCKET_TOKEN BITBUCKET_USERNAME \
      GITLAB_PROJECT_ID TELEGRAM_BOT_TOKEN TELEGRAM_WEBHOOK_SECRET 2>/dev/null || true

TASK_ID="${1:?TASK_ID required}"
TARGET_BRANCH="${TARGET_BRANCH:-main}"
PLAN_BRANCH="ai/${TASK_ID}/plan"
FINAL_BRANCH="ai/${TASK_ID}/final"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/ai-retry.sh"

git checkout "$FINAL_BRANCH"
if [ "${PROJECT_MODE:-existing}" = "local" ]; then
  # No remote in local mode: diff the final against the plan (or target) branch.
  DIFF_BASE="$PLAN_BRANCH"
  git rev-parse --verify "$DIFF_BASE" >/dev/null 2>&1 || DIFF_BASE="$TARGET_BRANCH"
  git diff "${DIFF_BASE}...$FINAL_BRANCH" > "/tmp/diff-${TASK_ID}.patch"
else
  git fetch origin "$TARGET_BRANCH"
  git diff "origin/${TARGET_BRANCH}...$FINAL_BRANCH" > "/tmp/diff-${TASK_ID}.patch"
fi

mkdir -p docs/ai
CODEX="${CODEX_BIN:-$(command -v codex 2>/dev/null || true)}"
if [ -n "$CODEX" ]; then
  aif_ai_retry 3 20 -- "$CODEX" exec --skip-git-repo-check --color never -o docs/ai/CODEX_REVIEW.md - <<'PROMPT'
請 review 目前變更 diff（已輸出至 /tmp/diff-*.patch）。

請特別檢查：
1. 是否符合 docs/ai/IMPLEMENTATION_PLAN.md
2. 正確性與邊界條件：錯誤處理、資料一致性、邊界/例外輸入
3. 並行 / 交易 / 冪等性（若適用）
4. API 相容性與安全性
5. 測試是否足夠
6. 若涉及金流、交易、權限等高風險領域，檢查該領域專門風險（如精度、捨入、額度、競態）

只回報 high-signal / critical / major 問題，不要吹毛求疵。
PROMPT
else
  echo "WARN: codex CLI not found, writing placeholder review" >&2
  printf "# Codex Review (placeholder)\n\ncodex CLI was not available.\n" > docs/ai/CODEX_REVIEW.md
fi

git add docs/ai/CODEX_REVIEW.md
git commit -m "review(${TASK_ID}): add Codex review" || true
[ "${PROJECT_MODE:-existing}" != "local" ] && git push origin "$FINAL_BRANCH"
true
