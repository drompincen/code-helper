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
