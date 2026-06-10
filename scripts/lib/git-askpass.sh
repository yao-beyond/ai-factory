#!/usr/bin/env bash
# Git askpass helper for AI Factory.
#
# Git invokes this script with the human prompt as $1 ("Username for ...",
# "Password for ...") and expects the answer on stdout. It feeds the token to
# git WITHOUT the token ever living in .git/config or in the AI agent's
# environment: the value arrives via AIF_GIT_PASSWORD, which aif_git() sets
# inline only for the duration of a single git network command.
case "$1" in
  Username*|username*) printf '%s\n' "${AIF_GIT_USER:-x-access-token}" ;;
  *)                   printf '%s\n' "${AIF_GIT_PASSWORD:-}" ;;
esac
