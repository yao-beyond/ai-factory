#!/usr/bin/env bash
set -euo pipefail

TASK_ID="${1:?TASK_ID required}"
FINAL_BRANCH="ai/${TASK_ID}/final"

git checkout "$FINAL_BRANCH"

if command -v claude-code >/dev/null 2>&1; then
  claude-code <<'PROMPT'
你是 Claude Code 修復工程師。

請根據 docs/ai/CODEX_REVIEW.md 修正問題。

規則：
1. 只修 review 指出的問題。
2. 不擴大重構。
3. 修完跑測試。
4. 補上必要測試。
5. 產出 docs/ai/FIX_SUMMARY.md。
PROMPT
else
  echo "WARN: claude-code CLI not found, leaving placeholder fix summary" >&2
  mkdir -p docs/ai
  printf "# Fix Summary (placeholder)\n\nClaude Code CLI not available; no fixes applied.\n" \
    > docs/ai/FIX_SUMMARY.md
fi

git add -A
git commit -m "fix(${TASK_ID}): address Codex review" || true
git push origin "$FINAL_BRANCH"
