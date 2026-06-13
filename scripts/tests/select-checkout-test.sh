#!/usr/bin/env bash
set -uo pipefail

# Regression tests for the "select stage fails: untracked .omc/ would be
# overwritten by checkout" bug. Two independent mechanisms must hold:
#   Fix A: .git/info/exclude keeps AI-tooling dirs out of `git add -A`, so they
#          never get committed into candidate branches in the first place.
#   Fix B: select-best-branch cleans untracked tooling dirs before the final
#          checkout, so a pre-existing collision can't abort the run.
#
# Run: bash scripts/tests/select-checkout-test.sh

FAILURES=0
assert() { local d="$1"; shift; if "$@"; then echo "  ok: $d"; else echo "  FAIL: $d" >&2; FAILURES=$((FAILURES+1)); fi; }
gitq() { git -c user.email=t@t -c user.name=t "$@" >/dev/null 2>&1; }

echo "test 1 (Fix A): .git/info/exclude keeps .omc out of git add -A"
T1="$(mktemp -d)"
( cd "$T1" && git init -q
  # Mirror run-task.sh's exclude write.
  printf '.omc/\n.claude/\n.codex/\n' >> .git/info/exclude
  mkdir -p .omc && echo '{}' > .omc/project-memory.json
  echo 'console.log(1)' > app.js
  git -c user.email=t@t -c user.name=t add -A
)
assert "app.js staged"        bash -c "cd '$T1' && git diff --cached --name-only | grep -qx app.js"
assert ".omc NOT staged"      bash -c "cd '$T1' && ! git diff --cached --name-only | grep -q '^\\.omc/'"
rm -rf "$T1"

echo "test 2 (Fix B): clean untracked tooling dir before checkout avoids abort"
T2="$(mktemp -d)"
( cd "$T2" && git init -q -b main
  echo base > base.txt
  gitq add -A && gitq commit -m base
  # A candidate branch that (wrongly) committed .omc/ — the legacy state.
  gitq checkout -b dev
  mkdir -p .omc && echo 'committed' > .omc/project-memory.json
  echo app > app.js
  gitq add -A && gitq commit -m candidate
  gitq checkout main
  # Working tree now has an UNTRACKED .omc/project-memory.json colliding with dev's.
  mkdir -p .omc && echo 'untracked' > .omc/project-memory.json
)
# Without the fix, this checkout aborts (rc != 0).
( cd "$T2" && git checkout dev >/dev/null 2>&1 )
assert "bare checkout aborts on collision" test $? -ne 0
# With the fix: clean the tooling dir first, then checkout succeeds.
( cd "$T2" && git clean -fdq -- .omc .claude .codex 2>/dev/null; git checkout -B final dev >/dev/null 2>&1 )
assert "checkout succeeds after clean" test $? -eq 0
assert "candidate file present after checkout" test -f "$T2/app.js"
rm -rf "$T2"

echo "test 3 (import gap): exclude set BEFORE the import commit keeps .omc untracked"
T3="$(mktemp -d)"
( cd "$T3" && git init -q -b main
  # Simulate run-task.sh import order: init -> exclude -> add -> commit, with an
  # imported project that ALREADY contains .omc/ (the gap Codex flagged).
  mkdir -p .omc && echo 'imported-state' > .omc/project-memory.json
  echo '<html>' > index.html
  ex="$(git rev-parse --git-common-dir)/info/exclude"
  printf '.omc/\n.claude/\n.codex/\n' >> "$ex"
  git -c user.email=t@t -c user.name=t add -A
  git -c user.email=t@t -c user.name=t commit -q -m import
)
assert "index.html committed"      bash -c "cd '$T3' && git ls-files | grep -qx index.html"
assert ".omc NOT committed"        bash -c "cd '$T3' && ! git ls-files | grep -q '^\\.omc/'"
rm -rf "$T3"

if [ "$FAILURES" -gt 0 ]; then echo "select-checkout-test: $FAILURES failure(s)" >&2; exit 1; fi
echo "select-checkout-test: all assertions passed"
