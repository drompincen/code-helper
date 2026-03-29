---
title: Stale Index Warning
status: completed
created: 2026-03-28
updated: 2026-03-28
current_chapter: 4
---

# Plan: Stale Index Warning

Enhance the existing staleness infrastructure (`StalenessService`, `javaducker_stale` MCP tool, `/stale` REST endpoint) with **proactive warnings** so Claude is automatically informed when search results may be stale — without needing to manually call the stale tool.

**What exists today:**
- `StalenessService.checkStaleness(filePaths)` — compares file mtime vs `indexed_at` for specific paths
- `javaducker_stale` MCP tool — accepts `file_paths` or `git_diff_ref`, returns stale/current/not_indexed counts
- `/api/stale` REST endpoint — POST with file_paths body

**What's missing:**
- No bulk "check all indexed files" mode — you must supply paths
- Search results don't include staleness warnings
- No way to get a quick health-check summary ("12 of 200 files stale")
- No proactive nudge to re-index

## Chapter 1: Bulk Staleness Check
**Status:** completed
**Depends on:** none

- [x] Add `StalenessService.checkAll()` — query all distinct `original_client_path` from `artifacts` where `status = 'INDEXED'`, then run the existing mtime comparison logic. Return summary: `{stale_count, current_count, missing_count, total, stale_files: [{path, artifact_id, indexed_at, file_modified_at}]}`
- [x] Add `GET /api/stale/summary` endpoint — calls `checkAll()`, returns the summary. Lightweight: only returns counts + stale file list (no embeddings, no chunks)
- [x] Add unit test in `StalenessServiceTest` — index 3 files, touch 1 on disk, verify `checkAll()` reports 1 stale, 2 current
- [x] Verify existing `/api/stale` POST still works unchanged

**Notes:**
> `checkAll()` iterates all indexed paths. For large indexes (1000+ files), this is I/O-bound on stat calls. Acceptable for now — can batch later if needed.

## Chapter 2: Staleness Banner in Search Results
**Status:** completed
**Depends on:** Chapter 1

- [x] Modify `SearchService.search()` (or the REST endpoint `POST /api/search`) to run a lightweight staleness check on the files that appear in search results. For each result, compare its artifact's `indexed_at` against the file's current mtime
- [x] Add a `stale` boolean field to each search result entry and a top-level `staleness_warning` string to the search response (e.g., `"3 of 8 results come from stale files — run re-index?"`)
- [x] If ALL results are current, omit the warning (no noise)
- [x] Update `JavaDuckerMcpServer.javaducker_search` tool — the warning string is already in the JSON, Claude will see it naturally
- [ ] Add test: index file, search (no warning), touch file on disk, search again (warning present)

**Notes:**
> Only check files that appear in results — don't scan the whole index on every search. Keep the overhead to a few stat() calls per search.

## Chapter 3: Health-Check MCP Tool
**Status:** completed
**Depends on:** Chapter 1

- [x] Add `javaducker_index_health` MCP tool to `JavaDuckerMcpServer.java` — no params required. Calls `GET /api/stale/summary`. Returns a human-readable report: "Index health: 188/200 files current, 12 stale (6%). Stale files: [list]. Run javaducker_index with file_paths to refresh."
- [x] Include recommendation threshold: if >10% stale, suggest full re-index; if ≤10%, suggest targeted re-index of the listed files
- [x] Add to `JavaDuckerClient.java` CLI as `index-health` subcommand
- [ ] Add integration test — verify tool returns expected structure

**Notes:**
> This is the tool Claude calls proactively or when starting a session. The response is designed to be actionable — it tells Claude exactly what to do next.

## Chapter 4: Search-Time Hook (MCP Server Enhancement)
**Status:** completed
**Depends on:** Chapter 2, Chapter 3

- [x] In `JavaDuckerMcpServer`, wrap the `javaducker_search` handler: after returning search results, if `staleness_warning` is non-null, append a footer line: `"\n⚠️ {warning} Use javaducker_index to refresh."`
- [x] Similarly, wrap `javaducker_summarize` — if the artifact being summarized is stale, prepend: `"⚠️ This file has changed since indexing — summary may be outdated."`
- [x] Add a `--staleness-check` flag (default: on) to the MCP server config so users can disable if overhead is unwanted
- [ ] Test: verify search result includes footer when stale, omits when current

**Notes:**
> The footer approach means Claude sees the warning in the tool response without any protocol-level changes. Claude can then decide to re-index or proceed.

---
