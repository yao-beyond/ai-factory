#!/usr/bin/env bash
# AI Factory config loader (sourceable).
#
# Locates and parses ai-factory.yml, then exposes:
#   aif_cfg <dotted.path>        -> scalar value (empty if absent)
#   aif_cfg_list <dotted.path>   -> newline-separated list items
#   aif_export_pipeline_env      -> exports GIT_PROVIDER/REPO_URL/... for the pipeline
#
# Config file precedence (first match wins):
#   1. $AI_FACTORY_CONFIG
#   2. ./ai-factory.yml
#   3. ~/.ai-factory/config.yml
#
# Parsing uses a small self-contained awk flattener (no yq dependency) that
# understands the fixed 2-space-indented schema in config/ai-factory.example.yml.

AIF_CONFIG_FILE=""
AIF_FLAT=""

aif_find_config() {
  if [ -n "${AI_FACTORY_CONFIG:-}" ] && [ -f "$AI_FACTORY_CONFIG" ]; then
    AIF_CONFIG_FILE="$AI_FACTORY_CONFIG"
  elif [ -f "./ai-factory.yml" ]; then
    AIF_CONFIG_FILE="./ai-factory.yml"
  elif [ -f "${HOME}/.ai-factory/config.yml" ]; then
    AIF_CONFIG_FILE="${HOME}/.ai-factory/config.yml"
  else
    AIF_CONFIG_FILE=""
  fi
  echo "$AIF_CONFIG_FILE"
}

# Flatten the YAML into "path=value" and "path[]=item" lines.
aif_flatten() {
  awk '
    function trim(s){ sub(/^[ \t]+/,"",s); sub(/[ \t]+$/,"",s); return s }
    {
      line=$0
      sub(/[ \t]+#.*/,"",line)         # strip trailing comments
      sub(/^#.*/,"",line)              # strip full-line comments
      if (line ~ /^[ \t]*$/) next
      match(line,/^ */); ind=RLENGTH; lvl=int(ind/2)
      content=trim(line)
      if (content ~ /^- /) {
        val=trim(substr(content,3)); gsub(/^"|"$/,"",val)
        p=""; for(i=0;i<lvl;i++){ p=(p==""?path[i]:p"."path[i]) }
        print p"[]="val; next
      }
      ci=index(content,":"); if (ci==0) next
      key=trim(substr(content,1,ci-1)); val=trim(substr(content,ci+1))
      path[lvl]=key
      for(i=lvl+1;i<=10;i++) delete path[i]
      if (val!="") {
        gsub(/^"|"$/,"",val)
        p=""; for(i=0;i<=lvl;i++){ p=(p==""?path[i]:p"."path[i]) }
        print p"="val
      }
    }
  ' "$1"
}

aif_load_config() {
  aif_find_config >/dev/null
  if [ -z "$AIF_CONFIG_FILE" ]; then
    AIF_FLAT=""
    return 1
  fi
  AIF_FLAT="$(aif_flatten "$AIF_CONFIG_FILE")"
  return 0
}

aif_cfg() {
  [ -n "$AIF_FLAT" ] || return 0
  printf '%s\n' "$AIF_FLAT" | awk -F= -v k="$1" '$1==k{ sub(/^[^=]*=/,""); print; exit }'
}

aif_cfg_list() {
  [ -n "$AIF_FLAT" ] || return 0
  printf '%s\n' "$AIF_FLAT" | awk -F= -v k="$1[]" '$1==k{ sub(/^[^=]*=/,""); print }'
}

# Map config values onto the env vars the bash pipeline already understands.
# Existing env vars win (so CI/k8s can override the file).
aif_export_pipeline_env() {
  local v
  v="$(aif_cfg git.provider)";     [ -n "$v" ] && export GIT_PROVIDER="${GIT_PROVIDER:-$v}"
  v="$(aif_cfg git.repo)";         [ -n "$v" ] && export REPO_URL="${REPO_URL:-$v}"
  v="$(aif_cfg git.targetBranch)"; [ -n "$v" ] && export TARGET_BRANCH="${TARGET_BRANCH:-$v}"
  v="$(aif_cfg git.branchPrefix)"; [ -n "$v" ] && export BRANCH_PREFIX="${BRANCH_PREFIX:-$v}"
  v="$(aif_cfg agents.maxAgents)"; [ -n "$v" ] && export MAX_AGENTS="${MAX_AGENTS:-$v}"
  v="$(aif_cfg security.draftPullRequests)"; [ -n "$v" ] && export PR_DRAFT="${PR_DRAFT:-$v}"
  v="$(aif_cfg security.pullRequestLabel)";  [ -n "$v" ] && export PR_LABEL="${PR_LABEL:-$v}"
  v="$(aif_cfg security.requireHumanMerge)"; [ -n "$v" ] && export REQUIRE_HUMAN_MERGE="${REQUIRE_HUMAN_MERGE:-$v}"
  v="$(aif_cfg workspace.mode)";   [ -n "$v" ] && export AI_FACTORY_MODE="${AI_FACTORY_MODE:-$v}"
  # Protected branches as a space-separated list for the push guard.
  local pb; pb="$(aif_cfg_list security.protectedBranches | tr '\n' ' ')"
  pb="$(echo "$pb" | sed 's/[[:space:]]*$//')"
  [ -n "$pb" ] && export PROTECTED_BRANCHES="${PROTECTED_BRANCHES:-$pb}"
}
