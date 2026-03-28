#!/bin/bash
# Hook: Check if JavaDucker has artifacts pending enrichment
# Runs at session start and after index operations
# Outputs a reminder to the session if artifacts need enrichment

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
  # Get total pending count
  FULL_QUEUE=$(curl -sf "${BASE_URL}/enrich-queue?limit=100" 2>/dev/null || echo '{"count":0}')
  TOTAL=$(echo "$FULL_QUEUE" | jq -r '.count' 2>/dev/null || echo "0")
  echo "[Content Intelligence] ${TOTAL} artifact(s) pending enrichment. Use javaducker_enrich_queue to see them, then classify/tag/extract points using the write MCP tools."
fi
