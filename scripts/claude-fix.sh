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
FINAL_BRANCH="ai/${TASK_ID}/final"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/ai-retry.sh"

git checkout "$FINAL_BRANCH"

CLAUDE="${CLAUDE_BIN:-$(command -v claude 2>/dev/null || command -v claude-code 2>/dev/null || true)}"
if [ -n "$CLAUDE" ]; then
  aif_ai_retry 3 20 -- "$CLAUDE" -p --permission-mode acceptEdits <<'PROMPT'
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
[ "${PROJECT_MODE:-existing}" != "local" ] && git push origin "$FINAL_BRANCH"
true
