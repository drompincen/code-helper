---
name: add-javaducker
description: Configure JavaDucker as an optional companion tool for semantic code search and indexing
user-invocable: true
---

# Add JavaDucker

You are setting up JavaDucker as a companion tool for this project. JavaDucker provides semantic code indexing, search, dependency analysis, and project mapping via MCP tools.

## What you need

The user must provide the **JavaDucker root folder** — the directory containing `JavaDuckerMcpServer.java` and `run-mcp.sh`.

If the user doesn't provide a path, look for it in sibling directories:
- `../code-helper`
- `../javaducker`

## Setup Process

1. **Get the path** — ask the user or auto-detect from sibling directories

2. **Validate** — confirm these files exist at the root:
   - `JavaDuckerMcpServer.java`
   - `run-mcp.sh`
   - `run-server.sh`
   If any are missing, stop and report the error.

3. **Create local data directory** — create `.claude/.javaducker/` in the project root:
   ```bash
   mkdir -p .claude/.javaducker/intake
   ```
   This is where the DuckDB database and intake files live — per-project, gitignored.

4. **Find a free port** — scan ports 8080-8180 to find one that's not in use:
   ```bash
   for port in $(seq 8080 8180); do
     if ! (echo >/dev/tcp/localhost/$port) 2>/dev/null; then
       echo "Using port $port"
       break
     fi
   done
   ```

5. **Write config** — create `.claude/.state/javaducker.conf`:
   ```
   JAVADUCKER_ROOT=/absolute/path/to/javaducker
   JAVADUCKER_HTTP_PORT=<free port found in step 4>
   JAVADUCKER_DB=/absolute/path/to/project/.claude/.javaducker/javaducker.duckdb
   JAVADUCKER_INTAKE=/absolute/path/to/project/.claude/.javaducker/intake
   ```
   All paths must be absolute.

6. **Register MCP server** — create or merge `.mcp.json` in the project root:
   ```json
   {
     "mcpServers": {
       "javaducker": {
         "command": "jbang",
         "args": ["JAVADUCKER_ROOT/JavaDuckerMcpServer.java"],
         "env": {
           "PROJECT_ROOT": "<project root absolute path>",
           "HTTP_PORT": "<port from step 4>"
         }
       }
     }
   }
   ```
   Replace placeholders with actual absolute paths and port.

   **If `.mcp.json` already exists**, read it first and merge the `javaducker` key into the existing `mcpServers` object. Do not overwrite other MCP servers.

7. **Start the server** — launch with project-local data paths:
   ```bash
   DB=<JAVADUCKER_DB> HTTP_PORT=<port> INTAKE_DIR=<JAVADUCKER_INTAKE> \
     nohup bash <JAVADUCKER_ROOT>/run-server.sh >/dev/null 2>&1 &
   ```
   Wait up to 10 seconds for it to become healthy (poll `/api/health`). The server auto-starts on future sessions via the memory-sync hook using `javaducker_start()`.

8. **Index the project** — once the server is healthy, use `javaducker_index_directory` with the project root. Or via CLI:
   ```bash
   bash <JAVADUCKER_ROOT>/run-client.sh --port <port> upload-dir --root <PROJECT_DIR> --ext .java,.xml,.md,.yml,.yaml,.json,.properties,.gradle,.kt,.py,.go,.rs,.ts,.js
   ```

9. **Index past sessions** (optional) — ask the user if they want to index past Claude Code sessions. If yes, use `javaducker_index_sessions`.

10. **Confirm setup** — print a short confirmation:
    ```
    JavaDucker ready. Look for "JD" in the statusline.
      Root:     /path/to/javaducker
      Port:     <port>
      Database: .claude/.javaducker/javaducker.duckdb
      Intake:   .claude/.javaducker/intake/
      Index:    started for current project
    ```

## How it works for the user

After setup, JavaDucker is invisible:
- **Statusline** shows `JD` when active, `JD(off)` if the server is down
- **Server auto-starts** on each session — finds a free port if the saved one is taken
- **Data stays local** — each project has its own database in `.claude/.javaducker/`
- **Edited files auto-index** via the post-edit hook
- **All drom-flow skills** automatically use JavaDucker for deeper search when available
- **No CLI commands needed** — everything happens through MCP tools and hooks

To remove: use `/remove-javaducker`

## Important notes

- First MCP connection may take 10-20 seconds (jbang compiles the Java file on first run)
- `.mcp.json` and `.claude/.javaducker/` are gitignored (machine-specific)
- The config is machine-specific — each developer runs `/add-javaducker` once
- Multiple projects can run simultaneously — each gets its own port and database
