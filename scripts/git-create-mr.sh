#!/usr/bin/env bash
set -euo pipefail
TASK_ID="${1:?TASK_ID required}"
: "${GITLAB_TOKEN:?GITLAB_TOKEN required}"
: "${GITLAB_PROJECT_ID:?GITLAB_PROJECT_ID required}"
GITLAB_API_BASE="${GITLAB_API_BASE:-https://gitlab.com/api/v4}"
SOURCE_BRANCH="ai/${TASK_ID}/final"
TARGET_BRANCH="${TARGET_BRANCH:-main}"

curl --fail --silent --show-error \
  --header "PRIVATE-TOKEN: ${GITLAB_TOKEN}" \
  --data-urlencode "source_branch=${SOURCE_BRANCH}" \
  --data-urlencode "target_branch=${TARGET_BRANCH}" \
  --data-urlencode "title=AI Factory: ${TASK_ID}" \
  --data-urlencode "remove_source_branch=false" \
  "${GITLAB_API_BASE}/projects/${GITLAB_PROJECT_ID}/merge_requests" \
  | tee /tmp/gitlab-mr-${TASK_ID}.json
