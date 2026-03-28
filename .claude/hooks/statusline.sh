#!/bin/bash
# drom-flow statusline — git-aware status for Claude Code

DIR="${CLAUDE_PROJECT_DIR:-.}"
STATE_DIR="$DIR/.claude/.state"

# --- Version ---
DROMFLOW_VERSION=""
for vfile in "$DIR/VERSION" "$(dirname "${BASH_SOURCE[0]}")/../../../VERSION"; do
  if [ -f "$vfile" ]; then
    DROMFLOW_VERSION=$(tr -d '[:space:]' < "$vfile")
    break
  fi
done
DROMFLOW_VERSION="${DROMFLOW_VERSION:-dev}"

# --- Session elapsed time ---
elapsed=""
if [ -f "$STATE_DIR/session-start" ]; then
  start=$(cat "$STATE_DIR/session-start")
  now=$(date +%s)
  diff=$((now - start))
  mins=$((diff / 60))
  secs=$((diff % 60))
  if [ $mins -ge 60 ]; then
    hrs=$((mins / 60))
    mins=$((mins % 60))
    elapsed="${hrs}h${mins}m"
  else
    elapsed="${mins}m${secs}s"
  fi
fi

# --- Plan progress (computed early so both git and no-git paths can use it) ---
plan_info=""
PLANS_DIR="$DIR/drom-plans"
if [ -d "$PLANS_DIR" ]; then
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    # Match plans that are in-progress OR have any in-progress chapter (fallback for bad frontmatter)
    if grep -q "^status: in-progress" "$plan" 2>/dev/null || grep -q '^\*\*Status:\*\* in-progress' "$plan" 2>/dev/null; then
      cur=$(grep "^current_chapter:" "$plan" 2>/dev/null | sed 's/^current_chapter: *//')
      total=$(grep -c "^## Chapter " "$plan" 2>/dev/null)
done_count=$(grep -c '^\*\*Status:\*\* completed' "$plan" 2>/dev/null)
      plan_info="plan:ch${cur:-?}/${total:-?}(${done_count:-0}✓)"
      break
    fi
  done
fi

# --- Git info ---
branch=$(git branch --show-current 2>/dev/null || echo "no-git")
if [ "$branch" = "no-git" ]; then
  nogit_status="drom-flow v$DROMFLOW_VERSION • [no-git] • ${elapsed:-0m0s}"
  [ -n "$plan_info" ] && nogit_status="$nogit_status • $plan_info"
  echo "$nogit_status"
  exit 0
fi

staged=$(git diff --cached --numstat 2>/dev/null | wc -l | tr -d ' ')
unstaged=$(git diff --numstat 2>/dev/null | wc -l | tr -d ' ')
untracked=$(git ls-files --others --exclude-standard 2>/dev/null | wc -l | tr -d ' ')

ahead=0
behind=0
upstream=$(git rev-list --left-right --count HEAD...@{upstream} 2>/dev/null)
if [ $? -eq 0 ]; then
  ahead=$(echo "$upstream" | awk '{print $1}')
  behind=$(echo "$upstream" | awk '{print $2}')
fi

# Compact git: +staged/-unstaged/?untracked
git_info="$branch +${staged}/-${unstaged}/?${untracked}"
[ "$ahead" -gt 0 ] || [ "$behind" -gt 0 ] && git_info="$git_info ↑${ahead}↓${behind}"

# --- Edit count (from edit-log) ---
edits=0
[ -f "$DIR/.claude/edit-log.jsonl" ] && edits=$(wc -l < "$DIR/.claude/edit-log.jsonl" | tr -d ' ')

# --- Background agents (tracked via hook) ---
agents=0
[ -f "$STATE_DIR/agent-count" ] && agents=$(cat "$STATE_DIR/agent-count" | tr -d '[:space:]')

# --- Memory status ---
mem="off"
[ -s "$DIR/context/MEMORY.md" ] && mem="on"

status="drom-flow v$DROMFLOW_VERSION • $git_info • ${elapsed:-0m0s} • edits:$edits • agents:$agents • mem:$mem"
[ -n "$plan_info" ] && status="$status • $plan_info"
echo "$status"
