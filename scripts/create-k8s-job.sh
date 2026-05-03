#!/usr/bin/env bash
set -euo pipefail

# Templates the orchestrator Job manifest with TASK_ID and the issue payload,
# then applies it via kubectl. The orchestrator pod decodes ISSUE_JSON_B64 at
# startup so it does not need a shared volume with the gateway.

TASK_ID="${1:?TASK_ID required}"
TEMPLATE="${JOB_TEMPLATE:-/opt/k8s/04-orchestrator-job-template.yaml}"
BASE="${AI_FACTORY_WORK_DIR:-/opt/ai-jobs}/${TASK_ID}"
ISSUE_FILE="${BASE}/issue.json"

if [ ! -f "$ISSUE_FILE" ]; then
  echo "ERROR: $ISSUE_FILE not found" >&2
  exit 2
fi

ISSUE_JSON_B64="$(base64 < "$ISSUE_FILE" | tr -d '\n')"

# Pull repo / branch / max-agents from issue.json (env wins).
extract() { jq -r "$1 // empty" "$ISSUE_FILE" 2>/dev/null || true; }
REPO_URL="${REPO_URL:-$(extract '.repo')}"
TARGET_BRANCH="${TARGET_BRANCH:-$(extract '.targetBranch')}"
MAX_AGENTS="${MAX_AGENTS:-$(extract '.maxAgents')}"
TARGET_BRANCH="${TARGET_BRANCH:-main}"
MAX_AGENTS="${MAX_AGENTS:-3}"

export TASK_ID ISSUE_JSON_B64 REPO_URL TARGET_BRANCH MAX_AGENTS

if command -v envsubst >/dev/null 2>&1; then
  envsubst '${TASK_ID} ${ISSUE_JSON_B64} ${REPO_URL} ${TARGET_BRANCH} ${MAX_AGENTS}' \
    < "$TEMPLATE" | kubectl apply -f -
else
  # Fallback: sed-based templating
  sed -e "s|\${TASK_ID}|${TASK_ID}|g" \
      -e "s|\${REPO_URL}|${REPO_URL}|g" \
      -e "s|\${TARGET_BRANCH}|${TARGET_BRANCH}|g" \
      -e "s|\${MAX_AGENTS}|${MAX_AGENTS}|g" \
      -e "s|\${ISSUE_JSON_B64}|${ISSUE_JSON_B64}|g" \
      "$TEMPLATE" | kubectl apply -f -
fi
