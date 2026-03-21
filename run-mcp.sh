#!/bin/bash
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
export PROJECT_ROOT="$SCRIPT_DIR"
exec jbang "$SCRIPT_DIR/JavaDuckerMcpServer.java"
