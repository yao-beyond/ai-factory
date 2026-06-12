#!/usr/bin/env bash
set -euo pipefail

# Generates EXPLAINER.md — a plain-language "what did the AI build and why"
# document — at the root of the delivered project, so a non-technical user
# opening the result.zip has a guided tour instead of a wall of code.
#
# Invoked by run-task.sh in the package stage (local mode), with the current
# directory already at workspace/repo on the final branch. Must NEVER fail the
# pipeline: any error degrades to a static fallback assembled from the plan
# and change summaries the agents already wrote.
#
# Containment: the AI CLI runs in an ISOLATED COPY of the project and only
# EXPLAINER.md is copied back. The deliverable zip ships the live working
# tree, so a prompt-injected project (e.g. a malicious imported repo) must not
# be able to make this post-review agent edit anything else that would ship.

# Least-privilege env for the AI CLI, same as claude-fix.sh: the child only
# needs its model key. Strip git-provider and messaging secrets so the agent
# can never read — and accidentally echo into the deliverable — them.
unset GITHUB_TOKEN GIT_TOKEN GITLAB_TOKEN BITBUCKET_TOKEN BITBUCKET_USERNAME \
      GITLAB_PROJECT_ID TELEGRAM_BOT_TOKEN TELEGRAM_WEBHOOK_SECRET 2>/dev/null || true

TASK_ID="${1:?TASK_ID required}"
BASE="${AI_FACTORY_WORK_DIR:-/opt/ai-jobs}/$TASK_ID"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/ai-retry.sh"

# Mask credential shapes, host account paths, and the internal task id before
# any pipeline artifact text lands in the deliverable. Mirror of the gateway's
# redaction (TaskService.redactSecrets): URL userinfo, known provider tokens,
# secret-ish assignments, auth headers, Bearer/Basic values, bare AWS secret
# keys, home paths. perl (present on ubuntu/macOS) is required for the
# case-insensitive classes; without it we don't emit artifact text at all.
redact() {
  # LC_ALL=C: a bad inherited locale (seen on macOS shells) can make perl die
  # at startup; byte semantics are fine — the patterns are ASCII, UTF-8 text
  # passes through untouched.
  AIF_TID="$TASK_ID" LC_ALL=C perl -pe '
    s#([a-zA-Z][a-zA-Z0-9+.\-]*://)[^/\s:@]+:[^/\s@]+\@#${1}<redacted>\@#g;
    s#gh[posru]_[A-Za-z0-9]{16,}#<redacted>#g;
    s#glpat-[A-Za-z0-9_\-]{16,}#<redacted>#g;
    s#xox[baprs]-[A-Za-z0-9\-]{10,}#<redacted>#g;
    s#AKIA[0-9A-Z]{16}#<redacted>#g;
    s#sk-[A-Za-z0-9]{20,}#<redacted>#g;
    s#AIza[0-9A-Za-z_\-]{20,}#<redacted>#g;
    s#([A-Za-z0-9_\-]*(?:secret|token|passwd|password|pwd|api[_-]?key|access[_-]?key|private[_-]?key|credential)[A-Za-z0-9_\-]*\s*[:=]\s*)(?:Bearer\s+|Basic\s+)?\S+#${1}<redacted>#gi;
    s#\b(authorization|auth)\b(\s*[:=]\s*).+#${1}${2}<redacted>#gi;
    s#\b(bearer|basic)\s+[A-Za-z0-9._+/=\-]{8,}#${1} <redacted>#gi;
    s#(?<![A-Za-z0-9+/])(?=[A-Za-z0-9+/]{40}(?![A-Za-z0-9+/]))[A-Za-z0-9+/]*[+/][A-Za-z0-9+/]*#<redacted>#g;
    s#(/Users/|/home/)[^/\s]+#${1}<user>#g;
    s#\Q$ENV{AIF_TID}\E#<task>#g;
  '
}

# Static fallback: honest about being auto-assembled, built from artifacts the
# pipeline already produced. Used when the CLI is missing or its run fails.
# Artifact text is redacted + size-capped before it enters the deliverable;
# if redaction isn't available, the artifacts stay out (no leak by default).
write_fallback() {
  # A hostile project could plant EXPLAINER.md as a symlink to a shipped file;
  # a plain `>` would write THROUGH it. Remove the name first, always.
  rm -f EXPLAINER.md
  {
    echo "# 這個專案做了什麼（白話說明）"
    echo
    echo "> 這份說明是由既有的計畫與變更摘要自動彙整的（AI 導覽這次沒有產生成功）。"
    echo
    if command -v perl >/dev/null 2>&1; then
      if [ -f docs/ai/IMPLEMENTATION_PLAN.md ]; then
        echo "## 當初的計畫"
        echo
        head -c 20000 docs/ai/IMPLEMENTATION_PLAN.md | redact
        echo
      fi
      if [ -f "${BASE}/summary.md" ]; then
        echo "## 實際做的變更"
        echo
        head -c 20000 "${BASE}/summary.md" | redact
        echo
      fi
    else
      echo "（此環境缺少 perl，為避免外洩內部資訊，這份說明不附上原始計畫與摘要內容。）"
    fi
  } > EXPLAINER.md
}

