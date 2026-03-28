#!/bin/bash
set -e
cd "$(dirname "$0")"

# Build if needed
if [ ! -d target/dependency ] || [ ! -d target/classes ]; then
    echo "Building project..."
    mvn -q package -DskipTests
fi

# If --enrich flag, run the enrichment script instead
if [ "$1" = "--enrich" ] || [ "$1" = "enrich" ]; then
    shift
    exec bash scripts/enrich.sh "$@"
fi

java -cp "target/classes:target/dependency/*" \
    com.javaducker.client.JavaDuckerClient "$@"
