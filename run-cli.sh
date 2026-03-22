#!/bin/sh
cd "$(dirname "$0")"

HTTP_PORT="${HTTP_PORT:-8080}"
JAVADUCKER_HOST="${JAVADUCKER_HOST:-localhost}"

java -cp "target/javaducker-1.0.0.jar:target/dependency/*" \
    com.javaducker.cli.InteractiveCli --host "$JAVADUCKER_HOST" --port "$HTTP_PORT" "$@"
