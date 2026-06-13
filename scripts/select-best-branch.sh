#!/usr/bin/env bash
set -euo pipefail

# Evidence-based selection: picks the best dev candidate from deterministic,
# reproducible checks — not "biggest diff wins", and not AI self-praise.
#
# Per candidate we collect:
#   - summary present    (committed CLAUDE_SUMMARY_<i>.md = "did real work")
#   - diff stats         (files / insertions / deletions vs the plan branch)
#   - usable preview     (root index.html that actually has a <body>)
#   - conflict markers   (unresolved <<<<<<< / >>>>>>>)                -> hard fail
#   - secret findings    (token-shaped strings in impl/config files)   -> hard fail
#   - network submission (fetch/XHR/beacon/WebSocket/form-to-http when
#                         the discovery capability boundary forbids it) -> hard fail
#   - external scripts   (<script src="http...">)                      -> -100 each
#
# Fixed scoring policy (documented in the report, no AI judgement involved):
#   summary +1000, usable index.html +200, + min(insertions, 400) so verbosity
#   can't buy the win, -100 per external script. Hard-fail candidates are
#   disqualified; ties go to the lower agent number. If EVERY candidate is
#   disqualified we still ship the least-bad one but the report says so loudly
#   (a reviewable draft beats a silent total failure — the human gate decides).
#
# Outputs:
#   docs/ai/SELECTION_REPORT.md   committed on the final branch
#   docs/ai/SELECTED_AGENT.txt    as before
#   ${BASE}/selection_report.md   served by the gateway UI
#   ${BASE}/evidence.json         machine-readable (written when jq is available)
#
# BEST_AGENT env still wins (manual override) and is recorded in the report.

TASK_ID="${1:?TASK_ID required}"
TARGET_BRANCH="${TARGET_BRANCH:-main}"
MAX_AGENTS="${MAX_AGENTS:-3}"
PLAN_BRANCH="ai/${TASK_ID}/plan"
BASE_DIR="${AI_FACTORY_WORK_DIR:-/opt/ai-jobs}/${TASK_ID}"

# Diff base: the plan branch if present, else the target branch. In local mode
# there is no origin, so never fall back to origin/<branch>.
if git rev-parse --verify "$PLAN_BRANCH" >/dev/null 2>&1; then
  BASE_REF="$(git rev-parse "$PLAN_BRANCH")"
elif [ "${PROJECT_MODE:-existing}" = "local" ]; then
  BASE_REF="$(git rev-parse "$TARGET_BRANCH")"
else
  BASE_REF="$(git rev-parse "origin/${TARGET_BRANCH}")"
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/git/branch-guard.sh"
# shellcheck source=/dev/null
source "${SCRIPT_DIR}/lib/git-auth.sh"

# ---------------------------------------------------------------------------
# Scan configuration
# ---------------------------------------------------------------------------

# Only implementation files count for violations: scanning docs/README/reports
# would flag prose that merely *mentions* fetch() (Codex review: false-positive
# trap). Generated artifacts (docs/ai) are filtered out of results below.
IMPL_INCLUDES=(--include='*.html' --include='*.htm' --include='*.js' --include='*.mjs'
  --include='*.cjs' --include='*.ts' --include='*.jsx' --include='*.tsx'
  --include='*.css' --include='*.vue' --include='*.svelte')
# Secrets can also hide in config shipped with the deliverable.
SECRET_INCLUDES=("${IMPL_INCLUDES[@]}" --include='*.json' --include='*.yml'
  --include='*.yaml' --include='*.env' --include='*.env.*' --include='*.txt')
EXCLUDE_DIRS=(--exclude-dir=.git --exclude-dir=node_modules --exclude-dir=vendor
  --exclude-dir=dist --exclude-dir=build --exclude-dir=coverage
  --exclude-dir=.cache --exclude-dir=.next --exclude-dir=.vite)

