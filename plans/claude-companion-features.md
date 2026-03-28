# JavaDucker: Claude Companion Features — Multi-Agent Implementation Plan

## Goal
Make JavaDucker a better companion for Claude Code when working with large codebases. All features must be **pure Java**, no new non-Java dependencies, everything local.

## Constraint
- Java only — no Python, no Node.js, no external embedding APIs
- No new Maven dependencies — use what's already in pom.xml (Spring Boot, DuckDB, POI, etc.)
- All processing local — no network calls to AI services
- Existing interfaces must not break

---

## Features (priority order)

### Feature 1: Line Numbers in Search Results
### Feature 2: Incremental Re-indexing (replace stale artifacts)
### Feature 3: File Summaries (auto-generated digest per file)
### Feature 4: Project Map (high-level codebase overview)
### Feature 5: Dependency/Import Graph
### Feature 6: Diff-Aware Search (git-changed file detection)
### Feature 7: Watch Mode (auto-index on file change)
### Feature 8: ANN Indexing (HNSW for scale)

---

## Feature 1: Line Numbers in Search Results

**Why**: Claude needs `file:line` to do `Read(file, offset=N)`. Without line numbers, Claude has to search again after finding a chunk.

**Changes**:

| File | Change |
|------|--------|
| `Chunker.java` | Track line_start and line_end per chunk (count `\n` in text before charStart/charEnd) |
| `SchemaBootstrap.java` | Add `line_start INTEGER, line_end INTEGER` columns to `artifact_chunks` |
| `IngestionWorker.java` | Pass line numbers when inserting chunks |
| `SearchService.java` | Include `line_start`, `line_end` in result maps |
| `JavaDuckerMcpServer.java` | Include line numbers in search result formatting |

**Parallel group**: All file changes are independent — one agent per file.

**Agent plan**:
```
Agent 1: Chunker.java — add line counting to chunk() method
Agent 2: SchemaBootstrap.java — add columns (with ALTER TABLE migration for existing DBs)
Agent 3: SearchService.java — add line_start/line_end to all search result maps
Agent 4: IngestionWorker.java — pass line numbers during chunk insert
Agent 5: JavaDuckerMcpServer.java — format line numbers in search tool output
```

**Verification**: Index a file, search for a known function, confirm line numbers match.

---

## Feature 2: Incremental Re-indexing

**Why**: Currently re-indexing a changed file creates a duplicate. Need to detect "same file, new content" and replace.

**Changes**:

| File | Change |
|------|--------|
| `UploadService.java` | On duplicate path detection: delete old chunks/embeddings/text, reset status to RECEIVED, update sha256 and size |
| `SchemaBootstrap.java` | Add `original_client_path` index if not exists (already has one — verify) |
| `ArtifactService.java` | Add `deleteArtifactData(artifactId)` — deletes from artifact_chunks, chunk_embeddings, artifact_text |

**Sequential**: UploadService depends on ArtifactService having the delete method.

**Agent plan**:
```
Group 1 (parallel):
  Agent A: ArtifactService.java — add deleteArtifactData() method
  Agent B: SchemaBootstrap.java — verify indexes exist

Group 2 (after Group 1):
  Agent C: UploadService.java — modify upload() to replace existing artifact on same path
```

**Verification**: Index a file, modify it, re-index same path, confirm old chunks gone and new chunks present.

---

## Feature 3: File Summaries

**Why**: Claude can understand the codebase at a glance without reading every file. One-call overview per file.

**Approach**: Generate a summary during ingestion using the extracted text — no LLM needed. Extract:
- File type and language
- Class/interface/function names (regex-based extraction for Java, JS, Python, etc.)
- Import statements (first 10)
- Line count
- Top terms (from TF-IDF embedding — highest-weight tokens)

**Changes**:

| File | Change |
|------|--------|
| `FileSummarizer.java` (NEW) | Extract structural summary from text: class names, method names, imports, line count, top terms |
| `SchemaBootstrap.java` | Add `artifact_summaries` table: artifact_id, summary_text, class_names, method_names, import_count, line_count |
| `IngestionWorker.java` | After CHUNKED stage, generate summary and insert |
| `ArtifactService.java` | Add `getSummary(artifactId)` method |
| `JavaDuckerRestController.java` | Add `GET /api/summary/{artifactId}` endpoint |
| `JavaDuckerMcpServer.java` | Add `javaducker_summarize` tool |

**Agent plan**:
```
Group 1 (parallel):
  Agent A: FileSummarizer.java — new class, regex-based extraction for Java/JS/Python/Go/Rust
  Agent B: SchemaBootstrap.java — add artifact_summaries table
  Agent C: JavaDuckerMcpServer.java — add javaducker_summarize tool (calls /api/summary/{id})

Group 2 (after Group 1):
  Agent D: IngestionWorker.java — call FileSummarizer after chunking
  Agent E: ArtifactService.java + REST controller — add getSummary() and endpoint
```

