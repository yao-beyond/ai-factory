#!/usr/bin/env bash
set -uo pipefail

# AI Factory doctor — checks your setup BEFORE you run a task, so problems show
# up here (with plain-language fixes) instead of midway through a pipeline.
#
# Usage:
#   scripts/doctor.sh            # full check, including a live connection test
#   scripts/doctor.sh --offline  # skip the network/token connection test
#
# Exit code is 0 only when there are no errors (warnings are allowed).

OFFLINE=false
[ "${1:-}" = "--offline" ] && OFFLINE=true

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/config/load-config.sh"

ERRORS=0
WARNINGS=0
ok()   { printf '  \033[32m✓\033[0m %s\n' "$1"; }
warn() { printf '  \033[33m!\033[0m %s\n' "$1"; WARNINGS=$((WARNINGS+1)); }
err()  { printf '  \033[31m✗\033[0m %s\n' "$1"; ERRORS=$((ERRORS+1)); }

echo "AI Factory doctor"
echo "-----------------"

# 1) Required command-line tools.
echo "Tools:"
for tool in git curl jq; do
  if command -v "$tool" >/dev/null 2>&1; then ok "$tool found"
  else err "$tool is not installed. Install it and run doctor again."; fi
done
command -v codex       >/dev/null 2>&1 && ok "codex CLI found"       || warn "codex CLI not found — planning/review will use a placeholder. Install with: npm i -g @openai/codex"
command -v claude-code >/dev/null 2>&1 && ok "claude-code CLI found" || warn "claude-code CLI not found — development will use a placeholder. Install the Claude Code CLI."

# 2) Config file.
echo "Configuration:"
CONFIG="$(aif_find_config)"
if [ -z "$CONFIG" ]; then
  err "No ai-factory.yml found. Run scripts/ai-factory-init.sh to create one, or copy config/ai-factory.example.yml to ai-factory.yml."
  echo
  echo "Found ${ERRORS} error(s), ${WARNINGS} warning(s)."
  exit 1
fi
ok "Using config: $CONFIG"
aif_load_config

PROVIDER="$(aif_cfg git.provider)"
REPO="$(aif_cfg git.repo)"
TARGET="$(aif_cfg git.targetBranch)"
PREFIX="$(aif_cfg git.branchPrefix)"

case "$PROVIDER" in
  gitlab|github|bitbucket) ok "Git platform: $PROVIDER" ;;
  "" ) err "git.provider is not set. Choose one of: github, gitlab, bitbucket." ;;
  *  ) err "git.provider '$PROVIDER' is not supported. Choose one of: github, gitlab, bitbucket." ;;
esac

if [ -z "$REPO" ]; then
  err "git.repo is empty. Set it to the address of the repository you want AI Factory to work on."
else
  ok "Repository: $REPO"
fi

# 3) Repository allowlist.
ALLOW=()
while IFS= read -r __line; do [ -n "$__line" ] && ALLOW+=("$__line"); done < <(aif_cfg_list security.allowRepositories)
if [ "${#ALLOW[@]}" -eq 0 ]; then
  warn "security.allowRepositories is empty — any repository would be accepted. Add at least one entry to stay safe."
elif [ -n "$REPO" ]; then
  matched=false
  for pat in "${ALLOW[@]}"; do
    # shellcheck disable=SC2053
    case "$REPO" in $pat) matched=true; break ;; esac
  done
  $matched && ok "Repository is within the allowlist" \
            || err "Repository '$REPO' is not in security.allowRepositories. Add a matching entry, e.g. ${REPO%/*}/*"
fi

# 4) Branch policy.
if [ -z "$PREFIX" ]; then
  warn "git.branchPrefix is empty — defaulting to 'ai'. AI branches will look like ai/<task>/dev-1."
else
  case "$PREFIX" in
    main|master|release*|"$TARGET") err "git.branchPrefix '$PREFIX' points at a protected branch. Use something like 'ai'." ;;
    *) ok "Branch prefix: $PREFIX" ;;
  esac
