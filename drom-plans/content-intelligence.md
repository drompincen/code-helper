---
title: Content Intelligence
status: completed
created: 2026-03-28
updated: 2026-03-28
current_chapter: 9
---

# Plan: Content Intelligence

Implement the objectives from `objectives/content-intelligence.md`: classify, tag, rank, prune, and synthesize both code and non-code content in JavaDucker. All post-intake intelligence is performed by Claude Code, triggered by hooks, running asynchronously via MCP tools and CLI.

## Chapter 1: Classification Schema (O1)
**Status:** pending
**Depends on:** none

Add 6 new DuckDB tables to `SchemaBootstrap.createSchema()` and extend the `artifacts` table. No existing functionality is affected — these tables are additive.

- [ ] Add `artifact_classifications` table — `artifact_id VARCHAR PRIMARY KEY, doc_type VARCHAR, confidence FLOAT, method VARCHAR, classified_at TIMESTAMP`
- [ ] Add `artifact_tags` table — `artifact_id VARCHAR NOT NULL, tag VARCHAR NOT NULL, tag_type VARCHAR, source VARCHAR, PRIMARY KEY (artifact_id, tag)`
- [ ] Add `artifact_salient_points` table — `point_id VARCHAR PRIMARY KEY, artifact_id VARCHAR NOT NULL, chunk_id VARCHAR, point_type VARCHAR NOT NULL, point_text VARCHAR NOT NULL, source VARCHAR, created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP`
- [ ] Add `artifact_concepts` table — `concept_id VARCHAR PRIMARY KEY, artifact_id VARCHAR NOT NULL, concept VARCHAR NOT NULL, concept_type VARCHAR, mention_count INTEGER, chunk_ids VARCHAR`
- [ ] Add `concept_links` table — `concept VARCHAR NOT NULL, artifact_a VARCHAR NOT NULL, artifact_b VARCHAR NOT NULL, strength FLOAT, PRIMARY KEY (concept, artifact_a, artifact_b)`
- [ ] Add `artifact_synthesis` table — `artifact_id VARCHAR PRIMARY KEY, summary_text VARCHAR, tags VARCHAR, key_points VARCHAR, outcome VARCHAR, original_file_path VARCHAR, synthesized_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP` — holds compact records for pruned artifacts
- [ ] Add freshness columns to `artifacts` table via ALTER TABLE try-catch — `freshness VARCHAR DEFAULT 'current'` (values: current, stale, superseded), `superseded_by VARCHAR`, `freshness_updated_at TIMESTAMP`
- [ ] Add `enrichment_status VARCHAR DEFAULT 'pending'` column to `artifacts` table via ALTER TABLE try-catch — (values: pending, enriching, enriched, enrich_failed). Separate from the existing `status` column which tracks ingestion
- [ ] Add indices: `idx_classifications_doc_type` on artifact_classifications(doc_type), `idx_tags_tag` on artifact_tags(tag), `idx_salient_points_type` on artifact_salient_points(point_type), `idx_concepts_concept` on artifact_concepts(concept), `idx_artifacts_freshness` on artifacts(freshness), `idx_artifacts_enrichment` on artifacts(enrichment_status)
- [ ] Write `SchemaBootstrapTest` additions — verify all 6 new tables created, verify ALTER TABLE columns added, verify indices exist

**Notes:**
> Use ALTER TABLE with try-catch for adding columns to `artifacts` (same pattern as `reladomo_type`). Use CREATE TABLE IF NOT EXISTS for new tables. Keep `status` (ingestion lifecycle) separate from `enrichment_status` (content intelligence lifecycle).

---

## Chapter 2: Write MCP Tools & REST Endpoints (O7 — write side)
**Status:** pending
**Depends on:** Chapter 1

Add the REST endpoints and MCP tools that the async post-processor will call to write enrichment data. These are the "hands" Claude Code needs to act.

