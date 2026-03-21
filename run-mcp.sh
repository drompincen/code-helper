#!/bin/bash
set -e
cd "$(dirname "$0")"

VENV_DIR="mcp/.venv"

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating Python venv..." >&2
    python3 -m venv "$VENV_DIR"
    "$VENV_DIR/bin/pip" install -q -r mcp-requirements.txt
fi

exec "$VENV_DIR/bin/python" -m mcp.server
