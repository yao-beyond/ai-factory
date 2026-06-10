#!/usr/bin/env bash
set -euo pipefail

# Picks the "best" dev candidate and creates ai/${TASK_ID}/final off of it.
#
# Strategy:
#   - If BEST_AGENT env is set, use that.
#   - Otherwise prefer the candidate with the largest non-trivial diff vs the
#     plan branch that still produced a CLAUDE_SUMMARY_${i}.md (proxy for "did real work").
#   - Fall back to dev-1.

TASK_ID="${1:?TASK_ID required}"
TARGET_BRANCH="${TARGET_BRANCH:-main}"
MAX_AGENTS="${MAX_AGENTS:-3}"
PLAN_BRANCH="ai/${TASK_ID}/plan"
# Diff base: the plan branch if present, else the target branch. In local mode
# there is no origin, so never fall back to origin/<branch>.
if git rev-parse --verify "$PLAN_BRANCH" >/dev/null 2>&1; then
  BASE_REF="$(git rev-parse "$PLAN_BRANCH")"
elif [ "${PROJECT_MODE:-existing}" = "local" ]; then
  BASE_REF="$(git rev-parse "$TARGET_BRANCH")"
else
  BASE_REF="$(git rev-parse "origin/${TARGET_BRANCH}")"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/git/branch-guard.sh"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/git-auth.sh"

choose_default() {
  for i in $(seq 1 "$MAX_AGENTS"); do
    if git rev-parse --verify "ai/${TASK_ID}/dev-${i}" >/dev/null 2>&1; then
      echo "$i"; return 0
    fi
  done
  return 1
}

if [ -n "${BEST_AGENT:-}" ]; then
  CHOSEN="$BEST_AGENT"
else
  best_score=-1
  best_idx=""
  for i in $(seq 1 "$MAX_AGENTS"); do
    branch="ai/${TASK_ID}/dev-${i}"
    git rev-parse --verify "$branch" >/dev/null 2>&1 || continue
    summary_bonus=0
    if git show "${branch}:docs/ai/CLAUDE_SUMMARY_${i}.md" >/dev/null 2>&1; then
      summary_bonus=1000
    fi
    diff_lines=$(git diff --shortstat "$BASE_REF...$branch" 2>/dev/null \
      | grep -oE '[0-9]+ insertions?' | grep -oE '[0-9]+' || echo 0)
    score=$(( summary_bonus + ${diff_lines:-0} ))
    if [ "$score" -gt "$best_score" ]; then
      best_score=$score
      best_idx=$i
    fi
  done
  CHOSEN="${best_idx:-$(choose_default || echo 1)}"
fi

SOURCE="ai/${TASK_ID}/dev-${CHOSEN}"
if ! git rev-parse --verify "$SOURCE" >/dev/null 2>&1; then
  echo "ERROR: chosen branch $SOURCE does not exist" >&2
  exit 4
fi

echo "Selected $SOURCE for final"
git checkout -B "ai/${TASK_ID}/final" "$SOURCE"
echo "$CHOSEN" > docs/ai/SELECTED_AGENT.txt
git add docs/ai/SELECTED_AGENT.txt
git commit -m "chore(${TASK_ID}): select dev-${CHOSEN} as final" || true
if [ "${PROJECT_MODE:-existing}" != "local" ]; then
  aif_assert_push_allowed "ai/${TASK_ID}/final"
  aif_git push -u origin "ai/${TASK_ID}/final" --force-with-lease
  aif_git push
fi