CLAUDE="${CLAUDE_BIN:-$(command -v claude 2>/dev/null || command -v claude-code 2>/dev/null || true)}"
if [ -z "$CLAUDE" ]; then
  write_fallback
  exit 0
fi

# Isolated sandbox: copy the project (sans .git) out, run the agent there, and
# copy back ONLY EXPLAINER.md. Whatever the agent edits in the sandbox — by
# bug or by injected instruction — cannot reach the tree the zip ships.
SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/aif-explainer.XXXXXX")"
trap 'rm -rf "$SANDBOX"' EXIT
if ! ( tar --exclude='./.git' -cf - . ) | ( cd "$SANDBOX" && tar -xf - ); then
  write_fallback
  exit 0
fi
# Neutralise a pre-planted EXPLAINER.md (e.g. a symlink a hostile project put
# there) inside the sandbox too, so the agent creates a fresh regular file
# instead of writing through the link onto the sandbox copy.
rm -f "$SANDBOX/EXPLAINER.md"

# || true: explainer quality is best-effort; the deliverable must still ship.
( cd "$SANDBOX" && aif_ai_retry 2 15 -- "$CLAUDE" -p --permission-mode acceptEdits <<'PROMPT'
你是「粉圓」，AI Factory 的吉祥物與導覽員。這個專案剛由 AI 開發完成，即將交付給一位
「不一定會寫程式」的使用者。請在專案根目錄產生一份 EXPLAINER.md（正體中文、白話），
當作打開成果包後的第一份導覽。

內容依序包含這幾節（先給定心丸與行動指引、再誠實揭露、最後才是技術細節）：
1. 「這是什麼？」— 一兩句話講清楚這個成品做什麼、給誰用。
2. 「怎麼打開來用？」— 具體步驟（例如雙擊哪個檔案、或要先裝什麼才能跑）。
3. 「建議你（或工程師）再確認的地方」— 誠實列出 AI 沒把握、簡化過、
   或之後想擴充時要注意的點。不要報喜不報憂。
4. 「AI 做了哪些重要決定？」— 用了什麼技術、為什麼，講人話。
5. 「檔案導覽」— 主要檔案/資料夾各是做什麼的（不用列完，挑重要的）。

背景資料可參考 docs/ai/IMPLEMENTATION_PLAN.md 與 docs/ai/ 下的摘要檔，
但 EXPLAINER.md 內不要引用 docs/ai/ 路徑（交付包不含那個資料夾）。

規則：
- 只新增/覆寫 EXPLAINER.md，不得修改任何其他檔案。
- 全文控制在 120 行以內，語氣親切但內容務實。
- 不要提到內部任務編號、工作目錄路徑或任何金鑰/環境變數。
PROMPT
) || true

# Copy back only the explainer — and only if the sandbox side is a regular
# non-symlink file. Remove the destination name first: a hostile project could
# pre-plant EXPLAINER.md as a symlink to a shipped file, and a plain cp would
# write THROUGH that link into the real tree. The agent-authored text also goes
# through redaction (defence in depth: the model saw the project and could have
# echoed a committed secret); a redaction failure degrades to the fallback.
if [ -f "$SANDBOX/EXPLAINER.md" ] && [ ! -L "$SANDBOX/EXPLAINER.md" ] \
    && [ -s "$SANDBOX/EXPLAINER.md" ]; then
  rm -f EXPLAINER.md
  if command -v perl >/dev/null 2>&1; then
    redact < "$SANDBOX/EXPLAINER.md" > EXPLAINER.md || rm -f EXPLAINER.md
  else
    cp "$SANDBOX/EXPLAINER.md" EXPLAINER.md
  fi
fi

# The CLI may have failed or produced nothing — the deliverable still gets a
# (fallback) explainer either way. A leftover symlink (never a real explainer)
# also lands here and is replaced by the fallback.
[ -f EXPLAINER.md ] && [ ! -L EXPLAINER.md ] && [ -s EXPLAINER.md ] || write_fallback
true
