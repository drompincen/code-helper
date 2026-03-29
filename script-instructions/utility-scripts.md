# Utility Scripts

Pipeline and orchestration scripts. Recreate in `scripts/local/`.

---

## enrich.sh

Content Intelligence enrichment script — processes the JavaDucker enrichment queue via REST API.

```bash
#!/bin/bash
# Content Intelligence Enrichment Script
# Triggered by Claude Code hooks to process the enrichment queue.
# Calls JavaDucker REST API to fetch pending artifacts, then uses Claude Code
# to classify, tag, extract salient points, and assess freshness.
#
# Usage: bash scripts/local/enrich.sh [--limit N] [--host HOST] [--port PORT]
set -e

HOST="${JAVADUCKER_HOST:-localhost}"
PORT="${JAVADUCKER_PORT:-8080}"
LIMIT=10
BASE_URL="http://${HOST}:${PORT}/api"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Parse args
while [[ $# -gt 0 ]]; do
  case $1 in
    --limit) LIMIT="$2"; shift 2 ;;
    --host)  HOST="$2"; BASE_URL="http://${HOST}:${PORT}/api"; shift 2 ;;
    --port)  PORT="$2"; BASE_URL="http://${HOST}:${PORT}/api"; shift 2 ;;
    *) shift ;;
  esac
done

# Check if server is running
if ! curl -sf "${BASE_URL}/health" > /dev/null 2>&1; then
  echo "[enrich] JavaDucker server not running at ${BASE_URL}, skipping enrichment"
  exit 0
fi

# Fetch enrichment queue
QUEUE=$(curl -sf "${BASE_URL}/enrich-queue?limit=${LIMIT}")
COUNT=$(echo "$QUEUE" | jq -r '.count')

if [ "$COUNT" = "0" ] || [ -z "$COUNT" ]; then
  echo "[enrich] No artifacts pending enrichment"
  exit 0
fi

echo "[enrich] Processing ${COUNT} artifacts from enrichment queue"

# Process each artifact
echo "$QUEUE" | jq -r '.queue[].artifact_id' | while read -r ARTIFACT_ID; do
  echo "[enrich] Processing artifact: ${ARTIFACT_ID}"

  # Fetch artifact text
  TEXT_RESPONSE=$(curl -sf "${BASE_URL}/text/${ARTIFACT_ID}" 2>/dev/null || echo '{"error":"not found"}')
  if echo "$TEXT_RESPONSE" | jq -e '.error' > /dev/null 2>&1; then
    echo "[enrich] Skipping ${ARTIFACT_ID}: no text available"
    continue
  fi

  FILE_NAME=$(echo "$QUEUE" | jq -r --arg id "$ARTIFACT_ID" '.queue[] | select(.artifact_id == $id) | .file_name')
  EXTRACTED_TEXT=$(echo "$TEXT_RESPONSE" | jq -r '.extracted_text')

  # Fetch related artifacts for freshness evaluation
  RELATED=$(curl -sf "${BASE_URL}/related-by-concept/${ARTIFACT_ID}" 2>/dev/null || echo '{"related":[]}')

  echo "[enrich] Artifact ready for enrichment:"
  echo "  ID: ${ARTIFACT_ID}"
  echo "  File: ${FILE_NAME}"
  echo "  Text length: ${#EXTRACTED_TEXT}"
  echo "  Related artifacts: $(echo "$RELATED" | jq -r '.count')"

  # Mark as enriching (claim the artifact)
  curl -sf -X POST "${BASE_URL}/freshness" \
    -H "Content-Type: application/json" \
    -d "{\"artifactId\":\"${ARTIFACT_ID}\",\"freshness\":\"current\"}" > /dev/null 2>&1 || true

  echo "[enrich] Artifact ${ARTIFACT_ID} queued for Claude Code enrichment"
done

echo "[enrich] Enrichment pass complete"
```

---

## index-sessions.sh

Indexes Claude Code session transcripts for the current project.

```bash
#!/usr/bin/env bash
# Index Claude Code session transcripts for the current project
set -euo pipefail

JAVADUCKER_PORT="${HTTP_PORT:-8080}"
JAVADUCKER_HOST="${JAVADUCKER_HOST:-localhost}"
BASE_URL="http://${JAVADUCKER_HOST}:${JAVADUCKER_PORT}/api"

# Find the project sessions directory
PROJECT_ROOT="${PROJECT_ROOT:-.}"
PROJECT_HASH=$(echo -n "$(cd "$PROJECT_ROOT" && pwd)" | md5sum | cut -d' ' -f1)
SESSIONS_DIR="$HOME/.claude/projects/${PROJECT_HASH}"

if [ ! -d "$SESSIONS_DIR" ]; then
    echo "No sessions directory found at $SESSIONS_DIR"
    exit 0
fi

echo "Indexing sessions from $SESSIONS_DIR..."
curl -s -X POST "${BASE_URL}/index-sessions" \
    -H "Content-Type: application/json" \
    -d "{\"projectPath\": \"${SESSIONS_DIR}\", \"incremental\": true}" | jq .
```

---

## orchestrate.sh

Closed-loop orchestration template for drom-flow pipelines.

