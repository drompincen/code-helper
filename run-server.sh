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
GRPC_PORT="${GRPC_PORT:-9090}"
INTAKE_DIR="${INTAKE_DIR:-temp/intake}"

echo "Starting JavaDucker Server on gRPC port $GRPC_PORT"
java -jar target/javaducker-1.0.0.jar \
    --javaducker.db-path="$DB" \
    --grpc.server.port="$GRPC_PORT" \
    --javaducker.intake-dir="$INTAKE_DIR" \
    "$@"
