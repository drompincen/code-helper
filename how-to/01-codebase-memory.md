# Use Case 1: Codebase Memory for Large Repositories

Use JavaDucker as a persistent, searchable memory for large codebases — so Claude can find relevant code
without reading every file, and so context survives across sessions.

## The Problem

Large codebases don't fit in a context window. When you ask Claude about a service, a bug, or a pattern,
it has no way to find the right files without browsing the whole repo. JavaDucker solves this by indexing
everything once and letting Claude search semantically at any time.

## Setup (one time per project)

**1. Build and start the server**

```bash
mvn package -DskipTests
./run-server.sh        # Linux/Mac
run-server.cmd         # Windows
```

**2. Configure the MCP server in Claude Code**

Add to your `.claude/settings.json` (or `settings.local.json`):

```json
{
  "mcpServers": {
    "javaducker": {
      "command": "jbang",
      "args": ["JavaDuckerMcpServer.java"],
      "cwd": "/path/to/code-helper",
      "env": {
        "PROJECT_ROOT": "/path/to/code-helper"
      }
    }
  }
}
```

**3. Index your repository**

Ask Claude:
> "Index the entire repo at `/path/to/my-project` using javaducker_index_directory"

Or run directly:
```bash
./run-client.sh --host localhost --port 9090 \
  upload-dir --root /path/to/my-project --ext .java,.xml,.md,.yml,.json
```

Monitor progress:
> "Check javaducker_stats until all artifacts are indexed"

## Daily Usage with Claude

Once indexed, you never re-index unless the codebase changes significantly. Just ask Claude naturally:

**Finding implementations**
> "Search javaducker for how authentication tokens are validated"
> "Find where the OrderService calls the payment gateway — use exact search for 'PaymentGateway'"

**Understanding unfamiliar code**
> "Search for 'retry logic' and read the full text of the top result"
> "Find all places that throw DataNotFoundException and show me the context"

**Cross-cutting concerns**
> "Semantic search for 'database connection pool configuration'"
> "Find all @Scheduled annotations and describe what each job does"

**Before making changes**
> "Before I modify the UserRepository, search for all callers of findByEmail"

## Typical Search Strategy

| Goal | Mode | Example phrase |
|------|------|----------------|
| Find a specific class or method | `exact` | `UserAuthService` |
| Find an annotation pattern | `exact` | `@Transactional(readOnly` |
| Understand a concept | `semantic` | `how session expiry is handled` |
| General exploration | `hybrid` | `order cancellation flow` |

## Re-indexing After Changes

Index only what changed — no need to wipe and restart:

```bash
# Re-index a single modified file
./run-client.sh upload-file --file src/main/java/com/example/OrderService.java

# Re-index a module that changed
./run-client.sh upload-dir --root src/main/java/com/example/payments --ext .java
```

Or ask Claude:
> "Re-index the payments module at `/path/to/src/payments`"

## Tips

- Index `.md` and `.yml` files too — config and docs are often where the real answers are
- Use `javaducker_get_file_text` after a search hit to read the complete file, not just the chunk
- Keep the DuckDB file (`data/javaducker.duckdb`) — it persists across server restarts
- For monorepos, index each service separately with a meaningful root path so file paths in results are clear
