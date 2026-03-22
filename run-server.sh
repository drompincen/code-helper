#!/bin/bash
set -e
cd "$(dirname "$0")"

# Build if needed
if [ ! -f target/javaducker-1.0.0.jar ]; then
    echo "Building project..."
    mvn -q package -DskipTests
fi

# Default args
DB="${DB:-data/javaducker.duckdb}"
HTTP_PORT="${HTTP_PORT:-8080}"
INTAKE_DIR="${INTAKE_DIR:-temp/intake}"

echo "Starting JavaDucker Server on HTTP port $HTTP_PORT"
java -jar target/javaducker-1.0.0.jar \
    --javaducker.db-path="$DB" \
    --server.port="$HTTP_PORT" \
    --javaducker.intake-dir="$INTAKE_DIR" \
    "$@"
