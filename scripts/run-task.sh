#!/usr/bin/env bash
set -euo pipefail

# AI Factory orchestrator entrypoint.
# Reads /opt/ai-jobs/$TASK_ID/issue.json and runs the full pipeline:
#   plan -> N parallel dev candidates -> select -> MR -> review -> fix
# Writes status.txt at each stage so the gateway can surface progress.

TASK_ID="${1:-${TASK_ID:?TASK_ID required}}"
BASE="${AI_FACTORY_WORK_DIR:-/opt/ai-jobs}/$TASK_ID"
WORK="${BASE}/workspace"
STATUS_FILE="${BASE}/status.txt"

# Where the pipeline scripts live. Defaults to this script's own directory so
# the pipeline runs unchanged locally, via docker compose, or in the container
# (scripts are mounted at /opt/ai-pipeline). Override with AI_FACTORY_PIPELINE_DIR.
PIPELINE_DIR="${AI_FACTORY_PIPELINE_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)}"

# If the gateway shipped issue.json via env (K8s mode), materialize it on disk.
if [ ! -f "${BASE}/issue.json" ] && [ -n "${ISSUE_JSON_B64:-}" ]; then
  mkdir -p "$BASE"
  echo "$ISSUE_JSON_B64" | base64 -d > "${BASE}/issue.json"
fi

if [ ! -f "${BASE}/issue.json" ]; then
  echo "ERROR: ${BASE}/issue.json not found and ISSUE_JSON_B64 not set" >&2
  exit 2
fi

# Pull config from issue.json first (a per-issue repo override wins).
if command -v jq >/dev/null 2>&1; then
  REPO_URL="${REPO_URL:-$(jq -r '.repo // empty' "${BASE}/issue.json")}"
  TARGET_BRANCH="${TARGET_BRANCH:-$(jq -r '.targetBranch // empty' "${BASE}/issue.json")}"
  MAX_AGENTS="${MAX_AGENTS:-$(jq -r '.maxAgents // empty' "${BASE}/issue.json")}"
fi

# Then let ai-factory.yml (if present) fill any gaps and provide
# provider/draft/label defaults. Env and issue.json values still win.
if [ -f "${PIPELINE_DIR}/config/load-config.sh" ]; then
  # shellcheck source=/dev/null
  source "${PIPELINE_DIR}/config/load-config.sh"
  if aif_load_config; then
    aif_export_pipeline_env
  fi
fi

TARGET_BRANCH="${TARGET_BRANCH:-main}"
MAX_AGENTS="${MAX_AGENTS:-3}"
# Clamp to a sane range so a bad issue.json/env value can't spawn a runaway
# number of parallel agents and exhaust the machine.
case "$MAX_AGENTS" in
  ''|*[!0-9]*) MAX_AGENTS=3 ;;             # non-numeric -> default
esac
[ "$MAX_AGENTS" -lt 1 ]  && MAX_AGENTS=1
[ "$MAX_AGENTS" -gt 10 ] && MAX_AGENTS=10
export TASK_ID TARGET_BRANCH MAX_AGENTS

if [ -z "${REPO_URL:-}" ]; then
  echo "ERROR: REPO_URL is empty (set REPO_URL env or issue.json .repo to a git URL)" >&2
  printf 'STATUS=FAILED\nMESSAGE=missing_repo_url\nUPDATED_AT=%s\n' "$(date -u +%FT%TZ)" > "${STATUS_FILE}.tmp.$$"
  mv -f "${STATUS_FILE}.tmp.$$" "$STATUS_FILE"
  exit 2
fi
export REPO_URL

# PR_URL is captured once the pull/merge request is created and then carried
# through every subsequent status write so the gateway/UI can surface the link.
PR_URL=""
set_status() {
  local status="$1"
  local message="${2:-}"
  # Write to a temp file then atomically rename, so a reader (the gateway) never
  # sees an empty or half-written status file.
  local tmp="${STATUS_FILE}.tmp.$$"
  {
    printf 'STATUS=%s\nMESSAGE=%s\nUPDATED_AT=%s\n' "$status" "$message" "$(date -u +%FT%TZ)"
    [ -n "$PR_URL" ] && printf 'PR_URL=%s\n' "$PR_URL"
  } > "$tmp"
  mv -f "$tmp" "$STATUS_FILE"
}

trap 'set_status FAILED "stage:${STAGE:-unknown} rc:$?"' ERR

