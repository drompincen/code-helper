#!/bin/bash
# Content Intelligence Enrichment Script
# Triggered by Claude Code hooks to process the enrichment queue.
# Calls JavaDucker REST API to fetch pending artifacts, then uses Claude Code
# to classify, tag, extract salient points, and assess freshness.
#
# Usage: bash scripts/enrich.sh [--limit N] [--host HOST] [--port PORT]
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

  # Build the enrichment prompt for Claude Code
  # The actual LLM call happens when Claude Code processes this script's output
  # For now, output the artifact info so the calling Claude Code session can enrich it
  echo "[enrich] Artifact ready for enrichment:"
  echo "  ID: ${ARTIFACT_ID}"
  echo "  File: ${FILE_NAME}"
  echo "  Text length: ${#EXTRACTED_TEXT}"
  echo "  Related artifacts: $(echo "$RELATED" | jq -r '.count')"

  # Mark as enriching (claim the artifact)
  curl -sf -X POST "${BASE_URL}/freshness" \
    -H "Content-Type: application/json" \
    -d "{\"artifactId\":\"${ARTIFACT_ID}\",\"freshness\":\"current\"}" > /dev/null 2>&1 || true

  # The actual classification, tagging, and point extraction is done by Claude Code
  # using the MCP tools (javaducker_classify, javaducker_tag, javaducker_extract_points, etc.)
  # This script provides the pipeline structure; Claude Code provides the intelligence.
  echo "[enrich] Artifact ${ARTIFACT_ID} queued for Claude Code enrichment"
done

echo "[enrich] Enrichment pass complete"
