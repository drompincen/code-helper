---
title: Quick Win — javaducker_blame
status: in-progress
created: 2026-03-28
updated: 2026-03-28
current_chapter: 1
---

# Plan: Quick Win — javaducker_blame

Add a `javaducker_blame` MCP tool that wraps `git blame` with indexed context — given a file or artifact, return who last touched each section, when, and the commit message. Enriched with JavaDucker metadata (summary, tags, dependencies).

## Chapter 1: Git Blame Service
**Status:** in-progress
**Depends on:** none

- [ ] Create `GitBlameService.java` in `server/service/` (~150 lines) — run `git blame --porcelain <file>` via `ProcessBuilder`, parse output into structured records: `BlameEntry(lineStart, lineEnd, commitHash, author, authorDate, commitMessage, content)`
- [ ] Handle edge cases: file not in git, binary files, files outside PROJECT_ROOT, git not installed
- [ ] Add method `blameForArtifact(artifactId)` — look up `original_client_path` from `artifacts` table, run blame on that path
- [ ] Add method `blameForLines(filePath, startLine, endLine)` — blame a specific range (useful when Claude is looking at a search result with line numbers)
- [ ] Write `GitBlameServiceTest` — test porcelain parsing, file-not-found, range queries

**Notes:**
> Use `--porcelain` format for machine-readable output. Cache blame results in memory (LRU, 50 files) since blame is expensive. PROJECT_ROOT env var gives the repo root.

## Chapter 2: REST Endpoint & MCP Tool
**Status:** pending
**Depends on:** Chapter 1

- [ ] Add `GET /api/blame/{artifactId}` endpoint — returns blame entries for the full file, enriched with artifact summary if available
- [ ] Add `POST /api/blame` endpoint — body: `{filePath, startLine?, endLine?}` — blame by path with optional range
- [ ] Add `javaducker_blame` MCP tool to `JavaDuckerMcpServer.java` — params: `file_path` (required), `start_line` (optional), `end_line` (optional). Description: "Show who last changed each line of a file, with commit info. Optionally narrow to a line range."
- [ ] Enrich blame response: for each unique commit, include the commit message. For the file, include artifact summary and dependency count if indexed
- [ ] Write integration test — blame a real file in the repo, verify structure

**Notes:**
> Keep the MCP tool response concise — group consecutive lines by the same commit into ranges, don't return per-line entries for 500-line files. Example: "lines 1-45: alice, 2026-03-20, 'Add auth middleware'"

---

## Risks
- git must be available on PATH — fail gracefully with clear error if not
- Large files produce verbose blame — cap at 500 lines or summarize by commit

## Open Questions
- None — straightforward feature
