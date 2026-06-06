#!/usr/bin/env bash
# GitLab merge-request adapter. Sourced by create-pr.sh; reads its exported env.
#
# Token:   GIT_TOKEN, else GITLAB_TOKEN
# Project: GITLAB_PROJECT_ID if set, else URL-encoded REPO_PATH
# Draft:   GitLab marks drafts via a "Draft:" title prefix.

create_pull_request() {
  local token="${GIT_TOKEN:-${GITLAB_TOKEN:?GITLAB_TOKEN or GIT_TOKEN required}}"
  local api_base="${GITLAB_API_BASE:-https://gitlab.com/api/v4}"

  local project="${GITLAB_PROJECT_ID:-}"
  if [ -z "$project" ]; then
    if [ -z "${REPO_PATH:-}" ]; then
      echo "ERROR: set GITLAB_PROJECT_ID or REPO_URL so the project can be resolved" >&2
      return 2
    fi
    project="$(printf '%s' "$REPO_PATH" | jq -sRr @uri)"
  fi

  local title="$PR_TITLE"
  [ "$PR_DRAFT" = "true" ] && title="Draft: ${PR_TITLE}"

  curl --fail --silent --show-error \
    --header "PRIVATE-TOKEN: ${token}" \
    --data-urlencode "source_branch=${SOURCE_BRANCH}" \
    --data-urlencode "target_branch=${TARGET_BRANCH}" \
    --data-urlencode "title=${title}" \
    --data-urlencode "description=${PR_BODY}" \
    --data-urlencode "labels=${PR_LABEL}" \
    --data-urlencode "remove_source_branch=false" \
    "${api_base}/projects/${project}/merge_requests" \
    | tee "/tmp/pr-${TASK_ID}.json"
}
