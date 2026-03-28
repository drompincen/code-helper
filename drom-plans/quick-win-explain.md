---
title: Quick Win — javaducker_explain
status: in-progress
created: 2026-03-28
updated: 2026-03-28
current_chapter: 1
---

# Plan: Quick Win — javaducker_explain

Add a `javaducker_explain` MCP tool that returns everything JavaDucker knows about a file in one call: summary, dependency chain, dependents, tags, classification, related plans/ADRs, co-change partners, and blame highlights. A single-call context loader for Claude.

## Chapter 1: Explain Service
**Status:** in-progress
**Depends on:** none

- [ ] Create `ExplainService.java` in `server/service/` (~200 lines) — aggregates data from existing services. Constructor-injected: `ArtifactService`, `DependencyService`, `SearchService`, `ContentIntelligenceService`. Optional: `GitBlameService`, `CoChangeService` (may not exist yet — use try-catch or Optional injection)
- [ ] Add method `explain(artifactId)` — returns a composite map with sections: `file` (name, path, size, status, indexed_at), `summary` (from artifact summary), `dependencies` (imports this file uses), `dependents` (files that import this one), `classification` (doc_type, tags, freshness), `salient_points` (decisions, risks, actions from content intelligence), `related_artifacts` (by concept links), `blame_highlights` (top 3 most recent committers + their commit messages, if GitBlameService available), `co_changes` (top 5 files commonly edited together, if CoChangeService available)
- [ ] Handle missing data gracefully — each section is optional. If a service throws or returns null, that section is omitted, not the whole response
- [ ] Write `ExplainServiceTest` — test with full data, test with partial data (no blame, no co-change), test with unknown artifactId

**Notes:**
> This is a read-only aggregation service. It calls existing services — no new tables, no new data. Its value is combining 6-8 separate API calls into one.

## Chapter 2: REST Endpoint & MCP Tool
**Status:** pending
**Depends on:** Chapter 1

- [ ] Add `GET /api/explain/{artifactId}` endpoint — returns the full explain bundle
- [ ] Add `POST /api/explain` endpoint — body: `{filePath}` — resolve to artifact_id first, then explain. If not indexed, return a minimal response with just the file path and a note that it's not indexed
- [ ] Add `javaducker_explain` MCP tool — params: `file_path` (required). Description: "Get everything JavaDucker knows about a file: summary, dependencies, dependents, tags, classification, related plans, blame highlights, and co-change partners. One call for full context."
- [ ] Keep response compact — summaries not full text, top-N for lists (5 deps, 5 dependents, 5 co-changes, 3 blame entries). Include counts so Claude knows there's more if needed
- [ ] Write integration test — explain a real indexed file, verify all sections present

**Notes:**
> This will likely become the most-called MCP tool. Claude should use it before editing any file to understand full context. Keep the response under 2K tokens.

---

## Risks
- Response could be large if all sections are populated — enforce limits per section
- Depends on other quick wins (blame, related) for full richness, but works without them

## Open Questions
- Should explain also return recent search queries that matched this file? (might be noise)
