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

# Project mode: "local" generates a brand-new project in a scratch repo with no
# remote, no clone, no pull request, no token — for users who only have an idea.
# "existing" (default) clones REPO_URL and opens a PR/MR as before.
PROJECT_MODE="${PROJECT_MODE:-existing}"
if [ "${GIT_PROVIDER:-}" = "local" ]; then PROJECT_MODE="local"; fi
LOCAL_MODE=false
[ "$PROJECT_MODE" = "local" ] && LOCAL_MODE=true
export TASK_ID TARGET_BRANCH MAX_AGENTS PROJECT_MODE

# A brand-new project never talks to a git remote. Make sure no git command in
# this mode (including any the AI agent runs) can reach a credential helper /
# macOS keychain — so the user is never asked for GitLab/other stored secrets.
if [ "$LOCAL_MODE" = true ]; then
  export GIT_TERMINAL_PROMPT=0
  export GIT_CONFIG_COUNT=1
  export GIT_CONFIG_KEY_0=credential.helper
  export GIT_CONFIG_VALUE_0=
fi

if [ "$LOCAL_MODE" = false ] && [ -z "${REPO_URL:-}" ]; then
  echo "ERROR: REPO_URL is empty (set REPO_URL env or issue.json .repo to a git URL)" >&2
  printf 'STATUS=FAILED\nMESSAGE=missing_repo_url\nUPDATED_AT=%s\n' "$(date -u +%FT%TZ)" > "${STATUS_FILE}.tmp.$$"
  mv -f "${STATUS_FILE}.tmp.$$" "$STATUS_FILE"
  exit 2
fi
export REPO_URL="${REPO_URL:-}"

# PR_URL is captured once the pull/merge request is created and then carried
# through every subsequent status write so the gateway/UI can surface the link.
PR_URL=""
RESULT_ZIP=""
set_status() {
  local status="$1"
  local message="${2:-}"
  # Write to a temp file then atomically rename, so a reader (the gateway) never
  # sees an empty or half-written status file.
  local tmp="${STATUS_FILE}.tmp.$$"
  {
    printf 'STATUS=%s\nMESSAGE=%s\nUPDATED_AT=%s\n' "$status" "$message" "$(date -u +%FT%TZ)"
    [ -n "$PR_URL" ] && printf 'PR_URL=%s\n' "$PR_URL"
    [ -n "$RESULT_ZIP" ] && printf 'RESULT_ZIP=%s\n' "$RESULT_ZIP"
  } > "$tmp"
  mv -f "$tmp" "$STATUS_FILE"
}

trap 'set_status FAILED "stage:${STAGE:-unknown} rc:$?"' ERR

# Auto-detect the AI CLIs (searching PATH + common install dirs). The Claude Code
# CLI is `claude`; we also accept the older `claude-code` name. If a required CLI
# is missing, fail early with a plain install hint instead of producing fake output.
STAGE=preflight
if command -v aif_find_cli >/dev/null 2>&1 || declare -F aif_find_cli >/dev/null 2>&1; then
  CODEX_BIN="$(aif_find_cli codex || true)"
  CLAUDE_BIN="$(aif_find_cli claude claude-code || true)"
else
  CODEX_BIN="$(command -v codex || true)"
  CLAUDE_BIN="$(command -v claude || command -v claude-code || true)"
fi
export CODEX_BIN CLAUDE_BIN
need_install=""
[ -z "$CODEX_BIN" ]  && need_install="codex CLI（安裝：npm i -g @openai/codex）"
[ -z "$CLAUDE_BIN" ] && need_install="${need_install:+$need_install；}Claude Code CLI（安裝請見 https://claude.com/claude-code）"
if [ -n "$need_install" ]; then
  set_status FAILED "請先安裝：${need_install}"
  exit 6
fi
# Make the resolved CLIs discoverable to child scripts even under a minimal PATH.
export PATH="$(dirname "$CODEX_BIN"):$(dirname "$CLAUDE_BIN"):$PATH"

mkdir -p "$WORK"
cd "$WORK"

