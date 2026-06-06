#!/usr/bin/env bash
set -euo pipefail

# Backward-compatible wrapper. This script historically opened a GitLab MR
# directly; it now delegates to the provider-neutral create-pr.sh so existing
# callers and docs keep working while github/bitbucket are also supported.
# Defaults to the gitlab provider, preserving the original behaviour.

TASK_ID="${1:?TASK_ID required}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
export GIT_PROVIDER="${GIT_PROVIDER:-gitlab}"
exec bash "${SCRIPT_DIR}/git/create-pr.sh" "$TASK_ID"
