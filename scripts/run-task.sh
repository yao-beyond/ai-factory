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

# If the gateway shipped issue.json via env (K8s mode), materialize it on disk.
if [ ! -f "${BASE}/issue.json" ] && [ -n "${ISSUE_JSON_B64:-}" ]; then
  mkdir -p "$BASE"
  echo "$ISSUE_JSON_B64" | base64 -d > "${BASE}/issue.json"
fi

if [ ! -f "${BASE}/issue.json" ]; then
  echo "ERROR: ${BASE}/issue.json not found and ISSUE_JSON_B64 not set" >&2
  exit 2
fi

# Pull config from issue.json (env vars take precedence so K8s/CI can override).
if command -v jq >/dev/null 2>&1; then
  REPO_URL="${REPO_URL:-$(jq -r '.repo // empty' "${BASE}/issue.json")}"
  TARGET_BRANCH="${TARGET_BRANCH:-$(jq -r '.targetBranch // "main"' "${BASE}/issue.json")}"
  MAX_AGENTS="${MAX_AGENTS:-$(jq -r '.maxAgents // 3' "${BASE}/issue.json")}"
fi
TARGET_BRANCH="${TARGET_BRANCH:-main}"
MAX_AGENTS="${MAX_AGENTS:-3}"
export TASK_ID TARGET_BRANCH MAX_AGENTS

if [ -z "${REPO_URL:-}" ]; then
  echo "ERROR: REPO_URL is empty (set REPO_URL env or issue.json .repo to a git URL)" >&2
  printf 'STATUS=FAILED\nMESSAGE=missing_repo_url\nUPDATED_AT=%s\n' "$(date -u +%FT%TZ)" > "$STATUS_FILE"
  exit 2
fi
export REPO_URL

set_status() {
  local status="$1"
  local message="${2:-}"
  printf 'STATUS=%s\nMESSAGE=%s\nUPDATED_AT=%s\n' "$status" "$message" "$(date -u +%FT%TZ)" > "$STATUS_FILE"
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
bash /opt/ai-pipeline/codex-plan.sh "${BASE}/issue.json"

STAGE=dev
set_status DEVELOPING "spawning ${MAX_AGENTS} dev agents"
pids=()
for i in $(seq 1 "$MAX_AGENTS"); do
  bash /opt/ai-pipeline/claude-dev.sh "$TASK_ID" "$i" &
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
bash /opt/ai-pipeline/select-best-branch.sh "$TASK_ID"

STAGE=mr
set_status MR_CREATED "creating GitLab MR"
bash /opt/ai-pipeline/git-create-mr.sh "$TASK_ID"

STAGE=review
set_status REVIEWING "running codex-review"
bash /opt/ai-pipeline/codex-review.sh "$TASK_ID"

STAGE=fix
set_status FIXING "running claude-fix"
bash /opt/ai-pipeline/claude-fix.sh "$TASK_ID"

set_status COMPLETED "pipeline finished"