# Network-submission APIs (hard fail when the capability boundary forbids them).
NET_PAT='fetch[[:space:]]*\(|XMLHttpRequest|axios[.(]|navigator\.sendBeacon|new[[:space:]]+WebSocket|<form[^>]*action[[:space:]]*=[[:space:]]*["'"'"']https?:'
# Loading third-party script is an exfiltration vector but also a common CDN
# pattern, so it only costs points instead of disqualifying.
EXT_SCRIPT_PAT='<script[^>]*src[[:space:]]*=[[:space:]]*["'"'"']https?:'
# Unresolved merge conflict markers ("=======" alone is too noisy to include).
CONFLICT_PAT='^<<<<<<< |^>>>>>>> '
# Token shapes (GitHub/GitLab/Slack/AWS/OpenAI/Google + private keys). These are
# REGEXES, not sample tokens, so secret scanners don't trip on this script itself.
SECRET_PAT='ghp_[A-Za-z0-9]{16,}|gho_[A-Za-z0-9]{16,}|glpat-[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|xox[baprs]-[A-Za-z0-9-]{10,}|sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z_-]{20,}|-----BEGIN [A-Z ]*PRIVATE KEY'

# Does the discovery capability boundary forbid network submission?
BOUNDARY_FORBIDS_NET=false
if [ -n "${CAPABILITY_BOUNDARY:-}" ] && command -v jq >/dev/null 2>&1; then
  verdict="$(printf '%s' "$CAPABILITY_BOUNDARY" | jq -r \
    'if (.handoff.networkSubmissionAllowed == false) or (.submissionMode == "visitor_manual_handoff")
     then "forbid" else "allow" end' 2>/dev/null || echo allow)"
  [ "$verdict" = "forbid" ] && BOUNDARY_FORBIDS_NET=true
fi

# Count occurrences of an ERE in the candidate worktree (impl scope by default).
# Results under docs/ai (pipeline metadata) never count.
scan_count() {
  local dir="$1" pattern="$2"; shift 2
  ( cd "$dir" && grep -rEn "$pattern" . "$@" "${EXCLUDE_DIRS[@]}" 2>/dev/null || true ) \
    | grep -cv '^\./docs/ai/' || true
}

# ---------------------------------------------------------------------------
# Evidence collection
# ---------------------------------------------------------------------------

declare -a EV_EXISTS EV_SUMMARY EV_FILES EV_INS EV_DEL EV_INDEX EV_CONFLICT \
           EV_SECRET EV_NET EV_EXTSCRIPT EV_SCORE EV_DQ EV_DQ_REASON

