# Plan: JavaDucker MCP Server for Claude Code

## Context

JavaDucker v2 is a Java/Spring Boot gRPC server that ingests source files, indexes them into DuckDB with TF-IDF embeddings, and exposes exact/semantic/hybrid search. The goal is to expose this as an MCP (Model Context Protocol) server so Claude Code can use it as a tool when working with large codebases — indexing directories and searching across them.

## Approach

Create a Python MCP server in a new `mcp/` subdirectory that wraps the existing `run-client.sh` CLI via subprocess. No changes to Java source or pom.xml. The MCP server auto-starts the Java server if it's not running.

## File Structure

```
code-helper/
├── mcp/
│   ├── __init__.py
│   ├── config.py          # env-var-based config (host, port, paths, timeouts)
│   ├── server_manager.py  # TCP health check + auto-start via run-server.sh
│   ├── client.py          # subprocess wrapper around run-client.sh
│   ├── parsers.py         # parse CLI text output into dicts
│   └── server.py          # FastMCP app with all tool definitions
├── mcp-requirements.txt   # mcp[cli]>=1.0.0
├── run-mcp.sh             # venv setup + python -m mcp.server launcher
└── docs/mcp-plan.md       # this document
```

## MCP Tools

| Tool | Description |
|------|-------------|
| `javaducker_health` | Check server health + version |
| `javaducker_index_file` | Upload + index a single file; returns artifact_id |
| `javaducker_index_directory` | Recursively index all files in a directory |
| `javaducker_search` | Search indexed code (exact/semantic/hybrid) |
| `javaducker_get_file_text` | Retrieve full extracted text by artifact_id |
| `javaducker_get_artifact_status` | Check ingestion lifecycle status |
| `javaducker_wait_for_indexed` | Poll until artifact reaches INDEXED or FAILED |
| `javaducker_stats` | Aggregate counts: artifacts, chunks, bytes, by status |

## Module Details

### `mcp/config.py`
Dataclass reading from env vars with defaults:
- `PROJECT_ROOT` — absolute path, auto-detected from `__file__`
- `GRPC_HOST=localhost`, `GRPC_PORT=9090`
- `SERVER_STARTUP_TIMEOUT_SECS=60`, poll every 2s
- `INGESTION_POLL_TIMEOUT_SECS=120`, poll every 3s
- `DEFAULT_EXTENSIONS=.java,.xml,.md,.yml,.json,.txt,.pdf`
- `DEFAULT_MAX_RESULTS=20`

### `mcp/server_manager.py`
- `is_server_healthy(host, port)` — TCP socket probe (< 1ms on localhost)
- `ensure_server_running(config)` — no-op if healthy; otherwise Popen `run-server.sh` with `start_new_session=True` (Java outlives MCP process), then polls until healthy or timeout

### `mcp/client.py`
- `run_cli(config, *args, timeout=30) -> str` — runs `run-client.sh --host H --port P <args>`, raises RuntimeError on non-zero exit
- One helper per subcommand: `cli_health`, `cli_upload_file`, `cli_upload_dir`, `cli_find`, `cli_cat`, `cli_status`, `cli_stats`

### `mcp/parsers.py`
Pure functions parsing CLI stdout into dicts. Line-by-line scanning (no regex). Key parsers:
- `parse_health`, `parse_upload_file`, `parse_upload_dir`
- `parse_find` — splits on `#N [match_type]` blocks
- `parse_cat`, `parse_status`, `parse_stats`

### `mcp/server.py`
```python
from mcp.server.fastmcp import FastMCP
mcp = FastMCP("javaducker")

# Every tool calls ensure_server_running(config) first
@mcp.tool()
def javaducker_search(phrase: str, mode: str = "hybrid", max_results: int = 20): ...

if __name__ == "__main__":
    mcp.run()
```

### `run-mcp.sh`
```bash
#!/bin/bash
set -e
cd "$(dirname "$0")"
VENV_DIR="mcp/.venv"
if [ ! -d "$VENV_DIR" ]; then
    python3 -m venv "$VENV_DIR"
    "$VENV_DIR/bin/pip" install -q -r mcp-requirements.txt
fi
exec "$VENV_DIR/bin/python" -m mcp.server
```

## Claude Code Registration

```bash
claude mcp add javaducker -- /absolute/path/to/code-helper/run-mcp.sh
```

Or via `.mcp.json` in project root:
```json
{
  "mcpServers": {
    "javaducker": {
      "command": "/absolute/path/to/code-helper/run-mcp.sh",
      "env": {
        "GRPC_HOST": "localhost",
        "GRPC_PORT": "9090",
        "PROJECT_ROOT": "/absolute/path/to/code-helper"
      }
    }
  }
}
```

## Typical Claude Workflow

1. `javaducker_index_directory(directory="/path/to/repo")` — submit all files
2. `javaducker_stats()` — monitor ingestion progress
3. `javaducker_search(phrase="...", mode="hybrid")` — search (works even mid-ingestion)
4. `javaducker_get_file_text(artifact_id="...")` — read full file content

## Implementation Order

1. `mcp/config.py`
2. `mcp/server_manager.py`
3. `mcp/client.py`
4. `mcp/parsers.py`
5. `mcp/__init__.py`
6. `mcp/server.py` (ties everything together)
7. `mcp-requirements.txt`
8. `run-mcp.sh`

## Verification

1. `mvn package -DskipTests` — build the JAR
2. `bash run-mcp.sh` — confirm venv created, server auto-starts
3. `claude mcp add javaducker -- $(pwd)/run-mcp.sh`
4. Ask Claude: "Index the test-corpus directory using JavaDucker"
5. Ask Claude: "Find code related to material change monitoring"
6. Confirm results with ranked previews and artifact IDs

## Key Design Decisions

- **No Java changes** — MCP layer is pure Python wrapping the existing CLI
- **Auto-start** — `ensure_server_running()` called on every tool; Java process outlives MCP via `start_new_session=True`
- **Subprocess over gRPC stubs** — simpler setup, no proto compilation; parsers are isolated in `parsers.py` so format changes are easy to fix
- **Async ingestion handled by tools** — `wait_for_indexed` and `stats` tools let Claude monitor progress without blocking the entire session

## Critical Files (reference before implementing)

- `run-client.sh` — exact arg syntax and output format parsers must match
- `run-server.sh` — env vars (`DB`, `GRPC_PORT`, `INTAKE_DIR`) and cwd behavior
- `src/main/proto/javaducker.proto` — status enum values, field names
- `src/main/java/com/javaducker/client/JavaDuckerClient.java` — exact CLI output strings