- [ ] Add `POST /api/classify` endpoint — accepts `artifactId`, `docType`, `confidence`, `method`. Upserts into `artifact_classifications`. Returns 200 with classification record
- [ ] Add `POST /api/tag` endpoint — accepts `artifactId`, `tags[]` (each with `tag`, `tagType`, `source`). DELETE existing + INSERT new for the artifact. Returns 200 with tag list
- [ ] Add `POST /api/salient-points` endpoint — accepts `artifactId`, `points[]` (each with `pointType`, `pointText`, `chunkId`, `source`). DELETE existing + INSERT new. Auto-generates `point_id` as `{artifactId}-{pointType}-{index}`. Returns 200
- [ ] Add `POST /api/concepts` endpoint — accepts `artifactId`, `concepts[]` (each with `concept`, `conceptType`, `mentionCount`). DELETE existing + INSERT new. Auto-generates `concept_id` as `{artifactId}-{concept-slug}`. Returns 200
- [ ] Add `POST /api/freshness` endpoint — accepts `artifactId`, `freshness` (current|stale|superseded), optional `supersededBy`. Updates `artifacts` columns. Returns 200
- [ ] Add `POST /api/synthesize` endpoint — accepts `artifactId`, `summaryText`, `tags`, `keyPoints`, `outcome`, `originalFilePath`. Inserts into `artifact_synthesis`, deletes from `artifact_text`, `artifact_chunks`, `chunk_embeddings` for that artifact. Sets `enrichment_status = 'enriched'`, `freshness = 'superseded'`. Returns 200
- [ ] Add `POST /api/link-concepts` endpoint — accepts pairs `[{concept, artifactA, artifactB, strength}]`. Upserts into `concept_links`. Returns 200
- [ ] Add `GET /api/enrich-queue` endpoint — returns artifacts where `enrichment_status = 'pending'` ordered by `created_at DESC`, limit 50
- [ ] Add `POST /api/mark-enriched` endpoint — accepts `artifactId`. Sets `enrichment_status = 'enriched'`, `freshness_updated_at = now()`. Returns 200
- [ ] Register 8 MCP write tools in `JavaDuckerMcpServer.java` — `javaducker_classify`, `javaducker_tag`, `javaducker_extract_points`, `javaducker_set_freshness`, `javaducker_synthesize`, `javaducker_link_concepts`, `javaducker_enrich_queue`, `javaducker_mark_enriched`. Each tool calls the corresponding REST endpoint via the same internal service methods
- [ ] Write `JavaDuckerRestControllerTest` additions — test each new endpoint: happy path, missing artifact 404, idempotent re-classification

**Notes:**
> All write endpoints use DELETE+INSERT pattern (not UPDATE) to avoid DuckDB ART index issues. `synthesize` is destructive — it prunes full text and embeddings, so the endpoint must write the synthesis record first, then delete. MCP tools follow the existing pattern in JavaDuckerMcpServer.java.

---

## Chapter 3: Read MCP Tools & REST Endpoints (O7 — read side, O4)
**Status:** pending
**Depends on:** Chapter 1

Add the query endpoints and MCP tools for Claude Code to read enriched data during normal sessions.

