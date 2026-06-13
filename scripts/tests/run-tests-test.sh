#!/usr/bin/env bash
set -uo pipefail

# Tests for scripts/run-tests.sh — the independent test runner that produces the
# real pass|fail|none signal the governance tests-pass gate depends on.
#
# Run: bash scripts/tests/run-tests-test.sh

SCRIPTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAILURES=0
assert() { local d="$1"; shift; if "$@"; then echo "  ok: $d"; else echo "  FAIL: $d" >&2; FAILURES=$((FAILURES+1)); fi; }
status_of() { ( cd "$1" && BASE="$1" bash "$SCRIPTS_DIR/run-tests.sh" 2>/dev/null | tail -n1 ); }
eq() { [ "$1" = "$2" ]; }

echo "test 1: no recognised test framework -> none"
T1="$(mktemp -d)"; printf 'hello' > "$T1/index.html"
assert "reports none" eq "$(status_of "$T1")" none
rm -rf "$T1"

echo "test 2: Makefile passing test -> pass"
T2="$(mktemp -d)"; printf 'test:\n\t@true\n' > "$T2/Makefile"
assert "reports pass" eq "$(status_of "$T2")" pass
rm -rf "$T2"

echo "test 3: Makefile failing test -> fail"
T3="$(mktemp -d)"; printf 'test:\n\t@false\n' > "$T3/Makefile"
assert "reports fail" eq "$(status_of "$T3")" fail
rm -rf "$T3"

echo "test 4: npm placeholder 'no test specified' -> none"
T4="$(mktemp -d)"
printf '{"scripts":{"test":"echo \\"Error: no test specified\\" && exit 1"}}' > "$T4/package.json"
# Only meaningful if jq is present (run-tests.sh requires it to read package.json).
if command -v jq >/dev/null 2>&1; then
  assert "placeholder treated as none" eq "$(status_of "$T4")" none
else
  echo "  skip: jq not installed"
fi
rm -rf "$T4"

echo "test 5: manifest present but runner unavailable -> unknown (not none)"
T5="$(mktemp -d)"; printf '<project/>' > "$T5/pom.xml"
# Run with a coreutils-only PATH so the maven runner is absent. Skip if mvn still
# resolves there (some systems put it in /usr/bin).
if PATH="/usr/bin:/bin" command -v mvn >/dev/null 2>&1; then
  echo "  skip: mvn present on minimal PATH"
else
  out="$( cd "$T5" && BASE="$T5" PATH="/usr/bin:/bin" bash "$SCRIPTS_DIR/run-tests.sh" 2>/dev/null | tail -n1 )"
  assert "reports unknown" eq "$out" unknown
fi
rm -rf "$T5"

if [ "$FAILURES" -gt 0 ]; then echo "run-tests-test: $FAILURES failure(s)" >&2; exit 1; fi
echo "run-tests-test: all assertions passed"
