#!/bin/bash
# drom-flow memory sync — inject session memory and check for in-progress plans on start

DIR="${CLAUDE_PROJECT_DIR:-.}"
MEMORY="$DIR/context/MEMORY.md"
STATE_DIR="$DIR/.claude/.state"
PLANS_DIR="$DIR/drom-plans"

# Initialize session state
mkdir -p "$STATE_DIR"
date +%s > "$STATE_DIR/session-start"
echo "0" > "$STATE_DIR/agent-count"
echo "0" > "$STATE_DIR/edit-count"

# Load session memory
if [ -s "$MEMORY" ]; then
  echo "[Session Memory Loaded]"
  echo "---"
  cat "$MEMORY"
  echo "---"
else
  echo "[No session memory found. Create context/MEMORY.md to persist context across sessions.]"
fi

# Check for in-progress plans
if [ -d "$PLANS_DIR" ]; then
  in_progress=""
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    if grep -q "^status: in-progress" "$plan" 2>/dev/null; then
      title=$(grep "^title:" "$plan" 2>/dev/null | sed 's/^title: *//')
      chapter=$(grep "^current_chapter:" "$plan" 2>/dev/null | sed 's/^current_chapter: *//')
      basename=$(basename "$plan")
      in_progress="${in_progress}\n  - ${basename} — \"${title}\" (Chapter ${chapter:-?})"
    fi
  done
  if [ -n "$in_progress" ]; then
    echo ""
    echo "[In-Progress Plans Found]"
    echo -e "The following plans were stopped midway and can be resumed:${in_progress}"
    echo "Read the plan file to review progress and resume from the current chapter."
  fi
fi