- [ ] Add `GET /api/latest` endpoint — accepts `topic` query param. Searches `artifact_concepts` and `artifact_tags` for topic match, joins with `artifacts` where `freshness = 'current'`, orders by `updated_at DESC`, returns top result with classification, tags, and salient points
- [ ] Add `GET /api/find-by-type` endpoint — accepts `docType` query param. Joins `artifact_classifications` with `artifacts` where `freshness != 'superseded'`. Returns list with artifact metadata
- [ ] Add `GET /api/find-by-tag` endpoint — accepts `tag` query param. Joins `artifact_tags` with `artifacts`. Returns list with artifact metadata and all tags
- [ ] Add `GET /api/find-points` endpoint — accepts `pointType` query param, optional `tag` filter. Queries `artifact_salient_points`, optionally joins with `artifact_tags`. Returns points with artifact context
- [ ] Add `GET /api/concepts` endpoint — queries `artifact_concepts` grouped by concept, with `COUNT(DISTINCT artifact_id)` as doc_count, ordered by doc_count DESC. Returns concept list with counts
- [ ] Add `GET /api/concept-timeline/{concept}` endpoint — queries `artifact_concepts` joined with `artifacts` and `artifact_classifications` for the given concept. Orders by `artifacts.created_at`. Returns timeline entries with doc_type, freshness, salient points
- [ ] Add `GET /api/stale-content` endpoint — queries `artifacts` where `freshness IN ('stale', 'superseded')`, joins with `artifact_classifications` and optionally `artifact_synthesis`. Returns list showing what's stale and what replaced it
- [ ] Add `GET /api/synthesis/{artifactId}` endpoint — returns the synthesis record for a pruned artifact (summary, tags, key points, file path)
- [ ] Register 8 MCP read tools in `JavaDuckerMcpServer.java` — `javaducker_latest`, `javaducker_find_by_type`, `javaducker_find_by_tag`, `javaducker_find_points`, `javaducker_concepts`, `javaducker_concept_timeline`, `javaducker_stale_content`, `javaducker_synthesis`. Each calls the corresponding REST endpoint
- [ ] Write `JavaDuckerRestControllerTest` additions — test each read endpoint: empty results, filtered results, concept timeline ordering, stale content listing

**Notes:**
> `javaducker_latest` is the "current truth" tool — it must prefer `freshness = 'current'` artifacts and fall back to most-recently-updated. The existing `javaducker_stale` tool checks file staleness on disk; `javaducker_stale_content` checks content freshness in the knowledge base — different concerns, keep both.

---

## Chapter 4: Enrichment Queue & Intake Wiring (O3 — queue side)
**Status:** pending
**Depends on:** Chapter 2

Wire artifact ingestion to the enrichment queue so newly indexed artifacts are automatically queued for post-processing.

- [ ] In `IngestionWorker.processArtifact()`, after the final `INDEXED` status update, set `enrichment_status = 'pending'` on the artifact. This is the intake-side queue trigger — no hook needed for this part, it's internal
- [ ] Add `enrichment_status` to the `GET /api/status/{artifactId}` response so callers can see both ingestion and enrichment status
- [ ] Add `enrichment_status` breakdown to `GET /api/stats` response — counts of pending, enriching, enriched, enrich_failed
- [ ] On re-index of an existing artifact (same `original_client_path`), reset `enrichment_status = 'pending'` and `freshness = 'current'` so the post-processor re-evaluates it
- [ ] Write test: upload artifact → verify enrichment_status = 'pending' after INDEXED. Re-index same path → verify enrichment_status reset to 'pending'

**Notes:**
> The enrichment queue is just a column filter (`enrichment_status = 'pending'`), not a separate table. This keeps it simple and queryable. The `enrich-queue` endpoint from Chapter 2 already reads this.

---

## Chapter 5: Hook-Driven Post-Processor (O3 — execution side)
**Status:** pending
**Depends on:** Chapter 2, Chapter 4

Create the Claude Code hook configuration and the enrichment script that Claude Code runs asynchronously. This is where the LLM intelligence lives.

