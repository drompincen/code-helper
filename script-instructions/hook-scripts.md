# Hook Scripts

Claude Code lifecycle hooks used by drom-flow. Recreate in `scripts/local/hooks/`.

Referenced by `.claude/settings.json` — after recreating, hooks work automatically.

---

## edit-log.sh

Appends edit events to JSONL log for tracking file modifications.

```bash
#!/bin/bash
# drom-flow edit logger — appends edit events to JSONL

DIR="${CLAUDE_PROJECT_DIR:-.}"
LOG="$DIR/.claude/edit-log.jsonl"

# Extract file_path from tool input (passed via stdin)
file_path="unknown"
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi

timestamp=$(date +%s)
echo "{\"type\":\"edit\",\"file\":\"$file_path\",\"timestamp\":$timestamp}" >> "$LOG"
```

---

## enrich-check.sh

Checks if JavaDucker has artifacts pending enrichment at session start.

```bash
#!/bin/bash
# Hook: Check if JavaDucker has artifacts pending enrichment

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"
HOST="${JAVADUCKER_HOST:-localhost}"
PORT="${JAVADUCKER_PORT:-8080}"
BASE_URL="http://${HOST}:${PORT}/api"

# Check if server is running
if ! curl -sf "${BASE_URL}/health" > /dev/null 2>&1; then
  exit 0
fi

# Check enrichment queue
QUEUE=$(curl -sf "${BASE_URL}/enrich-queue?limit=1" 2>/dev/null || echo '{"count":0}')
COUNT=$(echo "$QUEUE" | jq -r '.count' 2>/dev/null || echo "0")

if [ "$COUNT" != "0" ] && [ -n "$COUNT" ]; then
  FULL_QUEUE=$(curl -sf "${BASE_URL}/enrich-queue?limit=100" 2>/dev/null || echo '{"count":0}')
  TOTAL=$(echo "$FULL_QUEUE" | jq -r '.count' 2>/dev/null || echo "0")
  echo "[Content Intelligence] ${TOTAL} artifact(s) pending enrichment. Use javaducker_enrich_queue to see them, then classify/tag/extract points using the write MCP tools."
fi
```

---

## memory-sync.sh

Injects session memory and checks for in-progress plans on session start.

```bash
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

# --- JavaDucker: auto-start and health check ---
. "$DIR/scripts/local/hooks/javaducker-check.sh" 2>/dev/null
if javaducker_available; then
  if javaducker_healthy; then
    echo "[JavaDucker: connected (port ${JAVADUCKER_HTTP_PORT:-8080})]"
  else
    echo "[JavaDucker: starting server...]"
    if javaducker_start; then
      echo "[JavaDucker: connected (port ${JAVADUCKER_HTTP_PORT:-8080})]"
    else
      echo "[JavaDucker: server starting in background — will be available shortly]"
    fi
  fi
fi
```

---

## session-end.sh

Reminds to persist progress and checks JavaDucker hygiene at session end.

```bash
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

# JavaDucker session-end hygiene
. "$DIR/scripts/local/hooks/javaducker-check.sh" 2>/dev/null
if javaducker_available && javaducker_healthy; then
  edits=0
  [ -f "$DIR/.claude/edit-log.jsonl" ] && edits=$(wc -l < "$DIR/.claude/edit-log.jsonl" | tr -d ' ')
  if [ "$edits" -gt 10 ]; then
    echo "[JavaDucker: $edits files edited — run javaducker_index_health to check freshness.]"
  fi
  queue=$(curl -sf "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/enrich-queue?limit=1" 2>/dev/null)
  if [ -n "$queue" ] && echo "$queue" | grep -q '"artifact_id"'; then
    echo "[JavaDucker: un-enriched artifacts detected — run workflows/javaducker-hygiene.md Phase 2 to classify, tag, and extract points.]"
  fi
fi
```

---

## statusline.sh

Git-aware status line for Claude Code showing branch, edits, agents, and plan progress.

