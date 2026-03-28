---
title: Quick Win — javaducker_related
status: in-progress
created: 2026-03-28
updated: 2026-03-28
current_chapter: 1
---

# Plan: Quick Win — javaducker_related

Add a `javaducker_related` MCP tool that finds files commonly edited together by analyzing git log co-change history. When Claude is editing a file, it can ask "what other files usually change with this one?" to avoid missing related updates.

## Chapter 1: Co-Change Analysis Service
**Status:** in-progress
**Depends on:** none

- [ ] Create `CoChangeService.java` in `server/service/` (~180 lines) — run `git log --name-only --pretty=format:"COMMIT:%H" --since="6 months ago"` via `ProcessBuilder`, parse into commit→files map. For a given file, find all commits that touched it, then count co-occurrences of other files across those commits. Rank by frequency
- [ ] Add `cochange_cache` table to `SchemaBootstrap` — `file_a VARCHAR, file_b VARCHAR, co_change_count INTEGER, last_commit_date TIMESTAMP, PRIMARY KEY (file_a, file_b)` — precomputed cache, rebuilt on demand
- [ ] Add method `buildCoChangeIndex()` — parse full git log, populate cache table. Idempotent (DELETE + INSERT)
- [ ] Add method `getRelatedFiles(filePath, maxResults)` — query cache, return ranked list with co-change count and last shared commit date
- [ ] Filter out noise: ignore files that appear in >50% of all commits (build scripts, lockfiles), ignore commits with >30 files (bulk renames/reformats)
- [ ] Write `CoChangeServiceTest` — test parsing, ranking, noise filtering, empty repo

**Notes:**
> The git log parse is expensive (~2-5s for large repos) so we cache in DuckDB. Rebuild on demand via endpoint or when staleness is detected. The 6-month window keeps results relevant.

## Chapter 2: REST Endpoint & MCP Tool
**Status:** pending
**Depends on:** Chapter 1

- [ ] Add `GET /api/related/{artifactId}` endpoint — look up original_client_path, return co-change partners ranked by frequency
- [ ] Add `POST /api/related` endpoint — body: `{filePath, maxResults?, rebuild?}`. If `rebuild: true`, refresh the co-change cache first
- [ ] Add `javaducker_related` MCP tool — params: `file_path` (required), `max_results` (optional, default 10). Description: "Find files that are commonly edited together with this file, based on git history. Helps identify related files you might need to update."
- [ ] Enrich response: for each related file, include co-change count, last shared commit, and whether it's currently indexed in JavaDucker (with summary if so)
- [ ] Add `POST /api/rebuild-cochange` endpoint — force rebuild the cache
- [ ] Write integration test — build index from real repo, query related files

**Notes:**
> This is one of the most useful tools for Claude — when making a change, knowing what usually changes together prevents incomplete PRs.

---

## Risks
- Git log parsing on very large repos (10K+ commits) could be slow — the 6-month window mitigates this
- Projects without git history return empty results — handle gracefully

## Open Questions
- Should the co-change cache auto-rebuild on a timer, or only on demand?