- [ ] Create `scripts/enrich.sh` — shell script that Claude Code hooks invoke. Calls `./run-client.sh enrich-queue` to get pending artifacts, then for each artifact: fetches text via `GET /api/text/{id}`, calls Claude Code to classify/tag/extract points, writes results back via the write endpoints (classify, tag, salient-points, concepts, freshness, mark-enriched). Processes artifacts one at a time, exits 0 when queue is empty
- [ ] Create `scripts/enrich-prompt.md` — the prompt template for Claude Code enrichment. Instructs Claude to read artifact text and return structured JSON with: doc_type, tags, salient_points, concepts, freshness assessment. Includes the taxonomy from O1 (doc types, point types, concept types). This is what makes it LLM-native
- [ ] Add Claude Code hook configuration to `.claude/settings.json` — post-intake hook: fires after `javaducker_index_file` or `javaducker_index_directory` MCP tool calls, runs `scripts/enrich.sh` in async mode. Session-start hook: fires on Claude Code session start, runs `scripts/enrich.sh` in async mode to drain any backlog
- [ ] Add `--enrich` flag to `run-client.sh` — triggers a single enrichment pass (fetch queue → process → write back). Used by `scripts/enrich.sh` and available for manual invocation
- [ ] Write integration test: upload 3 diverse fixtures (an ADR markdown, a Java source file, a working notes file) → run enrichment pass → verify classification, tags, and salient points written correctly

**Notes:**
> The enrichment script is the bridge between Claude Code hooks and JavaDucker's MCP tools. It's intentionally simple — fetch queue, process each, write back. The intelligence is in the prompt, not the script. The script must be idempotent and safe to run concurrently (each artifact is claimed by setting enrichment_status = 'enriching' before processing).

---

## Chapter 6: Temporal Awareness & Freshness (O2)
**Status:** pending
**Depends on:** Chapter 5

Extend the post-processor to evaluate temporal relationships and freshness. This is an enrichment of the enrichment prompt, not new infrastructure.

- [ ] Extend `scripts/enrich-prompt.md` with freshness evaluation instructions — when processing an artifact, Claude also receives summaries of other artifacts tagged with the same concepts. Claude evaluates: is this artifact the latest on its topic? Does it supersede something? Is it superseded by something newer? Output includes `freshness` (current|stale|superseded) and `superseded_by` if applicable
- [ ] Add `GET /api/related-by-concept/{artifactId}` endpoint — returns other artifacts sharing concepts with the given artifact, ordered by created_at DESC. Used by the enrichment script to provide context for freshness evaluation
- [ ] Update `scripts/enrich.sh` to call `related-by-concept` before enriching each artifact, pass the related artifact summaries into the prompt context
- [ ] Add freshness cascade logic to `POST /api/freshness` — when an artifact is marked `superseded`, check if any other artifacts list it as `superseded_by` and update their freshness too (chain resolution)
- [ ] Extend search endpoint `POST /api/search` — add optional `freshness` filter param. Default behavior: boost `current` artifacts in ranking, demote `stale`, exclude `superseded` unless explicitly requested
- [ ] Write test: upload two plans on the same topic (v1 then v2) → run enrichment → verify v1 marked stale/superseded, v2 marked current

**Notes:**
> Freshness evaluation requires cross-artifact context — Claude needs to see what else exists on the same topic. This is why `related-by-concept` must exist before freshness can work. Keep the context window manageable: send summaries of related artifacts, not full text.

---

## Chapter 7: Pruning & Synthesis (O5)
**Status:** pending
**Depends on:** Chapter 6

Add the pruning pass to the post-processor. After enrichment and freshness evaluation, Claude decides what to synthesize and prune.

- [ ] Extend `scripts/enrich-prompt.md` with pruning instructions — after freshness evaluation, Claude evaluates superseded and stale artifacts for pruning. Output includes a `prune` boolean and, if true, a `synthesis` object (summary, key_points, outcome). Criteria: superseded + older than N days, or stale + no updates for M days (configurable thresholds in prompt)
- [ ] Update `scripts/enrich.sh` to process prune decisions — for each artifact marked for pruning, call `POST /api/synthesize` which writes the synthesis record and deletes full text/chunks/embeddings
- [ ] Add `GET /api/synthesis/search` endpoint — searches synthesis records by keyword in summary_text and tags. Returns synthesis records with file paths so Claude Code can read originals if needed
- [ ] Register `javaducker_synthesis_search` MCP tool — wraps the search endpoint
- [ ] Add safety: `POST /api/synthesize` must verify the artifact has `freshness = 'superseded'` or `freshness = 'stale'` before pruning. Reject pruning of `current` artifacts with 409 Conflict
- [ ] Write test: upload artifact → enrich → mark superseded → synthesize → verify text/chunks/embeddings deleted, synthesis record exists, original file path preserved