mkdir -p "$WORK"
cd "$WORK"

STAGE=clone
set_status RUNNING "cloning $REPO_URL"
if [ ! -d repo/.git ]; then
  git clone "$REPO_URL" repo
fi
cd repo
git fetch origin
git checkout "$TARGET_BRANCH"
git pull origin "$TARGET_BRANCH"

mkdir -p docs/ai
cp "${BASE}/issue.json" docs/ai/issue.json

STAGE=plan
set_status PLANNING "running codex-plan"
bash "${PIPELINE_DIR}/codex-plan.sh" "${BASE}/issue.json" "${BASE}/plan_summary.md"

# Pre-flight confirmation gate: show the plain-language plan and wait for the
# user to approve before building, so they don't wait through a wrong-direction
# run. Disable with security.confirmBeforeBuild=false (CONFIRM_BEFORE_BUILD).
CONFIRM_BEFORE_BUILD="${CONFIRM_BEFORE_BUILD:-true}"
CONFIRM_TIMEOUT_SECONDS="${CONFIRM_TIMEOUT_SECONDS:-1800}"
if [ "$CONFIRM_BEFORE_BUILD" = "true" ]; then
  STAGE=confirm
  rm -f "${BASE}/confirm.approve" "${BASE}/confirm.cancel"
  set_status AWAITING_CONFIRMATION "waiting_for_user_confirmation"
  waited=0
  while [ "$waited" -lt "$CONFIRM_TIMEOUT_SECONDS" ]; do
    if [ -f "${BASE}/confirm.cancel" ]; then   # cancel wins over approve
      set_status FAILED "cancelled_by_user"
      exit 4
    fi
    if [ -f "${BASE}/confirm.approve" ]; then
      break
    fi
    sleep 2
    waited=$((waited + 2))
  done
  if [ ! -f "${BASE}/confirm.approve" ]; then
    set_status FAILED "confirmation_timeout"
    exit 5
  fi
fi

STAGE=dev
set_status DEVELOPING "spawning ${MAX_AGENTS} dev agents"
pids=()
for i in $(seq 1 "$MAX_AGENTS"); do
  bash "${PIPELINE_DIR}/claude-dev.sh" "$TASK_ID" "$i" &
  pids+=("$!")
done
fail=0
for pid in "${pids[@]}"; do
  if ! wait "$pid"; then fail=$((fail + 1)); fi
done
if [ "$fail" -ge "$MAX_AGENTS" ]; then
  set_status FAILED "all_dev_candidates_failed"
  exit 3
fi

STAGE=select
set_status SELECTING "picking best candidate"
bash "${PIPELINE_DIR}/select-best-branch.sh" "$TASK_ID"

STAGE=mr
set_status MR_CREATED "creating pull request"
bash "${PIPELINE_DIR}/git/create-pr.sh" "$TASK_ID"
# Surface the PR/MR link to the gateway. Providers use different field names:
# GitLab=web_url, GitHub=html_url, Bitbucket=.links.html.href.
if command -v jq >/dev/null 2>&1 && [ -f "/tmp/pr-${TASK_ID}.json" ]; then
  PR_URL="$(jq -r '.web_url // .html_url // .links.html.href // empty' "/tmp/pr-${TASK_ID}.json" 2>/dev/null || true)"
fi
set_status MR_CREATED "pull request ready"

STAGE=review
set_status REVIEWING "running codex-review"
bash "${PIPELINE_DIR}/codex-review.sh" "$TASK_ID"

STAGE=fix
set_status FIXING "running claude-fix"
bash "${PIPELINE_DIR}/claude-fix.sh" "$TASK_ID"

STAGE=summary
# Assemble a plain-language change summary for the gateway/UI from the artifacts
# the agents already produced (the winning candidate's summary + the fix summary).
# Written into BASE so the gateway can read it regardless of working directory.
{
  sel="$(cat docs/ai/SELECTED_AGENT.txt 2>/dev/null || true)"
  if [ -n "${sel:-}" ] && [ -f "docs/ai/CLAUDE_SUMMARY_${sel}.md" ]; then
    cat "docs/ai/CLAUDE_SUMMARY_${sel}.md"
  fi
  if [ -f docs/ai/FIX_SUMMARY.md ]; then
    printf '\n## 審查修正\n\n'
    cat docs/ai/FIX_SUMMARY.md
  fi
} > "${BASE}/summary.md" 2>/dev/null || true

set_status COMPLETED "pipeline finished"
