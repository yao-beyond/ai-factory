#!/usr/bin/env bash
set -euo pipefail

# AI Factory setup wizard. Asks a few plain questions and writes:
#   - ai-factory.yml  (your settings, no secrets)
#   - .env            (your tokens / API keys)
# Then offers to run the doctor to check everything is connected.
#
# Safe to re-run: it asks before overwriting existing files.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
CONFIG_OUT="${ROOT_DIR}/ai-factory.yml"
ENV_OUT="${ROOT_DIR}/.env"

bold() { printf '\033[1m%s\033[0m\n' "$1"; }
ask() {  # ask <prompt> <default> -> echoes answer
  local prompt="$1" def="${2:-}" ans=""
  if [ -n "$def" ]; then
    printf '%s [%s]: ' "$prompt" "$def" >&2
  else
    printf '%s: ' "$prompt" >&2
  fi
  read -r ans || true
  echo "${ans:-$def}"
}
confirm() { # confirm <prompt> -> returns 0 for yes
  local ans; ans="$(ask "$1 (y/N)" "")"
  case "$ans" in y|Y|yes|YES) return 0 ;; *) return 1 ;; esac
}

bold "AI Factory 設定精靈"
echo "我會問幾個問題，然後幫你產生設定檔。需要技術金鑰時會說明在哪裡拿。"
echo

# 1) Git platform.
echo "你的程式碼放在哪個平台？"
echo "  1) GitHub"
echo "  2) GitLab"
echo "  3) Bitbucket"
PLATFORM_CHOICE="$(ask "請輸入 1 / 2 / 3" "1")"
case "$PLATFORM_CHOICE" in
  2) PROVIDER="gitlab" ;;
  3) PROVIDER="bitbucket" ;;
  *) PROVIDER="github" ;;
esac

# 2) Repository URL (auto-detect from local git if possible).
DETECTED=""
if command -v git >/dev/null 2>&1; then
  DETECTED="$(git -C "$ROOT_DIR" config --get remote.origin.url 2>/dev/null || true)"
fi
REPO="$(ask "要讓 AI Factory 處理的程式庫網址" "$DETECTED")"

# 3) Target branch.
TARGET="$(ask "Pull Request 要合併到哪個分支" "main")"

# Derive an allowlist pattern (everything up to the owner, then /*).
ALLOW_PATTERN=""
if [ -n "$REPO" ]; then
  base="${REPO%.git}"
  ALLOW_PATTERN="${base%/*}/*"
fi

# 4) Token.
echo
bold "金鑰設定"
case "$PROVIDER" in
  github)    echo "GitHub：到 Settings → Developer settings → Fine-grained tokens，給予目標 repo 的 Contents 與 Pull requests 寫入權限。"; TOK_VAR="GITHUB_TOKEN" ;;
  gitlab)    echo "GitLab：到 Project → Settings → Access Tokens，scope 勾選 api 與 write_repository。"; TOK_VAR="GITLAB_TOKEN" ;;
  bitbucket) echo "Bitbucket：建立 Access Token（或 App password），給予 Pull requests 寫入權限。"; TOK_VAR="BITBUCKET_TOKEN" ;;
esac
GIT_TOKEN_VAL="$(ask "貼上你的 ${TOK_VAR}（會寫進 .env，不會進設定檔）" "")"
OPENAI_VAL="$(ask "OPENAI_API_KEY（codex 規劃/審查用，可留空）" "")"
ANTHROPIC_VAL="$(ask "ANTHROPIC_API_KEY（Claude 開發用，可留空）" "")"

# Write config file.
if [ -f "$CONFIG_OUT" ] && ! confirm "已存在 ai-factory.yml，要覆寫嗎？"; then
  echo "保留現有 ai-factory.yml。"
else
  cat > "$CONFIG_OUT" <<YML
version: 1

workspace:
  workDir: ./.work
  mode: local

git:
  provider: ${PROVIDER}
  repo: ${REPO}
  targetBranch: ${TARGET}
  branchPrefix: ai

agents:
  maxAgents: 3
  planner: codex
  developer: claude
  reviewer: codex

security:
  allowRepositories:
    - ${ALLOW_PATTERN}
  protectedBranches:
    - main
    - master
    - "release/*"
  requireHumanMerge: true
  draftPullRequests: true
  pullRequestLabel: ai-generated

secrets:
  provider: env
YML
  bold "已寫入 ${CONFIG_OUT}"
fi

# Write/append .env (only the keys the user provided).
write_env_kv() {  # write_env_kv KEY VALUE
  local key="$1" val="$2"
  [ -z "$val" ] && return 0
  if [ -f "$ENV_OUT" ] && grep -q "^${key}=" "$ENV_OUT" 2>/dev/null; then
    # replace existing line (portable: rewrite file)
    local tmp; tmp="$(mktemp)"
    grep -v "^${key}=" "$ENV_OUT" > "$tmp" || true
    mv "$tmp" "$ENV_OUT"
  fi
  printf '%s=%s\n' "$key" "$val" >> "$ENV_OUT"
}
touch "$ENV_OUT"
write_env_kv "$TOK_VAR" "$GIT_TOKEN_VAL"
write_env_kv "OPENAI_API_KEY" "$OPENAI_VAL"
write_env_kv "ANTHROPIC_API_KEY" "$ANTHROPIC_VAL"
chmod 600 "$ENV_OUT" 2>/dev/null || true
bold "已更新 ${ENV_OUT}（請勿提交到版本控制）"

echo
if confirm "要現在執行健康檢查 (doctor) 嗎？"; then
  # shellcheck disable=SC1090
  set -a; [ -f "$ENV_OUT" ] && source "$ENV_OUT"; set +a
  AI_FACTORY_CONFIG="$CONFIG_OUT" bash "${SCRIPT_DIR}/doctor.sh" || true
else
  echo "稍後可執行：AI_FACTORY_CONFIG=${CONFIG_OUT} bash scripts/doctor.sh"
fi

echo
bold "完成！"
echo "下一步：docker compose up  然後打開 http://localhost:8080"