**Verification**: Index a Java file, call summarize, confirm class names and method names appear.

---

## Feature 4: Project Map

**Why**: Claude gets a mental map of the codebase in one call — directory structure, file counts, most-connected files, recently changed files.

**Approach**: Query DuckDB for all indexed artifacts, group by directory path, count files per directory, identify largest files, most recently indexed.

**Changes**:

| File | Change |
|------|--------|
| `ProjectMapService.java` (NEW) | Query artifacts grouped by directory prefix, return tree structure with counts |
| `JavaDuckerRestController.java` | Add `GET /api/map` endpoint |
| `JavaDuckerMcpServer.java` | Add `javaducker_map` tool |

**Agent plan**:
```
All parallel (independent):
  Agent A: ProjectMapService.java — new service, DuckDB queries
  Agent B: REST controller — add /api/map endpoint
  Agent C: MCP server — add javaducker_map tool
```

**Verification**: Index a directory, call map, confirm directory tree with file counts.

---

## Feature 5: Dependency/Import Graph

**Why**: Claude can trace call chains and understand what depends on what — the highest-value feature.

**Approach**: Parse import/require/include statements from source code during ingestion. Store as edges in a graph table. Query for callers/callees.

**Changes**:

| File | Change |
|------|--------|
| `ImportParser.java` (NEW) | Regex-based import extraction for Java (`import`), JS/TS (`import`/`require`), Python (`import`/`from`), Go (`import`), Rust (`use`) |
| `SchemaBootstrap.java` | Add `artifact_imports` table: artifact_id, import_statement, resolved_artifact_id (nullable) |
| `IngestionWorker.java` | After PARSING, extract imports and insert |
| `DependencyService.java` (NEW) | Query import graph: `getDependencies(artifactId)`, `getDependents(artifactId)`, `getImportChain(from, to)` |
| `JavaDuckerRestController.java` | Add `GET /api/dependencies/{artifactId}` and `GET /api/dependents/{artifactId}` |
| `JavaDuckerMcpServer.java` | Add `javaducker_dependencies` and `javaducker_dependents` tools |

**Agent plan**:
```
Group 1 (parallel):
  Agent A: ImportParser.java — new class, regex patterns per language
  Agent B: SchemaBootstrap.java — add artifact_imports table
  Agent C: DependencyService.java — new service, graph queries

Group 2 (after Group 1):
  Agent D: IngestionWorker.java — call ImportParser after text extraction
  Agent E: REST controller + MCP server — add dependency endpoints and tools
```

**Import resolution**: Match import paths to indexed artifacts by `original_client_path`. E.g., `import com.javaducker.server.service.SearchService` → find artifact where path ends with `com/javaducker/server/service/SearchService.java`. Store `resolved_artifact_id` when found, NULL when external.

**Verification**: Index the code-helper project itself, query dependencies of SearchService, confirm it lists EmbeddingService and DuckDBDataSource.

---

## Feature 6: Diff-Aware Search

**Why**: After code changes, Claude can ask "what indexed content is stale?"

**Approach**: Run `git diff --name-only HEAD~N` or accept a file list, cross-reference against indexed artifacts by `original_client_path`.

**Changes**:

| File | Change |
|------|--------|
| `StalenessService.java` (NEW) | Accept file paths, query artifacts table, return which indexed artifacts are stale (file modified after `indexed_at`) |
| `JavaDuckerRestController.java` | Add `POST /api/stale` endpoint (accepts list of file paths) |
| `JavaDuckerMcpServer.java` | Add `javaducker_stale` tool (accepts `git_diff_ref` or `file_paths`) |

**No git dependency in Java** — the MCP server runs `git diff --name-only` via ProcessBuilder and passes the file list to the REST endpoint.

**Agent plan**:
```
All parallel:
  Agent A: StalenessService.java — query by paths, compare timestamps
  Agent B: REST controller — add /api/stale endpoint
  Agent C: MCP server — add javaducker_stale tool with git diff integration
```

