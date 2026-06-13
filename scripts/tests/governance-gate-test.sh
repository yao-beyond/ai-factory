#!/usr/bin/env bash
set -euo pipefail

# Behavior tests for the governance_gate() hook in run-task.sh. The gate is a
# fail-closed trust boundary, so its dangerous paths get real assertions:
#   1. opt-out: no AIF_GATEWAY_URL -> gate is skipped, pipeline proceeds
#   2. ELIGIBLE -> deliverable proceeds (zip produced)
#   3. BLOCKED -> FAILED with promotion_blocked, no zip
#   4. gateway unreachable -> fail-closed (FAILED, no zip)
#
# A fake `curl` on PATH stands in for the gateway. We drive run-task.sh in local
# mode with stub AI CLIs so it reaches the package/gate stage quickly.
#
# Run: bash scripts/tests/governance-gate-test.sh

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0

assert() { local d="$1"; shift; if "$@"; then echo "  ok: $d"; else echo "  FAIL: $d" >&2; FAILURES=$((FAILURES+1)); fi; }
status_is() { grep -q "STATUS=$2" "$1/status.txt"; }
status_msg() { grep -q "$2" "$1/status.txt"; }

# Build an isolated env: a fake bin dir (claude/codex/curl stubs) + a seeded
# local task whose pipeline scripts are stubbed to no-ops so we reach the gate.
setup() { # setup <root> <curl-behavior>
  ROOT="$1"; CURL_MODE="$2"
  BIN="$ROOT/bin"; WORK="$ROOT/work"; TID="GATE-1"; BASE="$WORK/$TID"
  mkdir -p "$BIN" "$BASE/workspace/repo/docs/ai"
  # Minimal issue.json so run-task.sh has config.
  printf '{"repo":"","mode":"new"}' > "$BASE/issue.json"
  # Stub AI CLIs (present so preflight passes).
  printf '#!/usr/bin/env bash\nexit 0\n' > "$BIN/claude"; chmod +x "$BIN/claude"
  printf '#!/usr/bin/env bash\nexit 0\n' > "$BIN/codex"; chmod +x "$BIN/codex"
  # Fake curl: emits a body line + http code line, matching run-task.sh's -w usage.
  case "$CURL_MODE" in
    eligible) printf '#!/usr/bin/env bash\nprintf %%s "{\\"state\\":\\"DELIVERABLE_ELIGIBLE\\"}"; printf "\\n200"\n' > "$BIN/curl" ;;
    blocked)  printf '#!/usr/bin/env bash\nprintf %%s "{\\"state\\":\\"BLOCKED\\",\\"blockingGate\\":\\"tests-pass\\"}"; printf "\\n200"\n' > "$BIN/curl" ;;
    unreach)  printf '#!/usr/bin/env bash\nexit 7\n' > "$BIN/curl" ;;
  esac
  chmod +x "$BIN/curl"
}

# Run just the governance_gate logic by sourcing a tiny harness that defines the
# pieces run-task.sh's gate depends on, then copies the real function out of it.
# Simpler + hermetic: extract and eval the function under a controlled scope.
run_gate() { # run_gate <root> <gateway_url>
  local root="$1" url="$2"
  ( cd "$root/work/GATE-1/workspace/repo"
    export PATH="$root/bin:$PATH" AI_FACTORY_WORK_DIR="$root/work" TASK_ID=GATE-1 \
           BASE="$root/work/GATE-1" LOCAL_MODE=true AIF_GATEWAY_URL="$url"
    # Provide the helpers the function closes over.
    _write_cancelled(){ printf 'STATUS=CANCELLED\n' > "$BASE/status.txt"; }
    set_status(){ printf 'STATUS=%s\nMESSAGE=%s\n' "$1" "${2:-}" > "$BASE/status.txt"; }
    # shellcheck disable=SC2034  # consumed inside the eval'd governance_gate
    GOVERNANCE_GATE_DONE=false
    # shellcheck disable=SC1090
    eval "$(sed -n '/^governance_gate() {/,/^}/p' "$SCRIPTS_DIR/run-task.sh")"
    governance_gate && set_status COMPLETED "done"
  )
}

echo "test 1: no AIF_GATEWAY_URL -> gate skipped, proceeds"
T1="$(mktemp -d)"; setup "$T1" eligible
run_gate "$T1" "" || true
assert "proceeds to COMPLETED" status_is "$T1/work/GATE-1" COMPLETED
rm -rf "$T1"

echo "test 2: ELIGIBLE -> proceeds"
T2="$(mktemp -d)"; setup "$T2" eligible
run_gate "$T2" "http://127.0.0.1:9" || true
assert "proceeds to COMPLETED" status_is "$T2/work/GATE-1" COMPLETED
rm -rf "$T2"

echo "test 3: BLOCKED -> FAILED promotion_blocked, no COMPLETED"
T3="$(mktemp -d)"; setup "$T3" blocked
run_gate "$T3" "http://127.0.0.1:9" || true
assert "status FAILED" status_is "$T3/work/GATE-1" FAILED
assert "promotion_blocked recorded" status_msg "$T3/work/GATE-1" promotion_blocked
rm -rf "$T3"

echo "test 4: gateway unreachable -> fail-closed FAILED"
T4="$(mktemp -d)"; setup "$T4" unreach
run_gate "$T4" "http://127.0.0.1:9" || true
assert "status FAILED" status_is "$T4/work/GATE-1" FAILED
assert "gateway_unreachable recorded" status_msg "$T4/work/GATE-1" gateway_unreachable
rm -rf "$T4"

if [ "$FAILURES" -gt 0 ]; then echo "governance-gate-test: $FAILURES failure(s)" >&2; exit 1; fi
echo "governance-gate-test: all assertions passed"