```bash
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

# --- Project root (bright cyan to pop) ---
PROJECT_ROOT="\033[1;36m$(basename "$(cd "$DIR" && pwd)")\033[0m"

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

# --- Plan progress ---
plan_info=""
PLANS_DIR="$DIR/drom-plans"
if [ -d "$PLANS_DIR" ]; then
  for plan in "$PLANS_DIR"/*.md; do
    [ -f "$plan" ] || continue
    if grep -q "^status: in-progress" "$plan" 2>/dev/null || grep -q '^\*\*Status:\*\* in-progress' "$plan" 2>/dev/null; then
      cur=$(grep "^current_chapter:" "$plan" 2>/dev/null | sed 's/^current_chapter: *//')
      total=$(grep -c "^## Chapter " "$plan" 2>/dev/null)
      done_count=$(grep -c '^\*\*Status:\*\* completed' "$plan" 2>/dev/null)
      plan_info="plan:ch${cur:-?}/${total:-?}(${done_count:-0}done)"
      break
    fi
  done
fi

# --- Git info ---
branch=$(git branch --show-current 2>/dev/null || echo "no-git")
if [ "$branch" = "no-git" ]; then
  nogit_status="drom-flow v$DROMFLOW_VERSION | $PROJECT_ROOT | [no-git] | ${elapsed:-0m0s}"
  [ -n "$plan_info" ] && nogit_status="$nogit_status | $plan_info"
  echo -e "$nogit_status"
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

git_info="$branch +${staged}/-${unstaged}/?${untracked}"
[ "$ahead" -gt 0 ] || [ "$behind" -gt 0 ] && git_info="$git_info up${ahead}dn${behind}"

# --- Edit count ---
edits=0
[ -f "$DIR/.claude/edit-log.jsonl" ] && edits=$(wc -l < "$DIR/.claude/edit-log.jsonl" | tr -d ' ')

# --- Background agents ---
agents=0
[ -f "$STATE_DIR/agent-count" ] && agents=$(cat "$STATE_DIR/agent-count" | tr -d '[:space:]')

# --- Memory status ---
mem="off"
[ -s "$DIR/context/MEMORY.md" ] && mem="on"

# --- JavaDucker status ---
jd_icon=""
. "$DIR/scripts/local/hooks/javaducker-check.sh" 2>/dev/null
if javaducker_available; then
  javaducker_healthy && jd_icon="JD" || jd_icon="JD(off)"
fi

status="drom-flow v$DROMFLOW_VERSION | $PROJECT_ROOT | $git_info | ${elapsed:-0m0s} | edits:$edits | agents:$agents | mem:$mem"
[ -n "$jd_icon" ] && status="$status | $jd_icon"
[ -n "$plan_info" ] && status="$status | $plan_info"
echo -e "$status"
```

---

## track-agents.sh

Increments background agent counter for statusline display.

```bash
#!/bin/bash
# drom-flow — track background agent count

STATE_DIR="${CLAUDE_PROJECT_DIR:-.}/.claude/.state"
mkdir -p "$STATE_DIR"

count=0
[ -f "$STATE_DIR/agent-count" ] && count=$(cat "$STATE_DIR/agent-count" | tr -d '[:space:]')
echo $((count + 1)) > "$STATE_DIR/agent-count"
```

---

## validate-plan.sh

Validates plan files written to drom-plans/ have correct frontmatter and structure.

```bash
#!/bin/bash
# drom-flow — validate plan files written to drom-plans/

DIR="${CLAUDE_PROJECT_DIR:-.}"
PLANS_DIR="$DIR/drom-plans"

# Extract file_path from tool input
file_path=""
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi

# Only validate files in drom-plans/
case "$file_path" in
  */drom-plans/*.md|drom-plans/*.md) ;;
  *) exit 0 ;;
esac

[ ! -f "$file_path" ] && exit 0

errors=""

# Check frontmatter exists
if ! head -1 "$file_path" | grep -q "^---"; then
  errors="${errors}\n  - Missing YAML frontmatter (must start with ---)"
fi

# Check required frontmatter fields
for field in title status created updated current_chapter; do
  if ! grep -q "^${field}:" "$file_path"; then
    errors="${errors}\n  - Missing frontmatter field: ${field}"
  fi
done

# Check status value
status=$(grep "^status:" "$file_path" | head -1 | sed 's/^status: *//')
case "$status" in
  in-progress|completed|pending|abandoned) ;;
  *) errors="${errors}\n  - Invalid status: '${status}' (must be: in-progress, completed, pending, or abandoned)" ;;
esac

# Check for at least one chapter
chapter_count=$(grep -c "^## Chapter " "$file_path" 2>/dev/null | tr -d '[:space:]')
chapter_count=${chapter_count:-0}
if [ "$chapter_count" -eq 0 ]; then
  errors="${errors}\n  - No chapters found (need at least one '## Chapter N: Title')"
fi

# Check chapters have Status lines
while IFS= read -r line; do
  chapter_num=$(echo "$line" | grep -o "Chapter [0-9]*" | grep -o "[0-9]*")
  if ! grep -A2 "^## Chapter ${chapter_num}:" "$file_path" | grep -q '^\*\*Status:\*\*'; then
    chapters_without_status=$((chapters_without_status + 1))
    errors="${errors}\n  - Chapter ${chapter_num} missing **Status:** line"
  fi
done < <(grep "^## Chapter " "$file_path")

# Check chapters have at least one step
while IFS= read -r line; do
  chapter_num=$(echo "$line" | grep -o "Chapter [0-9]*" | grep -o "[0-9]*")
  next_section=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^## /{print NR; exit}" "$file_path")
  if [ -n "$next_section" ]; then
    step_count=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^## /{exit} found && /^- \[/" "$file_path" | wc -l)
  else
    step_count=$(awk "/^## Chapter ${chapter_num}:/{found=1; next} found && /^- \[/" "$file_path" | wc -l)
  fi
  if [ "$step_count" -eq 0 ]; then
    errors="${errors}\n  - Chapter ${chapter_num} has no steps (need at least one '- [ ] ...')"
  fi
done < <(grep "^## Chapter " "$file_path")

# Check current_chapter points to a valid chapter
current=$(grep "^current_chapter:" "$file_path" | head -1 | sed 's/^current_chapter: *//')
if [ -n "$current" ] && [ "$chapter_count" -gt 0 ]; then
  if ! grep -q "^## Chapter ${current}:" "$file_path"; then
    errors="${errors}\n  - current_chapter: ${current} does not match any chapter heading"
  fi
fi

if [ -n "$errors" ]; then
  echo "PLAN VALIDATION FAILED: $(basename "$file_path")"
  echo -e "Issues:${errors}"
  echo ""
  echo "Expected format: see /planner skill or drom-plans/ docs in CLAUDE.md"
  exit 1
fi
```

