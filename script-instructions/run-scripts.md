# Run Scripts

These scripts launch the JavaDucker application in different modes.
Recreate them in `scripts/local/` (which is gitignored).

---

## run-cli.sh

```bash
#!/bin/bash
cd "$(dirname "$0")/.."

DB="${DB:-data/javaducker.duckdb}"
INTAKE_DIR="${INTAKE_DIR:-temp/intake}"

echo "Building project..."
mvn -q package -DskipTests

echo "Starting JavaDucker CLI"
java -cp target/javaducker-1.0.0.jar \
    com.drom.javaducker.cli.CliMain \
    --javaducker.db-path="$DB" \
    --javaducker.intake-dir="$INTAKE_DIR" "$@"
```

---

## run-cli.cmd

```cmd
@echo off
cd /d "%~dp0\.."

set "JAVA_HOME=C:\Users\drom\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Building project...
call mvn -q package -DskipTests

echo Starting JavaDucker CLI
java -cp target\javaducker-1.0.0.jar ^
    com.drom.javaducker.cli.CliMain ^
    --javaducker.db-path="data\javaducker.duckdb" ^
    --javaducker.intake-dir="temp\intake" %*
```

---

## run-client.sh

```bash
#!/bin/bash
# Run the MCP client (interactive CLI over stdio transport)
cd "$(dirname "$0")/.."

DB="${DB:-data/javaducker.duckdb}"
HTTP_PORT="${HTTP_PORT:-8080}"
INTAKE_DIR="${INTAKE_DIR:-temp/intake}"

echo "Building project..."
mvn -q package -DskipTests

echo "Starting JavaDucker MCP Client..."
java -cp target/javaducker-1.0.0.jar \
    com.drom.javaducker.McpClientRunner \
    --javaducker.db-path="$DB" \
    --server.port="$HTTP_PORT" \
    --javaducker.intake-dir="$INTAKE_DIR" "$@"
```

---

## run-mcp.sh

Uses Spring AI MCP server with stdio transport (no JBang needed).

```bash
#!/bin/bash
cd "$(dirname "$0")/.."

DB="${DB:-data/javaducker.duckdb}"
INTAKE_DIR="${INTAKE_DIR:-temp/intake}"

mvn -q package -DskipTests 1>&2

java -jar target/javaducker-1.0.0.jar \
    --spring.profiles.active=mcp \
    --javaducker.db-path="$DB" \
    --javaducker.intake-dir="$INTAKE_DIR" "$@"
```

---

## run-mcp.cmd

```cmd
@echo off
cd /d "%~dp0\.."

set "JAVA_HOME=C:\Users\drom\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Building project... 1>&2
call mvn -q package -DskipTests 1>&2

if "%DB%"=="" set DB=data\javaducker.duckdb
if "%INTAKE_DIR%"=="" set INTAKE_DIR=temp\intake

java -jar target\javaducker-1.0.0.jar ^
    --spring.profiles.active=mcp ^
    --javaducker.db-path="%DB%" ^
    --javaducker.intake-dir="%INTAKE_DIR%" %*
```

---

## run-server.sh

```bash
#!/bin/bash
cd "$(dirname "$0")/.."

DB="${DB:-data/javaducker.duckdb}"
HTTP_PORT="${HTTP_PORT:-8080}"
INTAKE_DIR="${INTAKE_DIR:-temp/intake}"

echo "Building project..."
mvn -q package -DskipTests

echo "Starting JavaDucker Server on HTTP port $HTTP_PORT"
java -jar target/javaducker-1.0.0.jar \
    --javaducker.db-path="$DB" \
    --server.port="$HTTP_PORT" \
    --javaducker.intake-dir="$INTAKE_DIR" "$@"
```

---

## run-server.cmd

```cmd
@echo off
cd /d "%~dp0\.."

set "JAVA_HOME=C:\Users\drom\.jdks\openjdk-23.0.2"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo Building project...
call mvn -q package -DskipTests

if "%DB%"=="" set DB=data\javaducker.duckdb
if "%HTTP_PORT%"=="" set HTTP_PORT=8080
if "%INTAKE_DIR%"=="" set INTAKE_DIR=temp\intake

echo Starting JavaDucker Server on HTTP port %HTTP_PORT%
java -jar target\javaducker-1.0.0.jar ^
    --javaducker.db-path="%DB%" ^
    --server.port=%HTTP_PORT% ^
    --javaducker.intake-dir="%INTAKE_DIR%" %*
```

---

## Setup

To recreate these scripts from this instructions file:

1. Create `scripts/local/` directory if it doesn't exist
2. Copy each code block above into the corresponding file in `scripts/local/`
3. On Linux/macOS, make `.sh` files executable: `chmod +x scripts/local/*.sh`