**Notes:**
> Pruning is destructive within DuckDB but non-destructive overall — original files stay on disk. The synthesis record is the breadcrumb. The safety check prevents accidental pruning of current content. Configurable thresholds in the prompt (not code) so they can be tuned without redeployment.

---

## Chapter 8: Concept Lifecycle (O6)
**Status:** pending
**Depends on:** Chapter 5, Chapter 6

Build cross-document concept linking and lifecycle tracking.

- [ ] Extend `scripts/enrich-prompt.md` with concept linking instructions — after extracting concepts for an artifact, Claude also outputs `concept_links` connecting this artifact's concepts to other artifacts sharing the same concept. Strength based on how central the concept is to each artifact
- [ ] Update `scripts/enrich.sh` to call `POST /api/link-concepts` with the concept links from the enrichment response
- [ ] Add `GET /api/concept-health` endpoint — for each concept, reports: number of active (current) docs, number of stale docs, last mention date, trend (growing|stable|fading). Computed from `artifact_concepts` joined with `artifacts.freshness`
- [ ] Register `javaducker_concept_health` MCP tool — wraps the endpoint
- [ ] Add concept contradiction detection to the enrichment prompt — when Claude sees two `current` artifacts making conflicting claims about a concept, flag it in the enrichment output. Store as a salient point of type `CONTRADICTION` on both artifacts
- [ ] Write test: upload 3 artifacts mentioning the same concept across different doc types → enrich → verify concept_links created, concept_timeline shows ordered entries, concept_health reports correct counts

**Notes:**
> Concept linking is the most context-heavy part of enrichment — Claude needs to see summaries of all artifacts sharing a concept. Batch concept link generation to avoid O(n²) comparisons: group by concept, process one concept at a time. Contradiction detection is a stretch goal within this chapter — implement concept links first, then add contradiction if time permits.

---

## Chapter 9: CLI Wiring & End-to-End Validation
**Status:** pending
**Depends on:** all previous chapters

Add CLI subcommands to `run-client.sh` for all new endpoints and run a full end-to-end validation.

- [ ] Add CLI subcommands to `run-client.sh` for all write endpoints — `classify`, `tag`, `salient-points`, `concepts`, `freshness`, `synthesize`, `link-concepts`, `enrich-queue`, `mark-enriched`
- [ ] Add CLI subcommands to `run-client.sh` for all read endpoints — `latest`, `find-by-type`, `find-by-tag`, `find-points`, `concepts-list`, `concept-timeline`, `stale-content`, `synthesis`, `concept-health`, `synthesis-search`
- [ ] Add `--enrich-all` CLI command — convenience wrapper that runs the full enrichment pass (same as hooks would trigger)
- [ ] Create `test-corpus/intelligence/` with 5 fixture files: an ADR, a plan (v1), an updated plan (v2 superseding v1), a working notes file, a status update. Varying topics with overlapping concepts
- [ ] Write end-to-end shell test: upload all 5 fixtures → run enrichment pass → verify: v1 plan marked superseded by v2, all artifacts classified and tagged, concepts linked across docs, `latest` query returns v2 plan not v1, salient points extracted, concept timeline shows correct order
- [ ] Verify hook configuration works: start Claude Code session → confirm enrich.sh fires in background → confirm pending artifacts get enriched without user interaction

**Notes:**
> This chapter is validation, not new features. If anything fails here, fix it in the relevant chapter. The fixture files should be realistic — use content similar to actual project notes, not lorem ipsum. The end-to-end test is the definition of done for the entire plan.
