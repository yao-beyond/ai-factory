#!/usr/bin/env bash
set -uo pipefail

# Compliance-patcher governance demo — shows the "high moment" of the AI change
# control runtime WITHOUT needing the full AI pipeline: it drives the governance
# API directly to demonstrate interception, the human-approval gate, and the
# evidence pack.
#
# Prereq: a running gateway. Set GW to its base URL (default tries 8081 then 8080).
# Usage: bash scripts/demo/governance-demo.sh
#
# Flow:
#   1. submit a task under the critical "compliance-patcher" profile
#   2. promote-check with failing tests  -> BLOCKED (Safe Catch)
#   3. promote-check with passing tests   -> HUMAN_APPROVAL_PENDING (gate holds)
#   4. human approves                     -> promote-check now DELIVERABLE_ELIGIBLE
#   5. fetch the human-readable evidence pack + point at the dashboard

GW="${GW:-}"
pick_gw() {
  for u in "http://127.0.0.1:8081" "http://127.0.0.1:8080"; do
    if curl -sS -m 3 "$u/gateway/governance/profiles" >/dev/null 2>&1; then echo "$u"; return 0; fi
  done
  return 1
}
[ -n "$GW" ] || GW="$(pick_gw)" || { echo "找不到運行中的 gateway（請設定 GW=http://host:port）" >&2; exit 1; }
echo "▶ gateway: $GW"

SEC_HDR=()
[ -n "${AIF_INTERNAL_SECRET:-}" ] && SEC_HDR=(-H "X-AIF-Internal: ${AIF_INTERNAL_SECRET}")

TASK="DEMO-$(date +%s)"
jqr() { if command -v jq >/dev/null 2>&1; then jq -r "$1"; else cat; fi; }

echo "▶ 1) 以 compliance-patcher（critical）提交任務 $TASK"
curl -sS -X POST "$GW/gateway/issue" -H 'Content-Type: application/json' \
  -d "{\"source\":\"web\",\"mode\":\"new\",\"externalId\":\"$TASK\",\"title\":\"修補 CVE\",\"description\":\"修一個安全漏洞\",\"maxAgents\":1,\"governanceProfileId\":\"compliance-patcher\"}" \
  >/dev/null && echo "  ✓ 已提交"

promote() { # promote <testStatus>
  curl -sS -X POST "$GW/gateway/governance/$TASK/promote-check" -H 'Content-Type: application/json' \
    ${SEC_HDR[@]+"${SEC_HDR[@]}"} \
    -d "{\"testStatus\":\"$1\",\"reviewReportRef\":\"docs/ai/CODEX_REVIEW.md\",\"diffRef\":\"ai/$TASK/final\",\"mode\":\"local\"}"
}

echo "▶ 2) 測試失敗就送審 → 應被攔截 (BLOCKED)"
promote fail | jqr '"  state=\(.state) gate=\(.blockingGate)"'

echo "▶ 3) 測試通過但尚未核准 → 應停在人類核准閘門"
promote pass | jqr '"  state=\(.state)"'

echo "▶ 4) 人類於控制台核准交付"
curl -sS -X POST "$GW/gateway/governance/$TASK/approve" ${SEC_HDR[@]+"${SEC_HDR[@]}"} >/dev/null && echo "  ✓ 已核准"

echo "▶ 5) 再送審 → 應可交付 (DELIVERABLE_ELIGIBLE)"
promote pass | jqr '"  state=\(.state)"'

echo "▶ 6) 取得人類可讀的治理證據包 (GEP)"
curl -sS "$GW/gateway/governance/$TASK/gep" | head -n 20
echo "  …（完整證據包見上方端點）"
echo
echo "▶ 攔截看板： $GW/gateway/governance/dashboard"