fi

# 5) Token presence (matched to the chosen provider).
echo "Credentials:"
token=""
case "$PROVIDER" in
  gitlab)    token="${GIT_TOKEN:-${GITLAB_TOKEN:-}}";    tok_name="GITLAB_TOKEN" ;;
  github)    token="${GIT_TOKEN:-${GITHUB_TOKEN:-}}";    tok_name="GITHUB_TOKEN" ;;
  bitbucket) token="${GIT_TOKEN:-${BITBUCKET_TOKEN:-}}"; tok_name="BITBUCKET_TOKEN" ;;
esac
if [ -z "$token" ]; then
  err "No API token found. Set ${tok_name} (or GIT_TOKEN) in your environment / .env file."
else
  ok "${tok_name} is set"
fi
[ -n "${OPENAI_API_KEY:-}" ]    && ok "OPENAI_API_KEY is set"    || warn "OPENAI_API_KEY not set — the codex planner/reviewer will not run."
[ -n "${ANTHROPIC_API_KEY:-}" ] && ok "ANTHROPIC_API_KEY is set" || warn "ANTHROPIC_API_KEY not set — the Claude developer will not run."

# 6) Live connection test (token scope + repo reachability).
if [ "$OFFLINE" = false ] && [ -n "$token" ] && [ -n "$REPO" ] && command -v jq >/dev/null 2>&1; then
  echo "Connection:"
  # Reuse the path parser from create-pr.sh.
  repo_path="${REPO%.git}"; repo_path="${repo_path#*://}"; repo_path="${repo_path#*@}"
  repo_path="${repo_path/:/\/}"; repo_path="${repo_path#*/}"
  code=""
  case "$PROVIDER" in
    github)
      code="$(curl -s -o /dev/null -w '%{http_code}' \
        -H "Authorization: Bearer ${token}" -H "Accept: application/vnd.github+json" \
        "${GITHUB_API_BASE:-https://api.github.com}/repos/${repo_path}")" ;;
    gitlab)
      enc="$(printf '%s' "$repo_path" | jq -sRr @uri)"
      code="$(curl -s -o /dev/null -w '%{http_code}' \
        -H "PRIVATE-TOKEN: ${token}" \
        "${GITLAB_API_BASE:-https://gitlab.com/api/v4}/projects/${GITLAB_PROJECT_ID:-$enc}")" ;;
    bitbucket)
      auth="Authorization: Bearer ${token}"
      [ -n "${BITBUCKET_USERNAME:-}" ] && auth="Authorization: Basic $(printf '%s:%s' "$BITBUCKET_USERNAME" "$token" | base64 | tr -d '\n')"
      code="$(curl -s -o /dev/null -w '%{http_code}' -H "$auth" \
        "${BITBUCKET_API_BASE:-https://api.bitbucket.org/2.0}/repositories/${repo_path}")" ;;
  esac
  case "$code" in
    200) ok "Connected to ${PROVIDER} and found the repository" ;;
    401|403) err "${PROVIDER} rejected the token (HTTP $code). Check the token is valid and has repo + pull-request write scope." ;;
    404) err "Repository not found on ${PROVIDER} (HTTP $code). Check git.repo is spelled correctly and the token can see it." ;;
    "" ) warn "Could not run the connection test for ${PROVIDER}." ;;
    *  ) warn "${PROVIDER} returned HTTP $code during the connection test." ;;
  esac
fi

echo
if [ "$ERRORS" -gt 0 ]; then
  printf 'Found \033[31m%d error(s)\033[0m, %d warning(s). Fix the errors above before running a task.\n' "$ERRORS" "$WARNINGS"
  exit 1
fi
printf 'All good \033[32m✓\033[0m  (%d warning(s)). You are ready to submit a task.\n' "$WARNINGS"
exit 0
