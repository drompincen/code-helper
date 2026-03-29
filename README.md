# JavaDucker v2

[![CI](https://github.com/drompincen/code-helper/actions/workflows/ci.yml/badge.svg)](https://github.com/drompincen/code-helper/actions/workflows/ci.yml)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)

Code and content intelligence server for Claude Code. Indexes source files, documents, plans, notes, and threads into DuckDB with semantic search, dependency graphs, content classification, session history, and Reladomo support — all accessible via 49 MCP tools and 59 REST endpoints.

## Tech Stack

- **Java 21** + **Spring Boot 3.2**
- **REST API** (Spring MVC) — 59 endpoints
- **DuckDB** file-based persistence (JDBC) — 22 tables
- **MCP Server** (JBang + MCP SDK) — 49 tools for Claude Code
- **picocli** CLI client
- **Apache PDFBox** for PDF extraction
- **Apache POI** for Office documents (DOCX, XLSX, PPTX, DOC, XLS, PPT)
- **Jsoup** for HTML/EPUB extraction
- **TF-IDF hash vectorization** for self-contained semantic search
- **HNSW index** for approximate nearest neighbor search at scale

## Supported File Types

| Category | Extensions | Library |
|----------|-----------|---------|
| Source code | `.java` `.kt` `.scala` `.py` `.js` `.ts` `.go` `.rs` `.c` `.cpp` `.rb` `.php` `.swift` | JDK UTF-8 |
| Config / data | `.xml` `.json` `.yml` `.yaml` `.toml` `.properties` `.sql` `.csv` | JDK UTF-8 |
| Docs | `.md` `.txt` | JDK UTF-8 |
| PDF | `.pdf` | Apache PDFBox |
| Word | `.docx` `.doc` | Apache POI |
| PowerPoint | `.pptx` `.ppt` | Apache POI |
| Excel | `.xlsx` `.xls` | Apache POI |
| LibreOffice | `.odt` `.odp` `.ods` | Apache ODF Toolkit |
| HTML | `.html` `.htm` | Jsoup |
| eBook | `.epub` | Jsoup (ZIP+XHTML) |
| Rich Text | `.rtf` | JDK RTFEditorKit |
| Email | `.eml` | Jakarta Mail |
| Archive | `.zip` | JDK (recurses text entries, 50 MB / 500 entry limit) |

## Quick Start

### Prerequisites

- Java 21+
- Maven 3.9+

### Build

```bash
mvn package -DskipTests
```

### Start Server

```bash
./run-server.sh
# or:
java -jar target/javaducker-1.0.0.jar \
  --javaducker.db-path=data/javaducker.duckdb \
  --server.port=8080 \
  --javaducker.intake-dir=temp/intake
```

### Running Multiple Instances

Each project can have its own JavaDucker instance. Use different ports, database files, and intake directories to run multiple instances on the same machine:

```bash
# Instance 1: project-alpha on port 8080
java -jar target/javaducker-1.0.0.jar \
  --server.port=8080 \
  --javaducker.db-path=/data/alpha/javaducker.duckdb \
  --javaducker.intake-dir=/data/alpha/intake

# Instance 2: project-beta on port 8081
java -jar target/javaducker-1.0.0.jar \
  --server.port=8081 \
  --javaducker.db-path=/data/beta/javaducker.duckdb \
  --javaducker.intake-dir=/data/beta/intake

# Instance 3: project-gamma on port 8082
java -jar target/javaducker-1.0.0.jar \
  --server.port=8082 \
  --javaducker.db-path=/data/gamma/javaducker.duckdb \
  --javaducker.intake-dir=/data/gamma/intake
```

Each instance is fully isolated — its own DuckDB database, its own intake directory, its own port. The CLI client and MCP server connect to a specific instance via `--port` or `HTTP_PORT`.

### Use the CLI Client

Point the CLI at the correct port for your instance:

```bash
# Default instance (port 8080)
./run-client.sh health
./run-client.sh find --phrase "@Transactional" --mode exact

# Targeting a specific instance
./run-client.sh --port 8081 health
./run-client.sh --port 8081 upload-dir --root /projects/beta --ext .java,.xml,.md
./run-client.sh --port 8081 find --phrase "how onboarding approvals work" --mode hybrid

# Other commands
./run-client.sh --port 8082 upload-file --file ./docs/architecture.md
./run-client.sh --port 8082 index-health
./run-client.sh --port 8082 stats
```

## Features

### Core: Indexing & Search
- **File indexing** with automatic text extraction, chunking, and TF-IDF embedding
- **Three search modes**: exact (substring), semantic (cosine similarity), hybrid (weighted merge)
- **Line numbers** in search results for direct navigation (`file:line`)
- **HNSW index** for fast approximate nearest neighbor search at scale
- **Incremental re-indexing** — detects changed files by SHA-256 hash, replaces stale artifacts

### Code Intelligence
- **File summaries** — auto-generated structural digest (classes, methods, imports, line count)
- **Dependency/import graph** — parsed imports resolved to indexed artifacts
- **Explain** — single-call aggregator returning everything known about a file (summary, deps, dependents, tags, classification, related artifacts)
- **Git blame** — `git blame --porcelain` parsing with LRU cache and line range support
- **Co-change analysis** — git log co-change history to find files commonly edited together
- **Project map** — directory structure, file counts, largest files, recently indexed

### Content Intelligence
- **Document classification** — ADR, DESIGN_DOC, PLAN, MEETING_NOTES, THREAD, CODE, REFERENCE, etc.
- **Tagging** — topic, technology, and custom tags per artifact
- **Salient points** — extracted decisions, risks, actions, insights per document
- **Concepts** — cross-document concept tracking with mention counts and timeline
- **Concept links** — explicit cross-document relationships with strength scores
- **Freshness tracking** — mark artifacts as current, stale, or superseded
- **Synthesis** — compact summaries for stale/superseded artifacts with full text pruning
- **Concept health** — active/stale/fading trend analysis per concept

### Stale Index Warning
- **Bulk staleness check** — compare all indexed files against disk mtime
- **Search-time staleness banner** — search results annotated with `stale: true/false` and warning message
- **Index health tool** — actionable report ("12/200 files stale, run re-index")
- **MCP staleness footers** — `javaducker_search` and `javaducker_summarize` append warnings for stale files
- Configurable via `JAVADUCKER_STALENESS_CHECK=false` env var

### Session Transcript Indexing
- **JSONL parser** for Claude Code conversation files (`~/.claude/projects/`)
- **Incremental ingestion** with mtime tracking (skip unchanged files)
- **Session search** — search past conversations by phrase
- **Session context** — combined session excerpts + artifact search for full historical context
- **Decision extraction** — store and query decisions from past sessions, filterable by tag
- **Auto-index script** — `scripts/index-sessions.sh` for hook integration

### Watch Mode
- **Auto-index on file change** — monitors a directory for modifications
- **Extension filtering** — watch only `.java,.xml,.md` etc.
- Start/stop/status via REST and MCP

### Reladomo Support
- **Object model parsing** — extracts attributes, relationships, indices, temporal types from MithraObject XML
- **Relationship graph** — traverse N hops from any object
- **Shortest path** — find relationship chain between two objects
- **SQL schema derivation** — DDL from Reladomo object definitions
- **Finder pattern analysis** — ranked query patterns with source locations
- **Deep fetch profiles** — which relationships are eagerly loaded together
- **Temporal classification** — processing-date, business-date, bitemporal object metadata
- **Runtime config** — connection managers, cache strategies, object-to-DB mappings

## MCP Server (Claude Code Integration)

JavaDucker ships a JBang-based MCP server (`JavaDuckerMcpServer.java`) that exposes 49 tools for Claude Code.

### Setup

1. Start the JavaDucker server:
   ```bash
   ./run-server.sh   # or run-server.cmd on Windows
   ```

2. Register the MCP server in your Claude Code config (`.claude/settings.json` or `claude_desktop_config.json`):
   ```json
   {
     "mcpServers": {
       "javaducker": {
         "command": "/path/to/code-helper/run-mcp.sh"
       }
     }
   }
   ```

3. Environment variables (all optional):
   ```
   JAVADUCKER_HOST=localhost           (default: localhost)
   HTTP_PORT=8080                      (default: 8080)
   PROJECT_ROOT=.                      (default: .)
   JAVADUCKER_STALENESS_CHECK=true     (default: true, set false to disable)
   ```

### Multiple MCP Instances (per-project)

Register separate MCP servers for different projects, each pointing to its own JavaDucker instance:

```json
{
  "mcpServers": {
    "javaducker-alpha": {
      "command": "/path/to/code-helper/run-mcp.sh",
      "env": {
        "HTTP_PORT": "8080",
        "PROJECT_ROOT": "/projects/alpha"
      }
    },
    "javaducker-beta": {
      "command": "/path/to/code-helper/run-mcp.sh",
      "env": {
        "HTTP_PORT": "8081",
        "PROJECT_ROOT": "/projects/beta"
      }
    }
  }
}
```

Each MCP server connects to a different backend via `HTTP_PORT`. Start the corresponding Spring Boot instances first (see [Running Multiple Instances](#running-multiple-instances)).

The `PROJECT_ROOT` env var tells the MCP server where the git repo root is — used by `javaducker_blame`, `javaducker_related`, and `javaducker_stale` (git diff mode).

### MCP Tools

#### Indexing & Search

| Tool | Description |
|------|-------------|
| `javaducker_health` | Check server status and version |
| `javaducker_index_file` | Upload and index a single file by absolute path |
| `javaducker_index_directory` | Recursively index all source files under a directory |
| `javaducker_search` | Search indexed content (exact/semantic/hybrid) with staleness warnings |
| `javaducker_get_file_text` | Retrieve full extracted text of an artifact |
| `javaducker_get_artifact_status` | Check ingestion lifecycle status |
| `javaducker_wait_for_indexed` | Poll until artifact reaches INDEXED or FAILED |
| `javaducker_stats` | Aggregate stats: total/indexed/pending/failed artifacts |
| `javaducker_summarize` | Structural summary (classes, methods, imports) with staleness check |
| `javaducker_map` | Project overview: directory structure, file counts, largest files |
| `javaducker_watch` | Start/stop auto-indexing a directory on file changes |

#### Code Intelligence

| Tool | Description |
|------|-------------|
| `javaducker_explain` | Everything known about a file in one call: summary, deps, tags, blame, related |
| `javaducker_blame` | Git blame with commit grouping, optional line range |
| `javaducker_related` | Files commonly edited together (git co-change analysis) |
| `javaducker_dependencies` | Import/dependency list for an indexed file |
| `javaducker_dependents` | Files that import/depend on this file |
| `javaducker_stale` | Check which files are stale (modified since last index) |
| `javaducker_index_health` | Index health report with actionable recommendations |

#### Content Intelligence

| Tool | Description |
|------|-------------|
| `javaducker_classify` | Classify artifact by document type |
| `javaducker_tag` | Add/replace tags on an artifact |
| `javaducker_extract_points` | Write salient points (DECISION, RISK, ACTION, etc.) |
| `javaducker_set_freshness` | Mark artifact as current, stale, or superseded |
| `javaducker_synthesize` | Write compact summary and prune full text/embeddings |
| `javaducker_link_concepts` | Create cross-document concept links |
| `javaducker_enrich_queue` | List artifacts queued for enrichment |
| `javaducker_mark_enriched` | Mark artifact as enriched |
| `javaducker_latest` | Get the most recent non-superseded artifact on a topic |
| `javaducker_find_by_type` | Find artifacts by document type |
| `javaducker_find_by_tag` | Find artifacts by tag |
| `javaducker_find_points` | Search salient points by type across all documents |
| `javaducker_concepts` | List all concepts with mention counts |
| `javaducker_concept_timeline` | Evolution of a concept across documents over time |
| `javaducker_stale_content` | List stale/superseded artifacts |
| `javaducker_synthesis` | Retrieve synthesis records for pruned artifacts |
| `javaducker_concept_health` | Health report: active/stale doc counts, trend per concept |

#### Session History

| Tool | Description |
|------|-------------|
| `javaducker_index_sessions` | Index Claude Code session transcripts from a project directory |
| `javaducker_search_sessions` | Search past conversations by phrase |
| `javaducker_session_context` | Combined session + artifact search for full historical context |
| `javaducker_extract_decisions` | Store decisions extracted from a session |
| `javaducker_recent_decisions` | Recent decisions filterable by tag |

#### Reladomo

| Tool | Description |
|------|-------------|
| `javaducker_reladomo_relationships` | Object attributes, relationships, and metadata |
| `javaducker_reladomo_graph` | Traverse relationship graph N hops from root |
| `javaducker_reladomo_path` | Shortest relationship path between two objects |
| `javaducker_reladomo_schema` | SQL DDL derived from object definition |
| `javaducker_reladomo_object_files` | All files for an object grouped by type |
| `javaducker_reladomo_finders` | Finder query patterns ranked by frequency |
| `javaducker_reladomo_deepfetch` | Deep fetch profiles: eagerly loaded relationships |
| `javaducker_reladomo_temporal` | Temporal classification of all objects |
| `javaducker_reladomo_config` | Runtime config: DB connection, cache strategy |

### Typical Claude Code Workflows

```
# Index a codebase
javaducker_index_directory  directory=/repo/src
javaducker_stats

# Understand a file before editing
javaducker_explain  file_path=/repo/src/main/java/com/example/AuthService.java

# Search with staleness awareness
javaducker_search  phrase="how onboarding approvals work"  mode=hybrid
javaducker_index_health   # check if results might be stale

# Find related files to update together
javaducker_related  file_path=/repo/src/main/java/com/example/AuthService.java

# Review git history
javaducker_blame  file_path=/repo/src/main/java/com/example/AuthService.java  start_line=50  end_line=80

# Recall past session decisions
javaducker_search_sessions  phrase="auth middleware"
javaducker_recent_decisions  tag=auth

# Reladomo: understand object relationships
javaducker_reladomo_graph  object_name=Order  depth=2
javaducker_reladomo_schema  object_name=OrderItem
```

## Architecture

```
Claude Code ←─── MCP (stdio) ───→ JavaDuckerMcpServer.java (JBang)
                                          │
                                     HTTP REST
                                          │
                                          ▼
                               ┌──────────────────────┐
                               │  Spring Boot Server   │
                               │                      │
                               │  RestController       │─── 59 endpoints
                               │  Services (14)        │
                               │  Ingestion Pipeline   │
                               │                      │
                               │  ┌────────────────┐  │
                               │  │  DuckDB        │  │─── 22 tables
                               │  │  .duckdb file  │  │
                               │  └────────────────┘  │
                               └──────────────────────┘
```

### Ingestion Pipeline

```
File → TextExtractor → TextNormalizer → Chunker → EmbeddingService → DuckDB
  │         │                                          │
  │    (30+ formats)                              TF-IDF hash
  │                                              vectorization
  └→ FileSummarizer → artifact_summaries
  └→ ImportParser → artifact_imports (dependency graph)
  └→ ReladomoXmlParser → reladomo_objects/attributes/relationships
  └→ ReladomoFinderParser → reladomo_finder_usage/deep_fetch
  └→ ReladomoConfigParser → reladomo_connection_managers/object_config
```

## Data Model

| Table | Purpose |
|-------|---------|
| `artifacts` | One row per uploaded file — status, freshness, enrichment tracking |
| `artifact_text` | Extracted and normalized text per artifact |
| `artifact_chunks` | Ordered text chunks with line number ranges |
| `chunk_embeddings` | TF-IDF hash vectors per chunk |
| `artifact_summaries` | Structural summaries (classes, methods, imports, line count) |
| `artifact_imports` | Parsed imports with resolved artifact references |
| `ingestion_events` | Status transition audit log |
| `artifact_classifications` | Document type classification with confidence |
| `artifact_tags` | Topic/technology tags per artifact |
| `artifact_salient_points` | Decisions, risks, actions, insights per document |
| `artifact_concepts` | Concept mentions per artifact |
| `concept_links` | Cross-document concept relationships |
| `artifact_synthesis` | Compact summaries for pruned stale artifacts |
| `cochange_cache` | Git co-change analysis cache (file pairs + frequency) |
| `session_transcripts` | Indexed Claude Code session messages |
| `session_decisions` | Extracted decisions from past sessions |
| `reladomo_objects` | Parsed Reladomo object definitions |
| `reladomo_attributes` | Object attributes with types and constraints |
| `reladomo_relationships` | Object relationships with cardinality and join expressions |
| `reladomo_indices` | Object index definitions |
| `reladomo_finder_usage` | Finder query patterns with source locations |
| `reladomo_deep_fetch` | Deep fetch profiles |
| `reladomo_connection_managers` | Runtime DB connection manager configs |
| `reladomo_object_config` | Object-to-connection and cache config |

## Search Modes

- **Exact**: Case-insensitive substring match across chunk text
- **Semantic**: Cosine similarity between TF-IDF hash embeddings of query and chunks
- **Hybrid** (default): Weighted merge (0.3 exact + 0.7 semantic), deduplicated by chunk
- **HNSW fast path**: When HNSW index is built, semantic search uses approximate nearest neighbor for O(log n) performance

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `javaducker.db-path` | `data/javaducker.duckdb` | DuckDB database file |
| `javaducker.intake-dir` | `temp/intake` | Upload landing directory |
| `javaducker.chunk-size` | `1000` | Characters per chunk |
| `javaducker.chunk-overlap` | `200` | Overlap between chunks |
| `javaducker.embedding-dim` | `256` | Embedding vector dimension |
| `javaducker.ingestion-poll-seconds` | `5` | Background worker poll interval |
| `javaducker.ingestion-worker-threads` | `4` | Thread pool size for parallel ingestion |
| `javaducker.max-search-results` | `20` | Default max search results |
| `server.port` | `8080` | HTTP server port |

| Environment Variable | Default | Description |
|---------------------|---------|-------------|
| `JAVADUCKER_HOST` | `localhost` | Server hostname |
| `HTTP_PORT` | `8080` | Server port |
| `PROJECT_ROOT` | `.` | Git repo root for blame/related |
| `JAVADUCKER_STALENESS_CHECK` | `true` | Enable/disable staleness warnings in MCP |

## Testing

```bash
mvn test           # run tests
mvn verify         # run tests + generate JaCoCo coverage report
```

**585 tests** with **75.7% instruction coverage** and **66.7% branch coverage**.

| Category | Tests | Scope |
|----------|-------|-------|
| Ingestion | 34 | Full pipeline: upload, extract, chunk, embed, index |
| Text extraction | 96 | All 30+ file formats, error paths, edge cases |
| Search | 29 | Exact, semantic, hybrid, HNSW, merge/rank logic |
| Services | 120+ | All service methods with real DuckDB integration |
| REST | 75 | All endpoints via MockMvc |
| Reladomo | 70+ | XML parsing, query service, config parsing |
| Sessions | 35+ | Transcript parsing, ingestion, search, decisions |
| Parsers | 50+ | Import parser, blame parser, session parser |

## REST API

### Core

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Server health check |
| `GET` | `/api/stats` | Artifact/chunk counts and status breakdown |
| `POST` | `/api/upload` | Upload a file (multipart/form-data) |
| `GET` | `/api/status/{id}` | Artifact ingestion status |
| `GET` | `/api/text/{id}` | Extracted text for an artifact |
| `POST` | `/api/search` | Search chunks (phrase, mode, max_results) |
| `GET` | `/api/summary/{id}` | Structural file summary |
| `GET` | `/api/map` | Project directory overview |

### Code Intelligence

| Method | Path | Description |
|--------|------|-------------|
| `GET/POST` | `/api/blame` | Git blame by artifact ID or file path |
| `GET/POST` | `/api/explain` | Full context bundle for a file |
| `GET/POST` | `/api/related` | Co-change analysis results |
| `POST` | `/api/rebuild-cochange` | Rebuild co-change cache |
| `GET` | `/api/dependencies/{id}` | Import list for an artifact |
| `GET` | `/api/dependents/{id}` | Files importing this artifact |
| `GET` | `/api/stale/summary` | Bulk staleness report |
| `POST` | `/api/stale` | Check staleness for specific files |

### Content Intelligence

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/classify` | Classify document type |
| `POST` | `/api/tag` | Add tags |
| `POST` | `/api/salient-points` | Write salient points |
| `POST` | `/api/concepts` | Save concepts |
| `POST` | `/api/freshness` | Set freshness status |
| `POST` | `/api/synthesize` | Write synthesis + prune |
| `POST` | `/api/link-concepts` | Create concept links |
| `GET` | `/api/enrich-queue` | Artifacts awaiting enrichment |
| `POST` | `/api/mark-enriched` | Mark enriched |
| `GET` | `/api/latest` | Most recent artifact for a topic |
| `GET` | `/api/find-by-type` | Search by document type |
| `GET` | `/api/find-by-tag` | Search by tag |
| `GET` | `/api/find-points` | Search salient points |
| `GET` | `/api/concepts` | List all concepts |
| `GET` | `/api/concept-timeline/{c}` | Concept evolution over time |
| `GET` | `/api/stale-content` | Stale/superseded artifacts |
| `GET` | `/api/synthesis/{id}` | Synthesis record |
| `GET` | `/api/synthesis/search` | Search synthesis by keyword |
| `GET` | `/api/related-by-concept/{id}` | Related by shared concepts |
| `GET` | `/api/concept-health` | Concept trend analysis |

### Watch Mode

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/watch/start` | Start watching a directory |
| `POST` | `/api/watch/stop` | Stop watching |
| `GET` | `/api/watch/status` | Watch status |

### Session History

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/index-sessions` | Index session transcripts |
| `GET` | `/api/sessions` | List indexed sessions |
| `GET` | `/api/session/{id}` | Full session transcript |
| `POST` | `/api/search-sessions` | Search session content |
| `POST` | `/api/extract-session-decisions` | Store extracted decisions |
| `GET` | `/api/session-decisions` | Recent decisions (optional tag filter) |

### Reladomo

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/reladomo/relationships/{obj}` | Object metadata + relationships |
| `GET` | `/api/reladomo/graph/{obj}` | Relationship graph traversal |
| `GET` | `/api/reladomo/path` | Shortest path between objects |
| `GET` | `/api/reladomo/schema/{obj}` | SQL DDL derivation |
| `GET` | `/api/reladomo/files/{obj}` | Files grouped by type |
| `GET` | `/api/reladomo/finders/{obj}` | Finder patterns |
| `GET` | `/api/reladomo/deepfetch/{obj}` | Deep fetch profiles |
| `GET` | `/api/reladomo/temporal` | Temporal object classification |
| `GET` | `/api/reladomo/config` | Runtime configuration |

## Artifact Lifecycle

```
RECEIVED → STORED_IN_INTAKE → PARSING → CHUNKED → EMBEDDED → INDEXED
                                   └──────────────────────────→ FAILED
```

Post-indexing enrichment: `INDEXED` → classify → tag → extract points → `ENRICHED`

Freshness: `current` → `stale` → `superseded` (with synthesis + text pruning)