for i in $(seq 1 "$MAX_AGENTS"); do
  branch="ai/${TASK_ID}/dev-${i}"
  EV_EXISTS[i]=false; EV_SUMMARY[i]=false; EV_FILES[i]=0; EV_INS[i]=0; EV_DEL[i]=0
  EV_INDEX[i]=false; EV_CONFLICT[i]=0; EV_SECRET[i]=0; EV_NET[i]=0
  EV_EXTSCRIPT[i]=0; EV_SCORE[i]=-1; EV_DQ[i]=false; EV_DQ_REASON[i]=""
  git rev-parse --verify "$branch" >/dev/null 2>&1 || continue
  EV_EXISTS[i]=true

  if git show "${branch}:docs/ai/CLAUDE_SUMMARY_${i}.md" >/dev/null 2>&1; then
    EV_SUMMARY[i]=true
  fi

  shortstat="$(git diff --shortstat "$BASE_REF...$branch" 2>/dev/null || true)"
  EV_FILES[i]="$(printf '%s' "$shortstat" | grep -oE '[0-9]+ files? changed' | grep -oE '[0-9]+' || echo 0)"
  EV_INS[i]="$(printf '%s' "$shortstat" | grep -oE '[0-9]+ insertions?' | grep -oE '[0-9]+' || echo 0)"
  EV_DEL[i]="$(printf '%s' "$shortstat" | grep -oE '[0-9]+ deletions?' | grep -oE '[0-9]+' || echo 0)"

  # Materialise the candidate in a throwaway detached worktree for content checks.
  wt="$(mktemp -d "${TMPDIR:-/tmp}/aif-eval-XXXXXX")"
  if git worktree add --detach -q "$wt" "$branch" 2>/dev/null; then
    if [ -s "${wt}/index.html" ] && grep -qi '<body' "${wt}/index.html" 2>/dev/null; then
      EV_INDEX[i]=true
    fi
    EV_CONFLICT[i]="$(scan_count "$wt" "$CONFLICT_PAT" "${IMPL_INCLUDES[@]}")"
    EV_SECRET[i]="$(scan_count "$wt" "$SECRET_PAT" "${SECRET_INCLUDES[@]}")"
    EV_NET[i]="$(scan_count "$wt" "$NET_PAT" "${IMPL_INCLUDES[@]}")"
    EV_EXTSCRIPT[i]="$(scan_count "$wt" "$EXT_SCRIPT_PAT" "${IMPL_INCLUDES[@]}")"
    git worktree remove --force "$wt" 2>/dev/null || true
  fi
  rm -rf "$wt" 2>/dev/null || true

  # Hard-fail conditions first (a +1000 summary must never outweigh these).
  reasons=""
  [ "${EV_SECRET[i]}" -gt 0 ] && reasons="${reasons}夾帶疑似隱私鑰匙（${EV_SECRET[i]} 處）；"
  [ "${EV_CONFLICT[i]}" -gt 0 ] && reasons="${reasons}留下未解的程式衝突標記（${EV_CONFLICT[i]} 處）；"
  if [ "$BOUNDARY_FORBIDS_NET" = true ] && [ "${EV_NET[i]}" -gt 0 ]; then
    reasons="${reasons}能力邊界禁止對外送資料，但偵測到 ${EV_NET[i]} 處相關程式；"
  fi
  if [ -n "$reasons" ]; then
    EV_DQ[i]=true
    EV_DQ_REASON[i]="$reasons"
  fi

  # Fixed score (also computed for disqualified candidates: least-bad fallback).
  ins_capped="${EV_INS[i]}"; [ "$ins_capped" -gt 400 ] && ins_capped=400
  score=$ins_capped
  [ "${EV_SUMMARY[i]}" = true ] && score=$((score + 1000))
  [ "${EV_INDEX[i]}" = true ] && score=$((score + 200))
  score=$((score - EV_EXTSCRIPT[i] * 100))
  EV_SCORE[i]=$score
done

# ---------------------------------------------------------------------------
# Selection
# ---------------------------------------------------------------------------

pick_best() { # $1=qualified_only -> echoes index or nothing
  local only="$1" best=-999999 idx="" j
  for j in $(seq 1 "$MAX_AGENTS"); do
    [ "${EV_EXISTS[j]}" = true ] || continue
    [ "$only" = true ] && [ "${EV_DQ[j]}" = true ] && continue
    if [ "${EV_SCORE[j]}" -gt "$best" ]; then best="${EV_SCORE[j]}"; idx="$j"; fi
  done
  [ -n "$idx" ] && echo "$idx"
}

MANUAL_OVERRIDE=false
ALL_DISQUALIFIED=false
if [ -n "${BEST_AGENT:-}" ]; then
  CHOSEN="$BEST_AGENT"
  MANUAL_OVERRIDE=true
else
  CHOSEN="$(pick_best true || true)"
  if [ -z "$CHOSEN" ]; then
    # Every candidate failed a hard check: ship the least-bad draft, but the
    # report (and the human gate) make the failure impossible to miss.
    CHOSEN="$(pick_best false || true)"
    [ -n "$CHOSEN" ] && ALL_DISQUALIFIED=true
  fi
  CHOSEN="${CHOSEN:-1}"
fi

SOURCE="ai/${TASK_ID}/dev-${CHOSEN}"
if ! git rev-parse --verify "$SOURCE" >/dev/null 2>&1; then
  echo "ERROR: chosen branch $SOURCE does not exist" >&2
  exit 4
