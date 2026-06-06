#!/usr/bin/env bash
set -euo pipefail

TASK_ID="${1:?TASK_ID required}"
TARGET_BRANCH="${TARGET_BRANCH:-main}"
PLAN_BRANCH="ai/${TASK_ID}/plan"
FINAL_BRANCH="ai/${TASK_ID}/final"

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
if command -v codex >/dev/null 2>&1; then
  codex run > docs/ai/CODEX_REVIEW.md <<'PROMPT'
請 review 目前 MR diff（已輸出至 /tmp/diff-*.patch）。

請特別檢查：
1. 是否符合 docs/ai/IMPLEMENTATION_PLAN.md
2. funding rate / liquidation / margin / precision / rounding 風險
3. concurrency / transaction / idempotency
4. API 相容性與安全性
5. 測試是否足夠

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
