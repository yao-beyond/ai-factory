#!/usr/bin/env bash
set -euo pipefail

# Behavior tests for scripts/explainer.sh — the post-review explainer agent is
# a containment boundary (its output ships in result.zip), so the dangerous
# paths get real assertions:
#   1. containment: a CLI that edits other files must not affect the real tree
#   2. fallback: missing CLI -> static explainer, with secrets/paths redacted
#   3. failing CLI -> fallback still ships
#
# Run: bash scripts/tests/explainer-test.sh

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
not_contains() { ! grep -q "$2" "$1"; }

make_fixture() { # make_fixture <root> -> sets WORK, REPO, TID
  TID="TASK-XYZ-123"
  WORK="$1/work"
  REPO="$1/repo"
  mkdir -p "$WORK/$TID" "$REPO/docs/ai"
  printf 'console.log("original");\n' > "$REPO/app.js"
  printf '# 計畫\n- 做一個記帳網頁\n' > "$REPO/docs/ai/IMPLEMENTATION_PLAN.md"
  {
    printf '# 摘要\n- 完成記帳頁\n'
    printf -- '- token ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345\n'
    printf -- '- API_KEY=supersecret999value\n'
    printf -- '- Authorization: Bearer abc123def456ghi789\n'
    printf -- '- 路徑 /Users/bob/secret\n- 任務 TASK-XYZ-123 完成\n'
  } > "$WORK/$TID/summary.md"
  ( cd "$REPO" && git init -q && git add -A \
    && git -c user.email=t@t -c user.name=t commit -qm seed )
}

echo "test 1: containment — a misbehaving CLI cannot touch the real tree"
T1="$(mktemp -d)"
make_fixture "$T1"
MOCK="$T1/mock-claude"
cat > "$MOCK" <<'EOF'
#!/usr/bin/env bash
cat >/dev/null                      # consume the prompt
echo 'console.log("hacked");' > app.js   # tries to edit shipped code
echo "evil" > evil.txt                   # tries to add a shipped file
printf '# 導覽\n粉圓 mock 導覽內容\nleaked: ghp_MOCKLEAKMOCKLEAKMOCKLEAK012345\n' > EXPLAINER.md
EOF
chmod +x "$MOCK"
( cd "$T1/repo" && AI_FACTORY_WORK_DIR="$T1/work" CLAUDE_BIN="$MOCK" \
    bash "$SCRIPTS_DIR/explainer.sh" "$TID" )
assert "real app.js untouched"        contains     "$T1/repo/app.js" 'original'
assert "no injected evil.txt"         test ! -e "$T1/repo/evil.txt"
assert "EXPLAINER.md copied back"     contains     "$T1/repo/EXPLAINER.md" 'mock 導覽內容'
assert "agent-authored text redacted" not_contains "$T1/repo/EXPLAINER.md" 'ghp_MOCKLEAKMOCKLEAKMOCKLEAK012345'
rm -rf "$T1"

echo "test 2: fallback — missing CLI produces redacted static explainer"
T2="$(mktemp -d)"
make_fixture "$T2"
( cd "$T2/repo" && AI_FACTORY_WORK_DIR="$T2/work" CLAUDE_BIN="" \
    PATH="/usr/bin:/bin" bash "$SCRIPTS_DIR/explainer.sh" "$TID" )
E2="$T2/repo/EXPLAINER.md"
assert "fallback explainer written"   contains     "$E2" '白話說明'
assert "plan content included"        contains     "$E2" '記帳網頁'
assert "github token redacted"        not_contains "$E2" 'ghp_ABCDEFGHIJKLMNOPQRSTUVWXYZ012345'
assert "key assignment redacted"      not_contains "$E2" 'supersecret999value'
assert "bearer header redacted"       not_contains "$E2" 'abc123def456ghi789'
assert "home path redacted"           not_contains "$E2" '/Users/bob'
assert "task id redacted"             not_contains "$E2" 'TASK-XYZ-123'
rm -rf "$T2"

echo "test 3: failing CLI — fallback still ships"
T3="$(mktemp -d)"
make_fixture "$T3"
FAILCLI="$T3/fail-claude"
printf '#!/usr/bin/env bash\ncat >/dev/null\nexit 1\n' > "$FAILCLI"
chmod +x "$FAILCLI"
( cd "$T3/repo" && AI_FACTORY_WORK_DIR="$T3/work" CLAUDE_BIN="$FAILCLI" \
    bash "$SCRIPTS_DIR/explainer.sh" "$TID" )
assert "fallback explainer written"   contains "$T3/repo/EXPLAINER.md" '白話說明'
rm -rf "$T3"

echo "test 4: destination-symlink attack on copy-back — real files stay intact"
T4="$(mktemp -d)"
make_fixture "$T4"
ln -s app.js "$T4/repo/EXPLAINER.md"      # hostile pre-planted destination link
MOCK4="$T4/mock-claude"
cat > "$MOCK4" <<'EOF'
#!/usr/bin/env bash
cat >/dev/null
printf '# 導覽\n粉圓 mock 導覽內容\n' > EXPLAINER.md
EOF
chmod +x "$MOCK4"
( cd "$T4/repo" && AI_FACTORY_WORK_DIR="$T4/work" CLAUDE_BIN="$MOCK4" \
    bash "$SCRIPTS_DIR/explainer.sh" "$TID" )
assert "app.js not written through link" contains "$T4/repo/app.js" 'original'
assert "EXPLAINER.md is a regular file"  test ! -L "$T4/repo/EXPLAINER.md"
assert "EXPLAINER.md has mock content"   contains "$T4/repo/EXPLAINER.md" 'mock 導覽內容'
rm -rf "$T4"

echo "test 5: destination-symlink attack on fallback — real files stay intact"
T5="$(mktemp -d)"
make_fixture "$T5"
ln -s app.js "$T5/repo/EXPLAINER.md"
( cd "$T5/repo" && AI_FACTORY_WORK_DIR="$T5/work" CLAUDE_BIN="" \
    PATH="/usr/bin:/bin" bash "$SCRIPTS_DIR/explainer.sh" "$TID" )
assert "app.js not written through link" contains "$T5/repo/app.js" 'original'
assert "EXPLAINER.md is a regular file"  test ! -L "$T5/repo/EXPLAINER.md"
assert "fallback explainer written"      contains "$T5/repo/EXPLAINER.md" '白話說明'
rm -rf "$T5"

if [ "$FAILURES" -gt 0 ]; then
  echo "explainer-test: $FAILURES failure(s)" >&2
  exit 1
fi
echo "explainer-test: all assertions passed"
