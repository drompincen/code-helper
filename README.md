# JavaDucker v2

[![CI](https://github.com/drompincen/code-helper/actions/workflows/ci.yml/badge.svg)](https://github.com/drompincen/code-helper/actions/workflows/ci.yml)
![Coverage](.github/badges/jacoco.svg)
![Branches](.github/badges/branches.svg)

Code and content intelligence server for Claude Code. Indexes source files, documents, plans, notes, and threads into DuckDB with semantic search, dependency graphs, and content classification — all accessible via MCP tools and REST API.

## Tech Stack

- **Java 21** + **Spring Boot 3.2**
- **REST API** (Spring MVC)
- **DuckDB** file-based persistence (JDBC)
- **picocli** CLI client
- **Apache PDFBox** for PDF extraction
- **TF-IDF hash vectorization** for self-contained semantic search

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

### Use the CLI Client

```bash
# Check health
./run-client.sh --host localhost --port 8080 health

# Upload one file
./run-client.sh --host localhost --port 8080 upload-file --file ./docs/architecture.md

# Upload a directory
./run-client.sh --host localhost --port 8080 upload-dir --root ./repo --ext .java,.xml,.md,.yml,.pdf

# Check ingestion status
./run-client.sh --host localhost --port 8080 status --id <artifact-id>

# Exact search
./run-client.sh --host localhost --port 8080 find --phrase "@Transactional" --mode exact

# Semantic search
./run-client.sh --host localhost --port 8080 find --phrase "how onboarding approvals are coordinated" --mode semantic

# Hybrid search (default)
./run-client.sh --host localhost --port 8080 find --phrase "material change monitoring" --mode hybrid

# Retrieve extracted text
./run-client.sh --host localhost --port 8080 cat --id <artifact-id>

# View stats
./run-client.sh --host localhost --port 8080 stats
```

## MCP Server (Claude Code Integration)

JavaDucker ships a JBang-based MCP server (`JavaDuckerMcpServer.java`) that exposes the full indexing and search API as tools for Claude Code.

### Setup

1. Start the JavaDucker server:
   ```cmd
   run-server.cmd
   ```

2. Register the MCP server in your Claude Code config (`.claude/settings.json` or `claude_desktop_config.json`):
   ```json
   {
     "mcpServers": {
       "javaducker": {
         "command": "C:\\path\\to\\code-helper\\run-mcp.cmd"
       }
     }
   }
   ```

3. Optionally set environment variables to override defaults:
   ```
   JAVADUCKER_HOST=localhost    (default: localhost)
   HTTP_PORT=8080               (default: 8080)
   PROJECT_ROOT=.               (default: .)
   ```

### MCP Tools

| Tool | Description |
|------|-------------|
| `javaducker_health` | Check server status and version |
| `javaducker_index_file` | Upload and index a single file by absolute path; returns `artifact_id` |
| `javaducker_index_directory` | Recursively index all source files under a directory; `extensions` optional (default: `.java,.xml,.md,.yml,.json,.txt,.pdf`) |
| `javaducker_search` | Search indexed content — `phrase` required, `mode` = `exact`/`semantic`/`hybrid` (default), `max_results` optional |
| `javaducker_get_file_text` | Retrieve full extracted text of an artifact by `artifact_id` |
| `javaducker_get_artifact_status` | Check ingestion lifecycle status of an artifact |
| `javaducker_wait_for_indexed` | Poll until an artifact is `INDEXED` or `FAILED`; `timeout_seconds` optional (default 120) |
| `javaducker_stats` | Aggregate stats: total/indexed/pending/failed artifacts, chunks, bytes |

### Typical Claude Code workflow

```
# Index a codebase
javaducker_index_directory  directory=/repo/src
javaducker_stats            # monitor progress until indexed == total

# Search
javaducker_search  phrase="how onboarding approvals work"  mode=hybrid
javaducker_search  phrase="@Transactional"                 mode=exact

# Read a full file found in search results
javaducker_get_file_text  artifact_id=<id from search>
```

### Windows: Build and Run

The `.cmd` scripts pin `JAVA_HOME` to the bundled x86_64 JDK (runs in emulation on ARM64 Windows):

```cmd
run-server.cmd    # builds if needed, starts HTTP server on port 8080
run-mcp.cmd       # starts the MCP stdio server via JBang
```

## Architecture

```
Client (CLI)                    Server (Spring Boot)
┌──────────────┐               ┌──────────────────────────────────┐
│  picocli     │    REST       │  REST Controller                 │
│  commands    │───────────────│  UploadService                   │
│              │               │  ArtifactService                 │
│  health      │               │  SearchService                   │
│  upload-file │               │  StatsService                    │
│  upload-dir  │               │                                  │
│  find        │               │  ┌────────────────────────────┐  │
│  cat         │               │  │  Ingestion Worker          │  │
│  status      │               │  │  TextExtractor             │  │
│  stats       │               │  │  TextNormalizer            │  │
└──────────────┘               │  │  Chunker                   │  │
                               │  │  EmbeddingService          │  │
                               │  └────────────────────────────┘  │
                               │                                  │
                               │  ┌────────────┐  ┌───────────┐  │
                               │  │ DuckDB     │  │ temp/     │  │
                               │  │ .duckdb    │  │ intake/   │  │
                               │  └────────────┘  └───────────┘  │
                               └──────────────────────────────────┘
```

## Data Model

| Table | Purpose |
|-------|---------|
| `artifacts` | One row per uploaded file — status lifecycle tracking |
| `artifact_text` | Extracted and normalized text per artifact |
| `artifact_chunks` | Ordered text chunks for search |
| `chunk_embeddings` | TF-IDF hash vectors per chunk |
| `ingestion_events` | Status transition audit log |

## Artifact Lifecycle

```
RECEIVED → STORED_IN_INTAKE → PARSING → CHUNKED → EMBEDDED → INDEXED
                                   └──────────────────────────→ FAILED
```

## Search Modes

- **Exact**: Case-insensitive substring match across chunk text
- **Semantic**: Cosine similarity between TF-IDF hash embeddings of query and chunks
- **Hybrid**: Weighted merge (0.3 exact + 0.7 semantic), deduplicated by chunk

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

## REST API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/health` | Server health check |
| `POST` | `/api/upload` | Upload a single file (multipart/form-data) |
| `GET` | `/api/status/{id}` | Query artifact ingestion status |
| `GET` | `/api/text/{id}` | Retrieve extracted text for an artifact |
| `POST` | `/api/search` | Search chunks — JSON body: `{"phrase":"...", "mode":"hybrid", "max_results":20}` |
| `GET` | `/api/stats` | Artifact/chunk counts and status breakdown |

## Testing

```bash
mvn test
```

### Test Coverage
- **Unit**: TextExtractor, Chunker, EmbeddingService, TextNormalizer, SearchService scoring
- **Schema**: Table creation, idempotency, dedup index
- **Integration**: Full upload → ingest → exact/semantic/hybrid search flow, dedup, error handling
- **Parallel**: Concurrent ingestion via thread pool
- **REST**: Controller smoke tests (MockMvc)

## Project Structure

```
src/
├── main/
│   ├── java/com/javaducker/
│   │   ├── server/
│   │   │   ├── JavaDuckerServerApp.java
│   │   │   ├── config/AppConfig.java
│   │   │   ├── db/DuckDBDataSource.java
│   │   │   ├── db/SchemaBootstrap.java
│   │   │   ├── rest/JavaDuckerRestController.java
│   │   │   ├── service/UploadService.java
│   │   │   ├── service/ArtifactService.java
│   │   │   ├── service/SearchService.java
│   │   │   ├── service/StatsService.java
│   │   │   ├── ingestion/IngestionWorker.java
│   │   │   ├── ingestion/TextExtractor.java
│   │   │   ├── ingestion/TextNormalizer.java
│   │   │   ├── ingestion/Chunker.java
│   │   │   ├── ingestion/EmbeddingService.java
│   │   │   └── model/ArtifactStatus.java
│   │   └── client/JavaDuckerClient.java
│   └── resources/application.yml
├── test/
│   └── java/com/javaducker/
│       ├── server/ingestion/*Test.java
│       ├── server/service/*Test.java
│       ├── server/db/*Test.java
│       ├── server/rest/JavaDuckerRestControllerTest.java
│       └── integration/FullFlowIntegrationTest.java
test-corpus/                             # Sample files for manual testing
docs/wizard/                             # Interactive HTML presentation
```

## Interactive Documentation

Open `docs/wizard/index.html` in a browser for an interactive walkthrough of the system architecture, data flows, and search algorithms.
