#!/usr/bin/env bash
# GitHub pull-request adapter. Sourced by create-pr.sh; reads its exported env.
#
# Token: GIT_TOKEN, else GITHUB_TOKEN
# Repo:  GITHUB_REPOSITORY (owner/repo) if set, else derived REPO_PATH
# Draft: native draft PR support via "draft": true.

create_pull_request() {
  local token="${GIT_TOKEN:-${GITHUB_TOKEN:?GITHUB_TOKEN or GIT_TOKEN required}}"
  local api_base="${GITHUB_API_BASE:-https://api.github.com}"
  local repo="${GITHUB_REPOSITORY:-${REPO_PATH:?REPO_URL or GITHUB_REPOSITORY required}}"

  local draft="false"
  [ "$PR_DRAFT" = "true" ] && draft="true"

  local resp
  resp="$(curl --fail --silent --show-error \
    -X POST \
    -H "Authorization: Bearer ${token}" \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "${api_base}/repos/${repo}/pulls" \
    -d "$(jq -n \
          --arg t "$PR_TITLE" \
          --arg h "$SOURCE_BRANCH" \
          --arg b "$TARGET_BRANCH" \
          --arg body "$PR_BODY" \
          --argjson d "$draft" \
          '{title:$t, head:$h, base:$b, body:$body, draft:$d}')")"

  printf '%s\n' "$resp" | tee "/tmp/pr-${TASK_ID}.json"

  # Best-effort label (GitHub applies labels via the issues API).
  local number
  number="$(printf '%s' "$resp" | jq -r '.number // empty')"
  if [ -n "$number" ] && [ -n "${PR_LABEL}" ]; then
    curl --silent --show-error \
      -X POST \
      -H "Authorization: Bearer ${token}" \
      -H "Accept: application/vnd.github+json" \
      "${api_base}/repos/${repo}/issues/${number}/labels" \
      -d "$(jq -n --arg l "$PR_LABEL" '{labels:[$l]}')" >/dev/null || true
  fi
}