STAGE=clone
if [ "$LOCAL_MODE" = true ]; then
  # Local mode: git is used internally only — the user never needs an account,
  # token, or remote. Two flavours:
  #   - import: seed workspace/repo from an uploaded zip (already extracted by the
  #     gateway) or copy a local folder (SOURCE_PATH).
  #   - new: start from an empty project.
  if [ -n "${SOURCE_PATH:-}" ] && [ -d "$SOURCE_PATH" ]; then
    set_status RUNNING "importing your project"
    mkdir -p repo
    # Copy the folder's contents (never its .git) into repo.
    ( cd "$SOURCE_PATH" && tar --exclude='./.git' --exclude='./.git/*' -cf - . ) | ( cd repo && tar -xf - )
  fi
  if [ -d repo ] && find repo -mindepth 1 -not -path 'repo/.git*' -print -quit 2>/dev/null | grep -q .; then
    # Seeded (import): commit the existing files as the base.
    set_status RUNNING "importing your project"
    cd repo
    [ -d .git ] || git init -b "$TARGET_BRANCH" >/dev/null
    git config user.email "ai-factory@localhost"; git config user.name "AI Factory"
    git add -A
    git commit -q -m "chore: import existing project" >/dev/null 2>&1 || \
      git commit -q --allow-empty -m "chore: import existing project" >/dev/null 2>&1 || true
  else
    # Brand-new empty project.
    set_status RUNNING "preparing a new project"
    mkdir -p repo; cd repo
    [ -d .git ] || git init -b "$TARGET_BRANCH" >/dev/null
    git config user.email "ai-factory@localhost"; git config user.name "AI Factory"
    git commit --allow-empty -m "chore: initialize new project" >/dev/null
  fi
else
  set_status RUNNING "cloning $REPO_URL"
  if [ ! -d repo/.git ]; then
    git clone "$REPO_URL" repo
  fi
  cd repo
  git fetch origin
  git checkout "$TARGET_BRANCH"
  git pull origin "$TARGET_BRANCH"
fi

# Local mode never has a remote. Disable any credential helper at the REPO level
# (git installs ship credential.helper=osxkeychain by default), so NO git command
# run in this repo — including ones the AI agent runs — can pop a macOS keychain
# prompt for stored GitLab/GitHub secrets. Repo-level config is read regardless of
# environment, so this is robust even for child processes that reset env vars.
if [ "$LOCAL_MODE" = true ]; then
  git config credential.helper "" 2>/dev/null || true
fi

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

if [ "$LOCAL_MODE" = false ]; then
  STAGE=mr
  set_status MR_CREATED "creating pull request"
  bash "${PIPELINE_DIR}/git/create-pr.sh" "$TASK_ID"
  # Surface the PR/MR link to the gateway. Providers use different field names:
  # GitLab=web_url, GitHub=html_url, Bitbucket=.links.html.href.
  if command -v jq >/dev/null 2>&1 && [ -f "/tmp/pr-${TASK_ID}.json" ]; then
    PR_URL="$(jq -r '.web_url // .html_url // .links.html.href // empty' "/tmp/pr-${TASK_ID}.json" 2>/dev/null || true)"
  fi
  set_status MR_CREATED "pull request ready"
fi

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

# In local mode there is no PR to point at, so package the generated project as a
# downloadable zip for the gateway to serve. Exclude internal/tooling artifacts
# (.git, .omc agent state, docs/ai pipeline metadata) so the deliverable only
# contains the actual project.
if [ "$LOCAL_MODE" = true ]; then
  STAGE=package
  # Make sure the selected result is checked out.
  if git rev-parse --verify "ai/${TASK_ID}/final" >/dev/null 2>&1; then
    git checkout "ai/${TASK_ID}/final" >/dev/null 2>&1 || true
  fi
  ZIP_PATH="${BASE}/result.zip"
  rm -f "$ZIP_PATH"
  if command -v zip >/dev/null 2>&1; then
    zip -rq "$ZIP_PATH" . \
      -x '.git/*' './.git/*' \
      -x '.omc/*' './.omc/*' \
      -x 'docs/ai/*' './docs/ai/*' || true
  else
    # Fallback (e.g. no zip binary): git archive includes tracked files, so also
    # exclude tracked tooling artifacts (.omc) and the pipeline's docs/ai metadata.
    git archive --format=zip -o "$ZIP_PATH" HEAD -- . ':(exclude)docs/ai' ':(exclude).omc' 2>/dev/null || true
  fi
  [ -f "$ZIP_PATH" ] && RESULT_ZIP="$ZIP_PATH"
fi

set_status COMPLETED "pipeline finished"
