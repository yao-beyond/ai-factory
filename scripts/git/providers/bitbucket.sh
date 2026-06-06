#!/usr/bin/env bash
# Bitbucket Cloud pull-request adapter. Sourced by create-pr.sh.
#
# Token: GIT_TOKEN, else BITBUCKET_TOKEN
# Repo:  BITBUCKET_REPOSITORY (workspace/repo_slug) if set, else REPO_PATH
# Auth:  set BITBUCKET_USERNAME to use an app password (Basic auth);
#        otherwise the token is sent as a Bearer access token.
# Draft: Bitbucket has no native draft PRs, so drafts get a "[Draft]" prefix.

create_pull_request() {
  local token="${GIT_TOKEN:-${BITBUCKET_TOKEN:?BITBUCKET_TOKEN or GIT_TOKEN required}}"
  local api_base="${BITBUCKET_API_BASE:-https://api.bitbucket.org/2.0}"
  local repo="${BITBUCKET_REPOSITORY:-${REPO_PATH:?REPO_URL or BITBUCKET_REPOSITORY required}}"

  local title="$PR_TITLE"
  [ "$PR_DRAFT" = "true" ] && title="[Draft] ${PR_TITLE}"

  local auth_header
  if [ -n "${BITBUCKET_USERNAME:-}" ]; then
    auth_header="Authorization: Basic $(printf '%s:%s' "$BITBUCKET_USERNAME" "$token" | base64 | tr -d '\n')"
  else
    auth_header="Authorization: Bearer ${token}"
  fi

  curl --fail --silent --show-error \
    -X POST \
    -H "$auth_header" \
    -H "Content-Type: application/json" \
    "${api_base}/repositories/${repo}/pullrequests" \
    -d "$(jq -n \
          --arg t "$title" \
          --arg src "$SOURCE_BRANCH" \
          --arg dst "$TARGET_BRANCH" \
          --arg body "$PR_BODY" \
          '{title:$t, source:{branch:{name:$src}}, destination:{branch:{name:$dst}}, description:$body}')" \
    | tee "/tmp/pr-${TASK_ID}.json"
}
