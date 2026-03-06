#!/bin/bash
set -e
cd "$(dirname "$0")"

# Build if needed
if [ ! -d target/dependency ] || [ ! -d target/classes ]; then
    echo "Building project..."
    mvn -q package -DskipTests
fi

java -cp "target/classes:target/dependency/*" \
    com.javaducker.client.JavaDuckerClient "$@"