```bash
#!/bin/bash
# drom-flow orchestration script template
# Copy and customize this for your project's pipeline.
#
# Usage:
#   ./scripts/local/orchestrate.sh [--iteration N] [--max N] [--check-only]
#
# Output:
#   Writes JSON report to ./reports/iteration-N.json
#   Exit 0 = all pass, Exit 1 = issues remain, Exit 2 = error

set -euo pipefail

# --- Configuration (customize these) ---
CHECK_CMD="echo 'Override CHECK_CMD with your test/check command'"
REPORT_DIR="./reports"
MAX_ITERATIONS=10
# ----------------------------------------

# Parse arguments
ITERATION=1
CHECK_ONLY=false
while [[ $# -gt 0 ]]; do
  case $1 in
    --iteration) ITERATION="$2"; shift 2 ;;
    --max) MAX_ITERATIONS="$2"; shift 2 ;;
    --check-only) CHECK_ONLY=true; shift ;;
    *) echo "Unknown arg: $1"; exit 2 ;;
  esac
done

mkdir -p "$REPORT_DIR"

run_check() {
  local iter=$1
  local report="$REPORT_DIR/iteration-${iter}.json"
  local start_time=$(date +%s)

  echo "[orchestrate] Iteration $iter — running check..."

  # Run the check command, capture output
  local exit_code=0
  local output
  output=$(eval "$CHECK_CMD" 2>&1) || exit_code=$?

  local end_time=$(date +%s)
  local duration=$((end_time - start_time))

  # Write report
  cat > "$report" <<INNEREOF
{
  "iteration": $iter,
  "timestamp": "$(date -Iseconds)",
  "durationSeconds": $duration,
  "exitCode": $exit_code,
  "output": $(echo "$output" | python3 -c 'import sys,json; print(json.dumps(sys.stdin.read()))' 2>/dev/null || echo "\"$output\"")
}
INNEREOF

  echo "[orchestrate] Report written to $report (exit code: $exit_code, ${duration}s)"
  return $exit_code
}

compare_iterations() {
  local prev="$REPORT_DIR/iteration-$(($1 - 1)).json"
  local curr="$REPORT_DIR/iteration-$1.json"

  if [ ! -f "$prev" ]; then
    echo "[orchestrate] No previous iteration to compare"
    return 0
  fi

  local prev_exit=$(python3 -c "import json; print(json.load(open('$prev'))['exitCode'])" 2>/dev/null || echo "1")
  local curr_exit=$(python3 -c "import json; print(json.load(open('$curr'))['exitCode'])" 2>/dev/null || echo "1")

  echo "[orchestrate] Previous exit: $prev_exit → Current exit: $curr_exit"

  if [ "$curr_exit" -gt "$prev_exit" ]; then
    echo "[orchestrate] WARNING: Possible regression detected"
    return 1
  fi
  return 0
}

# --- Main ---

if [ "$CHECK_ONLY" = true ]; then
  run_check "$ITERATION"
  exit $?
fi

echo "[orchestrate] Starting closed loop: iteration $ITERATION, max $MAX_ITERATIONS"

while [ "$ITERATION" -le "$MAX_ITERATIONS" ]; do
  if run_check "$ITERATION"; then
    echo "[orchestrate] ALL CHECKS PASSED at iteration $ITERATION"
    exit 0
  fi

  if [ "$ITERATION" -gt 1 ]; then
    if ! compare_iterations "$ITERATION"; then
      echo "[orchestrate] Regression at iteration $ITERATION — stopping for review"
      exit 1
    fi
  fi

  echo "[orchestrate] Issues remain. Report: $REPORT_DIR/iteration-${ITERATION}.json"
  echo "[orchestrate] Waiting for fixes before next iteration..."
  exit 1

done

echo "[orchestrate] Max iterations ($MAX_ITERATIONS) reached"
exit 1
```

---

## enrich-batch.ps1

PowerShell batch enrichment script for classifying, tagging, and extracting points.

```powershell
param(
    [string]$Base = "http://localhost:8081/api",
    [string]$ArtifactsCsv,      # pipe-delimited: id|file_name
    [string]$DocType,            # ADR, MEETING_NOTES, etc.
    [string]$TagsJson,           # JSON array of {tag,tag_type,source}
    [string]$PointsMode = "none" # "none", "auto", or path to points JSON file
)

function Post($path, $body) {
    $json = $body | ConvertTo-Json -Depth 5 -Compress
    try {
        Invoke-RestMethod "$Base$path" -Method POST -ContentType "application/json" -Body $json
    } catch {
        Write-Warning "POST $path failed: $_"
    }
}

$lines = $ArtifactsCsv -split "`n" | Where-Object { $_.Trim() -ne "" }
$total = $lines.Count
$i = 0

foreach ($line in $lines) {
    $parts = $line.Trim() -split "\|"
    $id = $parts[0]
    $fname = $parts[1]
    $i++

    # Classify
    if ($DocType) {
        Post "/classify" @{ artifactId=$id; docType=$DocType; confidence=1; method="llm" }
    }

    # Tag
    if ($TagsJson) {
        $tags = $TagsJson | ConvertFrom-Json
        Post "/tag" @{ artifactId=$id; tags=$tags }
    }

    # Points from file
    if ($PointsMode -ne "none" -and $PointsMode -ne "auto" -and (Test-Path $PointsMode)) {
        $pointsData = Get-Content $PointsMode -Raw | ConvertFrom-Json
        $artifactPoints = $pointsData | Where-Object { $_.artifact_id -eq $id }
        if ($artifactPoints) {
            Post "/salient-points" @{ artifactId=$id; points=$artifactPoints.points }
        }
    }

    # Mark enriched
    Post "/mark-enriched" @{ artifactId=$id }

    if ($i % 10 -eq 0 -or $i -eq $total) {
        Write-Output "  [$i/$total] enriched"
    }
}
Write-Output "Done: $total artifacts enriched as $DocType"
```

---

## Setup

To recreate these scripts:

1. Create `scripts/local/` directory if it doesn't exist
2. Copy each code block above into the corresponding file in `scripts/local/`
3. On Linux/macOS, make `.sh` files executable: `chmod +x scripts/local/*.sh`
