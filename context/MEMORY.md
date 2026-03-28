# Session Memory

## Current Focus
All 8 Claude Companion features implemented and tests passing (65/65).

## Recent Decisions
- DuckDB UPDATE with PK can fail (ART index bug) — use DELETE+INSERT pattern for artifact reindex
- ALTER TABLE in SchemaBootstrap needs separate Statement objects (error closes shared stmt in DuckDB)
- chunk_embeddings cleanup requires subquery (keyed by chunk_id, not artifact_id)

## Key Findings
- All 8 features from plans/claude-companion-features.md implemented in one session
- 7 new Java classes created, 10+ existing classes modified
- 6 new MCP tools added (summarize, map, stale, dependencies, dependents, watch)
- 2 existing MCP tools enhanced (search with line numbers, index with incremental re-indexing)

## Open Questions
- HNSW index is not auto-built on startup — needs explicit buildHnswIndex() call
- Watch mode FileWatcher uses polling WatchService which may miss rapid changes on some OS

## Session Log
- 2026-03-28: Implemented all 8 features from claude-companion-features.md plan
  - Phase 1 (parallel): F1 Line Numbers, F3 File Summaries, F4 Project Map, F6 Diff-Aware Search
  - Phase 2 (parallel): F2 Incremental Re-indexing, F5 Dependency Graph
  - Phase 3+4 (parallel): F7 Watch Mode, F8 HNSW Index
  - Fixed test compilation (3 test files), DuckDB PK constraint bug, Statement closure bug
  - All 65 tests green
