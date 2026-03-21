# JavaDucker as a Brain/Memory for Claude

This guide explains how to use JavaDucker as persistent semantic memory for Claude — letting it search, recall, and reason over any codebase or document corpus across multiple sessions.

---

## Core Concept

JavaDucker stores chunked, vectorized text in DuckDB. Once indexed, Claude can search it at any time using exact, semantic, or hybrid queries — effectively giving Claude a searchable external brain for large codebases that exceed its context window.

---

## Setup (one-time)

### 1. Build the server
```bash
mvn package -DskipTests
```

### 2. Register with Claude Code
```bash
claude mcp add javaducker -- /absolute/path/to/code-helper/run-mcp.sh
```

Or add to `.mcp.json` in any project root:
```json
{
  "mcpServers": {
    "javaducker": {
      "command": "/absolute/path/to/code-helper/run-mcp.sh",
      "env": {
        "PROJECT_ROOT": "/absolute/path/to/code-helper"
      }
    }
  }
}
```

The server auto-starts when Claude first calls a tool. The DuckDB file persists between sessions.

---

## Skills: Prompts to Use JavaDucker Effectively

### Skill 1 — Index a Codebase
> "Index the codebase at [path] into JavaDucker so you can search it."

Claude will call `javaducker_index_directory`, then `javaducker_stats` to confirm progress.
Use after cloning a new repo or when starting work on an unfamiliar project.

---

### Skill 2 — Semantic Code Search
> "Search JavaDucker for [concept/question]"
> Example: "Search JavaDucker for how authentication tokens are refreshed"

Claude picks `mode=semantic` for conceptual questions, `mode=exact` for literal strings/annotations.
Results include file name, chunk index, score, and a text preview.

---

### Skill 3 — Find Usages / References
> "Find all usages of [ClassName/annotation/method] in the indexed codebase"
> Example: "Find all usages of @Transactional"

Claude uses `mode=exact` with the literal string. Best for annotations, interface names, constants.

---

### Skill 4 — Read a Full File
> "Show me the full content of [file name] from JavaDucker"

Claude searches for the file name to get its `artifact_id`, then calls `javaducker_get_file_text`.

---

### Skill 5 — Understand an Unfamiliar Module
> "I'm new to this codebase. Use JavaDucker to explain how [feature/module] works."

Claude searches multiple related terms, reads relevant chunks, and synthesizes an explanation — without needing the entire repo in context.

---

### Skill 6 — Cross-File Reasoning
> "Using JavaDucker, trace the full flow from [entry point] to [outcome]"
> Example: "Trace the flow from HTTP request to database write in the order service"

Claude iteratively searches for each layer, reading chunks to follow the call chain.

---

### Skill 7 — Index Documents / Architecture Docs
> "Index the docs/ folder into JavaDucker"

JavaDucker handles `.md`, `.txt`, `.pdf`. Index architecture docs, ADRs, runbooks — Claude can then answer questions about them semantically.

---

### Skill 8 — Monitor Indexing Progress
> "How many files are still being indexed in JavaDucker?"

Claude calls `javaducker_stats` and reports pending vs indexed counts.

---

### Skill 9 — Check Indexing Status of a Specific File
> "Has [filename] finished indexing in JavaDucker?"

Claude searches for the file's artifact_id then calls `javaducker_get_artifact_status` or `javaducker_wait_for_indexed`.

---

### Skill 10 — Reset / Re-index
> "Re-index [directory] in JavaDucker" (future: clear old entries first)

Currently, re-uploading the same file creates a new artifact. For a clean re-index, delete the DuckDB file and restart:
```bash
rm data/javaducker.duckdb
# Server auto-creates schema on next start
```

---

## Available MCP Tools (reference)

| Tool | When Claude uses it |
|------|---------------------|
| `javaducker_health` | Verify server is up |
| `javaducker_index_file` | Index a single file |
| `javaducker_index_directory` | Bulk index a directory |
| `javaducker_search` | Search (exact/semantic/hybrid) |
| `javaducker_get_file_text` | Read full file content |
| `javaducker_get_artifact_status` | Check one file's status |
| `javaducker_wait_for_indexed` | Wait for file to finish indexing |
| `javaducker_stats` | Overall indexing progress |

---

## Search Mode Guide

| Mode | Best for |
|------|----------|
| `hybrid` (default) | Most queries — balanced precision + recall |
| `exact` | Annotations (`@Transactional`), class names, method names, error codes |
| `semantic` | Concepts ("how are payments validated"), questions, intent-based search |

---

## Tips for Best Results

- **Index before asking** — JavaDucker only knows what's been uploaded. Index a repo before asking Claude to search it.
- **Use hybrid for exploration** — Start with `hybrid` and switch to `exact` once you know the exact term.
- **Large repos take time** — DuckDB ingestion is fast but async. Use `javaducker_stats` to confirm `pending=0` before heavy searches.
- **PDFs are supported** — Index architecture docs, design specs, and runbooks alongside source code.
- **Persistent across sessions** — The DuckDB file lives at `data/javaducker.duckdb`. Indexed content is available in every future Claude session.

---

## Example Session

```
User: Index the src/ directory of my payments service
Claude: [calls javaducker_index_directory("/path/to/payments/src")]
        Uploaded 312 files.
        [calls javaducker_stats] — 287 indexed, 25 pending.

User: How are failed payments retried?
Claude: [calls javaducker_search("failed payment retry", mode="semantic")]
        Found 8 results. Top match: RetryScheduler.java chunk 2 (score 0.91)
        [calls javaducker_get_file_text(artifact_id="...")]
        Based on the code, failed payments are retried via an exponential backoff scheduler...

User: Find all places that call PaymentGateway.charge()
Claude: [calls javaducker_search("PaymentGateway.charge()", mode="exact")]
        Found 4 usages across: OrderService.java, SubscriptionService.java, ...
```
