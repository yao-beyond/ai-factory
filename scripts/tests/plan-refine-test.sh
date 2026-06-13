#!/usr/bin/env bash
set -euo pipefail

# Behavior tests for scripts/plan-refine.sh — the confirm-gate plan polisher.
# Asserts the marker protocol (request -> response | failed), the data-only
# blockquote wrapping of the user draft, and that failures never leave the
# protocol stuck.
#
# Run: bash scripts/tests/plan-refine-test.sh

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0

assert() { # assert <desc> <condition...>
  local desc="$1"; shift
  if "$@"; then
    echo "  ok: $desc"
  else
    echo "  FAIL: $desc" >&2
    FAILURES=$((FAILURES + 1))
  fi
}

contains() { grep -q "$2" "$1"; }

make_fixture() { # sets WORK, TID
  TID="TASK-REFINE-1"
  WORK="$1/work"
  mkdir -p "$WORK/$TID"
  printf '做一個記帳網頁\n底色要藍的\n' > "$WORK/$TID/refine.request"
}

echo "test 1: success — response published, request consumed, draft fed as data"
T1="$(mktemp -d)"
make_fixture "$T1"
MOCK="$T1/mock-claude"
cat > "$MOCK" <<EOF
#!/usr/bin/env bash
cat > "$T1/prompt-seen.txt"
printf '## 目標\\n- 做一個記帳網頁（底色藍）\\n'
EOF
chmod +x "$MOCK"
AI_FACTORY_WORK_DIR="$T1/work" CLAUDE_BIN="$MOCK" bash "$SCRIPTS_DIR/plan-refine.sh" "$TID"
assert "response published"          contains "$T1/work/$TID/refine.response" '記帳網頁'
assert "request consumed"            test ! -e "$T1/work/$TID/refine.request"
assert "no failed marker"            test ! -e "$T1/work/$TID/refine.failed"
assert "draft wrapped as blockquote" contains "$T1/prompt-seen.txt" '^> 底色要藍的'
rm -rf "$T1"

echo "test 2: CLI failure — failed marker, protocol not stuck"
T2="$(mktemp -d)"
make_fixture "$T2"
FAILCLI="$T2/fail-claude"
printf '#!/usr/bin/env bash\ncat >/dev/null\nexit 1\n' > "$FAILCLI"
chmod +x "$FAILCLI"
AI_FACTORY_WORK_DIR="$T2/work" CLAUDE_BIN="$FAILCLI" bash "$SCRIPTS_DIR/plan-refine.sh" "$TID"
assert "failed marker written"       test -e "$T2/work/$TID/refine.failed"
assert "request consumed"            test ! -e "$T2/work/$TID/refine.request"
assert "no response"                 test ! -e "$T2/work/$TID/refine.response"
rm -rf "$T2"

echo "test 3: missing CLI — failed marker, protocol not stuck"
T3="$(mktemp -d)"
make_fixture "$T3"
AI_FACTORY_WORK_DIR="$T3/work" CLAUDE_BIN="" PATH="/usr/bin:/bin" \
  bash "$SCRIPTS_DIR/plan-refine.sh" "$TID"
assert "failed marker written"       test -e "$T3/work/$TID/refine.failed"
assert "request consumed"            test ! -e "$T3/work/$TID/refine.request"
rm -rf "$T3"

echo "test 4: no request — no-op"
T4="$(mktemp -d)"
mkdir -p "$T4/work/TASK-REFINE-1"
AI_FACTORY_WORK_DIR="$T4/work" CLAUDE_BIN="/nonexistent" \
  bash "$SCRIPTS_DIR/plan-refine.sh" "TASK-REFINE-1"
assert "nothing produced" test -z "$(ls "$T4/work/TASK-REFINE-1")"
rm -rf "$T4"

if [ "$FAILURES" -gt 0 ]; then
  echo "plan-refine-test: $FAILURES failure(s)" >&2
  exit 1
fi
echo "plan-refine-test: all assertions passed"
