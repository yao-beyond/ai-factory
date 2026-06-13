#!/usr/bin/env bash
set -uo pipefail

# Independently runs the delivered project's OWN test command on the current
# working tree and reports a trustworthy, machine-checkable result — NOT the AI
# agent's self-report. This is what makes the governance tests-pass gate real:
# without it the gate could only ever see a hardcoded "passed".
#
# Prints exactly one of these as the LAST stdout line:
#   pass     a test command ran and succeeded
#   fail     a test command ran and failed (includes timing out)
#   none     no test framework manifest exists in the project
#   unknown  a test framework manifest exists but could NOT be run (missing
#            runner/tooling) — the gateway treats this as fail-closed
# Full output goes to ${BASE}/tests.log. Never errors the caller (always exit 0);
# the printed status is the signal. Bounded by TEST_TIMEOUT_SECONDS even when no
# `timeout` binary is present (portable watchdog).
#
# Note: this executes project code in the same (un-sandboxed in Phase 1)
# workspace the AI agents already ran in — it is not a new trust boundary. The
# execution-layer sandbox is a later phase (see docs/design/governance-runtime.md §0).

TIMEOUT="${TEST_TIMEOUT_SECONDS:-600}"
LOG="${BASE:-/tmp}/tests.log"
: > "$LOG" 2>/dev/null || true

RC=0
# Project tests are UNTRUSTED code. Scrub governance/transport credentials from
# their environment so a malicious test cannot read the promote token or any
# secret and act on the gateway. (The operator secret is already absent from the
# pipeline env; this is defence in depth + covers the scoped promote token.)
SCRUB=(env -u AIF_INTERNAL_SECRET -u AIF_PROMOTE_TOKEN -u AIF_GATEWAY_URL
       -u GIT_TOKEN -u GITHUB_TOKEN -u GITLAB_TOKEN -u BITBUCKET_TOKEN)
# Run a command with a hard time bound. Prefer a real timeout binary; otherwise
# use a portable watchdog so a hanging test cannot wedge the pipeline.
run() {
  if command -v timeout >/dev/null 2>&1; then
    timeout "$TIMEOUT" "${SCRUB[@]}" "$@" >>"$LOG" 2>&1; RC=$?; return
  fi
  if command -v gtimeout >/dev/null 2>&1; then
    gtimeout "$TIMEOUT" "${SCRUB[@]}" "$@" >>"$LOG" 2>&1; RC=$?; return
  fi
  "${SCRUB[@]}" "$@" >>"$LOG" 2>&1 &
  local cmd_pid=$!
  # Redirect the watchdog's fds to /dev/null: otherwise it inherits this process's
  # stdout and a `$(run-tests.sh)` command substitution would block on the
  # backgrounded sleep until TIMEOUT (classic bash pipe-held-open gotcha).
  ( sleep "$TIMEOUT"; kill -TERM "$cmd_pid" 2>/dev/null; sleep 5; kill -KILL "$cmd_pid" 2>/dev/null ) \
    >/dev/null 2>&1 &
  local watch_pid=$!
  wait "$cmd_pid" 2>/dev/null; RC=$?      # killed-by-watchdog => nonzero => "fail"
  kill "$watch_pid" 2>/dev/null; wait "$watch_pid" 2>/dev/null || true
}
verdict() { [ "$RC" -eq 0 ] && echo pass || echo fail; }
has() { command -v "$1" >/dev/null 2>&1; }

status=none        # set to pass|fail once a command actually runs
have_manifest=0    # a recognised test framework manifest was found
ran=0              # a test command actually executed

# consider <manifest_present 0|1> <runner_available 0|1> <cmd...>
# Runs the command (sets status) only when both the manifest and its runner are
# present. A manifest with no runner is recorded as "found but unrun".
consider() {
  local mpresent="$1" rpresent="$2"; shift 2
  [ "$ran" -eq 1 ] && return 0
  [ "$mpresent" -eq 1 ] || return 0
  have_manifest=1
  [ "$rpresent" -eq 1 ] || return 0
  run "$@"; status="$(verdict)"; ran=1
}

# --- npm (only when a non-placeholder test script is declared) ---
npm_manifest=0
if [ -f package.json ]; then
  if has jq; then
    if jq -e '.scripts.test' package.json >/dev/null 2>&1; then
      case "$(jq -r '.scripts.test' package.json)" in
        *"no test specified"*) ;;     # npm's default placeholder is not a test
        *) npm_manifest=1 ;;
      esac
    fi
  else
    # package.json present but we cannot read whether it declares tests -> a
    # framework MAY exist that we can't run: record as found-but-unrun.
    have_manifest=1
  fi
fi
consider "$npm_manifest" "$(has npm && echo 1 || echo 0)" npm test --silent

# --- maven ---
consider "$([ -f pom.xml ] && echo 1 || echo 0)" "$(has mvn && echo 1 || echo 0)" mvn -q -B test

# --- gradle (wrapper preferred) ---
if [ -f build.gradle ] || [ -f build.gradle.kts ]; then
  if [ "$ran" -eq 0 ]; then
    have_manifest=1
    if [ -x ./gradlew ]; then run ./gradlew test -q; status="$(verdict)"; ran=1
    elif has gradle; then run gradle test -q; status="$(verdict)"; ran=1
    fi
  fi
fi

# --- pytest ---
pytest_manifest=0
if [ -f pyproject.toml ] || [ -f pytest.ini ] || [ -f setup.cfg ] \
    || ls tests/test_*.py test_*.py >/dev/null 2>&1; then
  pytest_manifest=1
fi
consider "$pytest_manifest" "$(has pytest && echo 1 || echo 0)" pytest -q

# --- make ---
make_manifest=0
if [ -f Makefile ] && grep -qE '^test:' Makefile; then make_manifest=1; fi
consider "$make_manifest" "$(has make && echo 1 || echo 0)" make test

# A manifest was found but nothing could be run -> unknown (fail-closed), NOT
# none. "none" is reserved for genuinely no test framework at all.
if [ "$ran" -eq 0 ] && [ "$have_manifest" -eq 1 ]; then
  status=unknown
fi

printf '%s\n' "$status"
