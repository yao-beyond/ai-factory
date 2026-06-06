#!/usr/bin/env bash
# Branch push guard (sourceable). Refuses to push to a protected branch even if
# the token has permission, so a misconfiguration can never let an agent write
# to main/release. Protected patterns come from PROTECTED_BRANCHES (space- or
# newline-separated globs) or a safe default.

aif_assert_push_allowed() {
  local branch="$1"
  local patterns="${PROTECTED_BRANCHES:-main master release/* hotfix/*}"
  local p
  for p in $patterns; do
    # shellcheck disable=SC2254
    case "$branch" in
      $p)
        echo "ERROR: refusing to push protected branch '$branch' (matches '$p'). " \
             "AI agents may only push to the configured branch prefix." >&2
        return 1
        ;;
    esac
  done
  return 0
}