---

## javaducker-check.sh

Guard and lifecycle functions for JavaDucker (sourced by other hooks).

```bash
#!/bin/bash
# drom-flow — JavaDucker guard and lifecycle functions (sourced by other hooks)
# When .claude/.state/javaducker.conf does not exist, all functions return false.

JAVADUCKER_CONF="${CLAUDE_PROJECT_DIR:-.}/.claude/.state/javaducker.conf"

javaducker_available() {
  [ -f "$JAVADUCKER_CONF" ] || return 1
  . "$JAVADUCKER_CONF"
  [ -n "$JAVADUCKER_ROOT" ]
}

javaducker_healthy() {
  javaducker_available || return 1
  curl -sf "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/health" >/dev/null 2>&1
}

# Find a free TCP port in the 8080-8180 range
javaducker_find_free_port() {
  for port in $(seq 8080 8180); do
    if ! (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
      echo "$port"
      return 0
    fi
  done
  echo "8080"
}

# Start the server with project-local data paths
javaducker_start() {
  javaducker_available || return 1
  javaducker_healthy && return 0

  local db="${JAVADUCKER_DB:-${CLAUDE_PROJECT_DIR:-.}/.claude/.javaducker/javaducker.duckdb}"
  local intake="${JAVADUCKER_INTAKE:-${CLAUDE_PROJECT_DIR:-.}/.claude/.javaducker/intake}"
  local port="${JAVADUCKER_HTTP_PORT:-8080}"

  mkdir -p "$(dirname "$db")" "$intake"

  # Check if the configured port is taken; if so, find a free one
  if (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
    if curl -sf "http://localhost:$port/api/health" >/dev/null 2>&1; then
      return 0
    fi
    port=$(javaducker_find_free_port)
    sed -i "s/^JAVADUCKER_HTTP_PORT=.*/JAVADUCKER_HTTP_PORT=$port/" "$JAVADUCKER_CONF"
    export JAVADUCKER_HTTP_PORT="$port"
  fi

  DB="$db" HTTP_PORT="$port" INTAKE_DIR="$intake" \
    nohup bash "${JAVADUCKER_ROOT}/run-server.sh" >/dev/null 2>&1 &

  # Wait for startup
  for i in 1 2 3 4 5 6 7 8; do
    sleep 1
    if curl -sf "http://localhost:$port/api/health" >/dev/null 2>&1; then
      return 0
    fi
  done
  return 1
}
```

---

## javaducker-index.sh

Indexes modified files in JavaDucker after edits (fire-and-forget).

```bash
#!/bin/bash
# drom-flow — index modified files in JavaDucker after edits
# Triggered by PostToolUse on Write|Edit|MultiEdit
# Fire-and-forget: does not block the edit.

DIR="${CLAUDE_PROJECT_DIR:-.}"
. "$DIR/scripts/local/hooks/javaducker-check.sh" 2>/dev/null
javaducker_healthy || exit 0

# Extract file_path from tool input
file_path=""
if [ -n "$CLAUDE_TOOL_USE_INPUT" ]; then
  fp=$(echo "$CLAUDE_TOOL_USE_INPUT" | grep -o '"file_path":"[^"]*"' | head -1 | cut -d'"' -f4)
  [ -n "$fp" ] && file_path="$fp"
fi
[ -z "$file_path" ] && exit 0
[ -f "$file_path" ] || exit 0

# Index via REST API (background, fire-and-forget)
abs_path=$(realpath "$file_path" 2>/dev/null || echo "$file_path")
curl -sf -X POST "http://localhost:${JAVADUCKER_HTTP_PORT:-8080}/api/upload-file" \
  -H "Content-Type: application/json" \
  -d "{\"file_path\":\"$abs_path\"}" \
  >/dev/null 2>&1 &
```

---

## Setup

To recreate all hooks from this instructions file:

1. Create `scripts/local/hooks/` directory if it doesn't exist
2. Copy each code block above into the corresponding file in `scripts/local/hooks/`
3. Make executable: `chmod +x scripts/local/hooks/*.sh`
4. Hooks are referenced by `.claude/settings.json` and will work automatically once recreated