fi

echo "Selected $SOURCE for final (score=${EV_SCORE[$CHOSEN]:-n/a} manual=${MANUAL_OVERRIDE} allDq=${ALL_DISQUALIFIED})"
# Resilience: an untracked AI-tooling dir in the working tree (.omc/.claude/.codex)
# can collide with a tracked copy in the candidate branch and abort the checkout.
# These are never part of the deliverable, so drop them before switching.
git clean -fdq -- .omc .claude .codex 2>/dev/null || true
git checkout -B "ai/${TASK_ID}/final" "$SOURCE"
mkdir -p docs/ai

# ---------------------------------------------------------------------------
# Report (bullets + headings only: the gateway's markdown renderer is minimal)
# ---------------------------------------------------------------------------

REPORT="docs/ai/SELECTION_REPORT.md"
{
  echo "# 🏆 AI 候選評選報告"
  echo
  echo "粉圓讓 ${MAX_AGENTS} 位 AI 選手分頭實作、各交一版，再用固定的健檢規則挑出最可靠的一版。"
  echo "每一分都有依據——不是 AI 自由心證。"
  echo
  echo "## 評選規則（固定，不看心情）"
  echo "- ❌ 直接失格：夾帶隱私鑰匙（API 金鑰／私鑰）、留下未解的程式衝突標記、違反能力邊界偷偷對外送資料"
  echo "- ➕ 加分：有交付實作摘要 +1000；成品可直接預覽 +200；實際改動規模最多 +400（防灌水）"
  echo "- ⚠️ 扣分：引用外部網站的程式 -100／處"
  if [ "$BOUNDARY_FORBIDS_NET" = true ]; then
    echo "- 🔒 本任務的能力邊界**禁止對外送出資料**，相關檢查已啟用"
  fi
  echo
  for i in $(seq 1 "$MAX_AGENTS"); do
    echo "## 候選 ${i}（dev-${i}）"
    if [ "${EV_EXISTS[i]}" != true ]; then
      echo "- 未產出結果（這位選手中途失敗）"
      echo
      continue
    fi
    if [ "${EV_SUMMARY[i]}" = true ]; then
      echo "- 實作重點摘要：✅ 有"
    else
      echo "- 實作重點摘要：⚠️ 沒交"
    fi
    echo "- 工程規模：${EV_FILES[i]} 個檔案、+${EV_INS[i]} / -${EV_DEL[i]} 行"
    if [ "${EV_INDEX[i]}" = true ]; then
      echo "- 成品樣品屋（可預覽）：✅ index.html 可直接開啟"
    else
      echo "- 成品樣品屋（可預覽）：— 無（或非網頁專案）"
    fi
    if [ "$BOUNDARY_FORBIDS_NET" = true ]; then
      if [ "${EV_NET[i]}" -gt 0 ]; then
        echo "- 安全守法檢查（能力邊界）：❌ 偵測到 ${EV_NET[i]} 處對外送資料程式"
      else
        echo "- 安全守法檢查（能力邊界）：✅ 通過"
      fi
    fi
    if [ "${EV_SECRET[i]}" -gt 0 ]; then
      echo "- 隱私鑰匙防護：❌ 偵測到 ${EV_SECRET[i]} 處疑似金鑰"
    else
      echo "- 隱私鑰匙防護：✅ 乾淨"
    fi
    if [ "${EV_CONFLICT[i]}" -gt 0 ]; then
      echo "- 衝突標記：❌ ${EV_CONFLICT[i]} 處未解"
    else
      echo "- 衝突標記：✅ 無"
    fi
    echo "- 外部程式引用：${EV_EXTSCRIPT[i]} 處"
    if [ "${EV_DQ[i]}" = true ]; then
      echo "- ❌ 失格原因：${EV_DQ_REASON[i]}"
    fi
    echo "- 總分：${EV_SCORE[i]}"
    echo
  done
  echo "## 🏆 錄取結果"
  if [ "$MANUAL_OVERRIDE" = true ]; then
    echo "- ⚠️ 本次由管理者手動指定候選 ${CHOSEN}（BEST_AGENT），上方健檢表僅供參考。"
  else
    echo "- **錄取：候選 ${CHOSEN}**（總分 ${EV_SCORE[$CHOSEN]}）"
    for i in $(seq 1 "$MAX_AGENTS"); do
      [ "$i" = "$CHOSEN" ] && continue
      [ "${EV_EXISTS[i]}" = true ] || { echo "- 落選：候選 ${i} — 未產出結果"; continue; }
      if [ "${EV_DQ[i]}" = true ]; then
        echo "- 落選：候選 ${i} — ❌ 失格：${EV_DQ_REASON[i]}"
      else
        echo "- 落選：候選 ${i} — 總分較低（${EV_SCORE[i]}）"
      fi
    done
  fi
  if [ "$ALL_DISQUALIFIED" = true ]; then
    echo
    echo "> ⚠️ **所有候選都未通過健檢**，已挑選問題最少的一版交付草稿。"
    echo "> 請務必人工審查上面列出的失格原因後再採用。"
  fi
} > "$REPORT"

