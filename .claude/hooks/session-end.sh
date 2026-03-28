#!/bin/bash
# drom-flow session end — remind to persist progress and update plans

DIR="${CLAUDE_PROJECT_DIR:-.}"
PLANS_DIR="$DIR/drom-plans"

echo "[Session ending. Update context/MEMORY.md with progress, findings, and next steps.]"

# Remind about in-progress plans
if [ -d "$PLANS_DIR" ]; then
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    if grep -q "^status: in-progress" "$plan" 2>/dev/null; then
      title=$(grep "^title:" "$plan" 2>/dev/null | sed 's/^title: *//')
      echo "[Plan in progress: \"${title}\" — update chapter status and step checkboxes before ending.]"
    fi
  done
fi
