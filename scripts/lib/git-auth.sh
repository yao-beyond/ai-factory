#!/usr/bin/env bash
# Authenticated git transport for repo mode WITHOUT persisting the credential.
#
# Why: the AI agent runs arbitrary generated code inside the cloned repo. If the
# token sits in repo/.git/config (the only way a plain `git clone <url-with-token>`
# authenticates), the agent can read it and it can even be committed. Instead:
#
#   - The remote URL stored in .git/config is always credential-free.
#   - The token lives in a 0600 file OUTSIDE the repo worktree (cannot be reached
#     by normal repo edits, can never be committed, excluded from result.zip).
#   - The token VALUE is materialised (via `cat`) only inline, for the lifetime
#     of one git network command, through GIT_ASKPASS — never placed in a
#     long-lived environment variable the AI CLI subprocess would inherit.
#
# Parent (run-task.sh) calls aif_git_auth_setup once; every script that runs a
# git network op (clone/fetch/pull/push) sources this file and uses `aif_git`.

# Resolve the lib dir at source time (BASH_SOURCE is reliable here, unlike inside
# a function called from another file) so the askpass helper is always found.
AIF_GIT_AUTH_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]:-$0}")" && pwd)"

# Strip "user[:pass]@" from right after scheme:// in an https URL.
aif_git_clean_url() {
  printf '%s' "${1:-}" | sed -E 's#^([a-zA-Z][a-zA-Z0-9+.-]*://)[^/@]+@#\1#'
}

# Echo the token embedded in a URL's userinfo, if any (user:token or bare token).
aif_git_url_token() {
  local userinfo
  userinfo="$(printf '%s' "${1:-}" | sed -nE 's#^[a-zA-Z][a-zA-Z0-9+.-]*://([^/@]+)@.*#\1#p')"
  [ -z "$userinfo" ] && return 0
  case "$userinfo" in
    *:*) printf '%s' "${userinfo#*:}" ;;  # user:token -> token
    *)   printf '%s' "$userinfo" ;;       # bare token
  esac
}

# Resolve token + clean URL, stash the token in a 0600 file, export pointers.
# Arg $1 = repo URL (possibly credentialed). Sets AIF_GIT_CLEAN_URL. Call it
# DIRECTLY (not in $(...)) — a command substitution would run the exports in a
# subshell and they would not reach the caller / child processes:
#   aif_git_auth_setup "$REPO_URL"; REPO_URL="$AIF_GIT_CLEAN_URL"
aif_git_auth_setup() {
  local url="${1:?repo url required}" token user
  AIF_GIT_CLEAN_URL="$(aif_git_clean_url "$url")"
  # Token source: explicit provider env first (the intended secret store), then
  # any credentials the operator embedded in the URL.
  token="${GIT_TOKEN:-${GITHUB_TOKEN:-${GITLAB_TOKEN:-${BITBUCKET_TOKEN:-}}}}"
  [ -z "$token" ] && token="$(aif_git_url_token "$url")"
  case "${GIT_PROVIDER:-}" in
    gitlab)    user="oauth2" ;;
    bitbucket) user="${BITBUCKET_USERNAME:-x-token-auth}" ;;
    *)         user="x-access-token" ;;  # github / default; PATs ignore username
  esac
  AIF_GIT_USER="$user"
  AIF_GIT_TOKEN_FILE="${AIF_GIT_TOKEN_FILE:-${BASE:-.}/.aif-git-token}"
  AIF_ASKPASS_SCRIPT="${AIF_GIT_AUTH_LIB_DIR}/git-askpass.sh"
  chmod +x "$AIF_ASKPASS_SCRIPT" 2>/dev/null || true
  if [ -n "$token" ]; then
    ( umask 077; printf '%s' "$token" > "$AIF_GIT_TOKEN_FILE" )
  fi
  export AIF_GIT_USER AIF_GIT_TOKEN_FILE AIF_ASKPASS_SCRIPT AIF_GIT_CLEAN_URL
}

# Run a git command with transport auth when a token file is present. The token
# is read inline and exposed only to this git process and its askpass child —
# never to the wider environment or the AI CLI. Falls back to plain git (public
# repos, local mode) when no token is staged.
aif_git() {
  if [ -n "${AIF_GIT_TOKEN_FILE:-}" ] && [ -s "${AIF_GIT_TOKEN_FILE:-/nonexistent}" ]; then
    GIT_ASKPASS="${AIF_ASKPASS_SCRIPT}" \
    GIT_TERMINAL_PROMPT=0 \
    AIF_GIT_PASSWORD="$(cat "${AIF_GIT_TOKEN_FILE}")" \
    git "$@"
  else
    git "$@"
  fi
}

# Remove the staged token file. Call from the pipeline's exit trap.
aif_git_auth_cleanup() {
  [ -n "${AIF_GIT_TOKEN_FILE:-}" ] && rm -f "${AIF_GIT_TOKEN_FILE}" 2>/dev/null || true
}