**Verification**: Index project, modify a file (don't re-index), call stale, confirm that file appears.

---

## Feature 7: Watch Mode

**Why**: Auto-index changed files so the search index stays current without manual re-indexing.

**Approach**: Use Java's `WatchService` (java.nio.file) to monitor directories. On file change, trigger re-index via UploadService. Requires Feature 2 (incremental re-indexing).

**Changes**:

| File | Change |
|------|--------|
| `FileWatcher.java` (NEW) | `WatchService`-based directory monitor, filters by extension, debounces (500ms), calls UploadService on change |
| `AppConfig.java` | Add `watchDirs` (list of paths), `watchEnabled` (boolean), `watchExtensions` (string) |
| `JavaDuckerServerApp.java` | Start FileWatcher as Spring bean if enabled |
| `JavaDuckerMcpServer.java` | Add `javaducker_watch` tool to start/stop watching a directory |
| `JavaDuckerRestController.java` | Add `POST /api/watch/start` and `POST /api/watch/stop` |

**Depends on**: Feature 2 (incremental re-indexing must work first).

**Agent plan**:
```
Group 1 (parallel):
  Agent A: FileWatcher.java — WatchService implementation with debounce
  Agent B: AppConfig.java — add watch config properties

Group 2 (after Group 1):
  Agent C: ServerApp + REST + MCP — wire up start/stop watch endpoints
```

**Verification**: Start watch on a directory, modify a file, wait 5s, confirm it's automatically re-indexed.

---

## Feature 8: HNSW Indexing (ANN)

**Why**: Brute-force cosine works for ~10k chunks. HNSW gives O(log n) search for 100k+ chunks.

**Approach**: Implement HNSW in pure Java. This is the most complex feature. The algorithm is well-documented and doesn't require external libraries.

**Changes**:

| File | Change |
|------|--------|
| `HnswIndex.java` (NEW) | Pure Java HNSW implementation: insert, search, serialize/deserialize. Parameters: M=16, efConstruction=200, efSearch=50 |
| `EmbeddingService.java` | Add methods to build and query HNSW index |
| `SearchService.java` | Replace brute-force loop with HNSW query when index is available, fallback to brute-force if not |
| `IngestionWorker.java` | After EMBEDDED, insert into HNSW index |
| `SchemaBootstrap.java` | Add `hnsw_state` table for serialized index persistence |

**Agent plan**: This is a single-agent task — HNSW is tightly coupled and shouldn't be split.

```
Agent 1: HnswIndex.java — full implementation (insert, knn-search, serialization)
Agent 2 (after Agent 1): Wire into SearchService + IngestionWorker
```

**Verification**: Index 1000+ chunks, compare search results and latency between brute-force and HNSW.

---

## Execution Order (respecting dependencies)

```
Phase 1 — Independent features (ALL PARALLEL):
  ├── Feature 1: Line Numbers (5 agents)
  ├── Feature 3: File Summaries (5 agents)
  ├── Feature 4: Project Map (3 agents)
  └── Feature 6: Diff-Aware Search (3 agents)

Phase 2 — Depends on nothing but benefits from Phase 1:
  ├── Feature 2: Incremental Re-indexing (3 agents, 2 groups)
  └── Feature 5: Dependency Graph (5 agents, 2 groups)

Phase 3 — Depends on Feature 2:
  └── Feature 7: Watch Mode (3 agents, 2 groups)

Phase 4 — Independent but complex, do last:
  └── Feature 8: HNSW Index (2 agents, sequential)
```

## New MCP Tools Summary

| Tool | Feature | Purpose |
|------|---------|---------|
| `javaducker_summarize` | 3 | One-paragraph file digest with class/method names |
| `javaducker_map` | 4 | Project directory tree with file counts |
| `javaducker_dependencies` | 5 | What does this file import? |
| `javaducker_dependents` | 5 | What files import this one? |
| `javaducker_stale` | 6 | Which indexed files have changed since last index? |
| `javaducker_watch` | 7 | Start/stop auto-indexing a directory |

Existing tools enhanced:
| Tool | Feature | Enhancement |
|------|---------|-------------|
| `javaducker_search` | 1 | Results include `line_start`, `line_end` |
| `javaducker_index_file` | 2 | Replaces stale artifact instead of duplicating |
| `javaducker_index_directory` | 2 | Replaces stale artifacts instead of duplicating |
| `javaducker_search` | 8 | Uses HNSW for faster semantic search when available |

## New Java Classes

| Class | Feature | Lines (est.) |
|-------|---------|-------------|
| `FileSummarizer.java` | 3 | ~150 |
| `ProjectMapService.java` | 4 | ~80 |
| `ImportParser.java` | 5 | ~120 |
| `DependencyService.java` | 5 | ~100 |
| `StalenessService.java` | 6 | ~60 |
| `FileWatcher.java` | 7 | ~120 |
| `HnswIndex.java` | 8 | ~300 |

## Modified Java Classes

| Class | Features | Changes |
|-------|----------|---------|
| `Chunker.java` | 1 | Add line counting |
| `SchemaBootstrap.java` | 1, 2, 3, 5, 8 | New tables and columns |
| `IngestionWorker.java` | 1, 3, 5, 8 | Call summarizer, import parser, HNSW insert |
| `SearchService.java` | 1, 8 | Line numbers in results, HNSW search path |
| `ArtifactService.java` | 2, 3 | deleteArtifactData(), getSummary() |
| `UploadService.java` | 2 | Replace stale artifacts |
| `AppConfig.java` | 7 | Watch config properties |
| `JavaDuckerRestController.java` | 3, 4, 5, 6, 7 | New endpoints |
| `JavaDuckerMcpServer.java` | 1, 3, 4, 5, 6, 7 | New tools, enhanced search output |
