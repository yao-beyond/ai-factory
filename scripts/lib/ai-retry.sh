#!/usr/bin/env bash
# Retry wrapper for AI CLI calls (sourceable).
#
#   aif_ai_retry <attempts> <base_seconds> -- <command...>
#
# The prompt is read from this function's stdin (a heredoc/pipe) and captured
# once, so it can be re-fed on each attempt. The command runs with that stdin;
# its output is streamed to our stdout (so existing redirects keep working).
# Retries only on clearly transient failures (HTTP 529 / overloaded / rate limit
# / 5xx gateway / network blips) with linear backoff; a non-transient failure is
# returned immediately.
aif_ai_retry() {
  local attempts="$1" base="$2"; shift 2
  [ "${1:-}" = "--" ] && shift

  local stdin_file out_file
  stdin_file="$(mktemp)"; out_file="$(mktemp)"
  cat > "$stdin_file"

  local n=1 rc=0
  while :; do
    rc=0
    "$@" < "$stdin_file" > "$out_file" 2>&1 || rc=$?
    cat "$out_file"
    if [ "$rc" -eq 0 ]; then break; fi
    if [ "$n" -ge "$attempts" ]; then break; fi
    if grep -qiE '529|overloaded|rate.?limit|too many requests|503 service|502 bad gateway|temporarily unavailable|connection reset|etimedout|enotfound|socket hang up|fetch failed' "$out_file"; then
      local wait=$(( base * n ))
      echo "WARN: transient AI error (attempt ${n}/${attempts}), retrying in ${wait}s..." >&2
      sleep "$wait"
      n=$((n + 1))
      continue
    fi
    break
  done

  rm -f "$stdin_file" "$out_file"
  return "$rc"
}