echo "$CHOSEN" > docs/ai/SELECTED_AGENT.txt
git add docs/ai/SELECTED_AGENT.txt "$REPORT"
git commit -m "chore(${TASK_ID}): select dev-${CHOSEN} as final (evidence-based)" || true

# Copies for the gateway (work dir may differ from the repo checkout).
if [ -d "$BASE_DIR" ]; then
  cp "$REPORT" "${BASE_DIR}/selection_report.md" 2>/dev/null || true
  if command -v jq >/dev/null 2>&1; then
    for i in $(seq 1 "$MAX_AGENTS"); do
      jq -n --argjson agent "$i" \
        --argjson exists "${EV_EXISTS[i]}" --argjson summary "${EV_SUMMARY[i]}" \
        --argjson files "${EV_FILES[i]}" --argjson insertions "${EV_INS[i]}" \
        --argjson deletions "${EV_DEL[i]}" --argjson indexHtmlUsable "${EV_INDEX[i]}" \
        --argjson conflictMarkers "${EV_CONFLICT[i]}" --argjson secretFindings "${EV_SECRET[i]}" \
        --argjson networkViolations "${EV_NET[i]}" --argjson externalScripts "${EV_EXTSCRIPT[i]}" \
        --argjson disqualified "${EV_DQ[i]}" --arg reason "${EV_DQ_REASON[i]}" \
        --argjson score "${EV_SCORE[i]}" \
        '{agent:$agent,exists:$exists,summary:$summary,diffFiles:$files,insertions:$insertions,
          deletions:$deletions,indexHtmlUsable:$indexHtmlUsable,conflictMarkers:$conflictMarkers,
          secretFindings:$secretFindings,networkViolations:$networkViolations,
          externalScripts:$externalScripts,disqualified:$disqualified,
          disqualifyReason:$reason,score:$score}'
    done | jq -s --arg chosen "$CHOSEN" \
             --argjson manualOverride "$MANUAL_OVERRIDE" \
             --argjson allDisqualified "$ALL_DISQUALIFIED" \
             --argjson boundaryForbidsNet "$BOUNDARY_FORBIDS_NET" \
             '{chosen:($chosen|tonumber),manualOverride:$manualOverride,
               allDisqualified:$allDisqualified,boundaryForbidsNet:$boundaryForbidsNet,
               candidates:.}' > "${BASE_DIR}/evidence.json" 2>/dev/null || true
  fi
fi

if [ "${PROJECT_MODE:-existing}" != "local" ]; then
  aif_assert_push_allowed "ai/${TASK_ID}/final"
  aif_git push -u origin "ai/${TASK_ID}/final" --force-with-lease
  aif_git push
fi
