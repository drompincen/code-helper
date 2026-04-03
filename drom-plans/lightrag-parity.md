---
title: LightRAG Feature Parity — Knowledge Graph + Semantic Tags
status: completed
created: 2026-04-02
updated: 2026-04-02
current_chapter: 10
---

# LightRAG Feature Parity — Knowledge Graph + Semantic Tags

## Problem

code-helper currently provides chunk-level vector search (TF-IDF + HNSW) and basic concept tracking, but this is **naive RAG** — it misses structural relationships between concepts across files. LightRAG (github.com/HKUDS/LightRAG) demonstrates that graph-augmented RAG dramatically improves retrieval by:

1. **Building a knowledge graph** from extracted entities and relationships
2. **Multi-level retrieval** — entity-local, relationship-global, and hybrid modes
3. **Entity merging** — deduplicating and consolidating entity descriptions across documents
4. **Community detection** — grouping related entities for high-level summaries

Additionally, Claude needs **functional semantic tags** (4-10 per file) that capture what a file *does* and *means* beyond its filename and imports — e.g., "error-handling", "authentication", "rate-limiting", "database-migration", "event-sourcing".

## Gap Analysis

| Capability | LightRAG | code-helper | Gap |
|---|---|---|---|
| Chunk-level vector search | Yes (naive mode) | Yes (exact/semantic/hybrid) | None |
| Entity extraction (LLM) | Yes, with gleaning | No (regex imports only) | **Full gap** |
| Relationship extraction | Yes, typed + described | concept_links (shallow) | **Major gap** |
| Knowledge graph storage | Yes (Neo4j, NetworkX, PG) | No dedicated graph tables | **Full gap** |
| Entity merging/dedup | Yes (LLM summarization) | No | **Full gap** |
| Local retrieval (entity-centric) | Yes | No | **Full gap** |
| Global retrieval (relationship) | Yes | No | **Full gap** |
| Hybrid graph+vector retrieval | Yes (mix mode) | No | **Full gap** |
| Community detection | Yes | No | **Full gap** |
| Source provenance per entity | Yes (source_ids) | No | **Full gap** |
| Semantic tags (4-10 per file) | No | artifact_tags (manual) | **Partial** |
| Incremental graph updates | Yes | N/A | **Full gap** |
| Citation in search results | Yes (include_references) | No | **Full gap** |

## Architecture

```
Current:   Claude → search → chunk embeddings → cosine similarity → results

Target:    Claude → search → ┬─ chunk embeddings (naive)
                             ├─ entity graph (local)      ← entity embeddings
                             ├─ relationship graph (global) ← rel embeddings
                             └─ combined (hybrid/mix)
                             
           Claude → tag_synthesis → index file → extract entities → build graph
                                               → generate 4-10 semantic tags
                                               → link entities via relationships
```

All LLM-powered extraction happens via Claude Code calling MCP tools — no embedded LLM. The pipeline is:
1. File indexed (existing) → chunks + embeddings created
2. Claude calls `javaducker_extract_entities` → entities + relationships extracted and stored
3. Claude calls `javaducker_synthesize_tags` → 4-10 semantic tags generated and stored
4. Graph is queryable immediately via new retrieval modes

---

## Chapter 1: Secondary Semantic Tags Table & Synthesis
**Status:** completed
**Depends on:** none

New table `artifact_semantic_tags` with richer schema than existing `artifact_tags`. Each file gets 4-10 tags capturing functional/semantic meaning. Tags are synthesized by Claude Code calling an MCP tool that stores the result.

- [ ] Add `artifact_semantic_tags` table to `SchemaBootstrap.java`:
  ```sql
  CREATE TABLE IF NOT EXISTS artifact_semantic_tags (
    artifact_id VARCHAR NOT NULL,
    tag VARCHAR NOT NULL,
    category VARCHAR NOT NULL,      -- functional, architectural, domain, pattern, concern
    confidence FLOAT DEFAULT 1.0,
    rationale VARCHAR,              -- why this tag was assigned (for Claude to explain)
    source VARCHAR DEFAULT 'llm',   -- llm, manual, rule
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (artifact_id, tag)
  )
  ```
- [ ] Add index `idx_semantic_tags_tag` on `artifact_semantic_tags(tag)`
- [ ] Add index `idx_semantic_tags_category` on `artifact_semantic_tags(category)`
- [ ] Add `SemanticTagService.java` in `server/mcp/` (or appropriate service package):
  - `writeTags(artifactId, List<SemanticTag> tags)` — DELETE existing + INSERT new (4-10 tags)
  - `findByTag(tag)` — find all artifacts with a given semantic tag
  - `findByCategory(category)` — find all artifacts in a tag category
  - `searchByTags(List<String> tags)` — find artifacts matching any/all of a tag set
  - `getTagCloud()` — all tags with artifact counts, grouped by category
  - `getSuggestedTags(artifactId)` — return existing tags for similar files (by embedding similarity) as suggestions
- [ ] Add `javaducker_synthesize_tags` MCP tool — accepts `artifactId` + `tags[]` (each with tag, category, confidence, rationale). Calls `SemanticTagService.writeTags()`. Returns stored tags
- [ ] Add `javaducker_search_by_tags` MCP tool — accepts `tags[]`, optional `matchMode` (any/all), optional `category` filter. Returns matching artifacts with scores
- [ ] Add `javaducker_tag_cloud` MCP tool — returns all semantic tags grouped by category with counts
- [ ] Add `javaducker_suggest_tags` MCP tool — given an `artifactId`, finds similar files and returns their tags as suggestions for the current file
- [ ] Add REST endpoints: `POST /api/semantic-tags`, `GET /api/semantic-tags/search`, `GET /api/semantic-tags/cloud`, `GET /api/semantic-tags/suggest/{id}`
- [ ] Write tests: `SemanticTagServiceTest.java` — write/read/search/cloud/suggest operations, verify 4-10 tag constraint enforcement
- [ ] Write tests: MCP tool integration tests for all 4 new tools

**Tag categories and examples:**
- **functional**: error-handling, authentication, authorization, validation, caching, logging, retry-logic, rate-limiting, pagination, serialization
- **architectural**: controller, service, repository, middleware, event-handler, scheduler, interceptor, filter, adapter, facade
- **domain**: user-management, payment-processing, order-fulfillment, notification, reporting, onboarding, billing, inventory
- **pattern**: factory, builder, observer, strategy, decorator, singleton, template-method, chain-of-responsibility, event-sourcing, CQRS
- **concern**: performance-critical, security-sensitive, backwards-compatible, deprecated, experimental, tech-debt, high-complexity

---

## Chapter 2: Knowledge Graph Schema & Storage
**Status:** completed
**Depends on:** none (parallel with Chapter 1)

Create the entity-relationship graph tables in DuckDB. This is the foundation for graph-based retrieval.

- [ ] Add `entities` table to `SchemaBootstrap.java`:
  ```sql
  CREATE TABLE IF NOT EXISTS entities (
    entity_id VARCHAR PRIMARY KEY,
    entity_name VARCHAR NOT NULL,
    entity_type VARCHAR NOT NULL,     -- class, interface, method, function, module, concept, pattern, service, config, enum, annotation, exception, table, endpoint
    description VARCHAR,
    summary VARCHAR,                  -- LLM-merged summary when entity appears in multiple files
    source_artifact_ids VARCHAR,      -- JSON array of artifact_ids where entity was found
    source_chunk_ids VARCHAR,         -- JSON array of chunk_ids for provenance
    mention_count INTEGER DEFAULT 1,
    embedding DOUBLE[256],            -- entity description embedding for local retrieval
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  )
  ```
- [ ] Add `entity_relationships` table:
  ```sql
  CREATE TABLE IF NOT EXISTS entity_relationships (
    relationship_id VARCHAR PRIMARY KEY,
    source_entity_id VARCHAR NOT NULL,
    target_entity_id VARCHAR NOT NULL,
    relationship_type VARCHAR NOT NULL,  -- uses, extends, implements, calls, depends-on, configures, tests, creates, contains, references
    description VARCHAR,
    weight FLOAT DEFAULT 1.0,
    source_artifact_ids VARCHAR,         -- JSON array: which files contain this relationship
    source_chunk_ids VARCHAR,
    embedding DOUBLE[256],               -- relationship description embedding for global retrieval
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (source_entity_id, target_entity_id, relationship_type)
  )
  ```
- [ ] Add `entity_communities` table (for future community detection):
  ```sql
  CREATE TABLE IF NOT EXISTS entity_communities (
    community_id VARCHAR PRIMARY KEY,
    community_name VARCHAR,
    summary VARCHAR,                     -- LLM-generated community summary
    entity_ids VARCHAR,                  -- JSON array of entity_ids in this community
    level INTEGER DEFAULT 0,             -- hierarchy level (0 = leaf community)
    parent_community_id VARCHAR,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
  )
  ```
- [ ] Add indices: `idx_entities_name` on entities(entity_name), `idx_entities_type` on entities(entity_type), `idx_rel_source` on entity_relationships(source_entity_id), `idx_rel_target` on entity_relationships(target_entity_id), `idx_rel_type` on entity_relationships(relationship_type), `idx_community_level` on entity_communities(level)
- [ ] Write `SchemaBootstrapTest` additions — verify all 3 new tables created with correct columns, verify indices exist
- [ ] Add `KnowledgeGraphService.java`:
  - `upsertEntity(entity)` — insert or merge entity (update description/summary, append source_ids, increment mention_count)
  - `upsertRelationship(rel)` — insert or update relationship (merge descriptions, update weight)
  - `getEntity(entityId)` — by ID
  - `findEntitiesByName(name)` — fuzzy match on entity_name
  - `findEntitiesByType(type)` — filter by entity_type
  - `getRelationships(entityId)` — all relationships for an entity (both directions)
  - `getNeighborhood(entityId, depth)` — BFS traversal N hops from entity
  - `getPath(fromEntityId, toEntityId)` — shortest path between entities
  - `getEntityCount()`, `getRelationshipCount()` — stats
  - `deleteEntitiesForArtifact(artifactId)` — remove entities only sourced from this artifact
- [ ] Write tests: `KnowledgeGraphServiceTest.java` — upsert, merge, traversal, path, deletion

---

## Chapter 3: Entity & Relationship Extraction (LLM-powered via MCP)
**Status:** completed
**Depends on:** Chapter 2

MCP tools that Claude Code calls to extract entities and relationships from indexed files. Claude reads the file text, identifies entities/relationships, and writes them via these tools. This is the equivalent of LightRAG's extraction pipeline but using Claude as the LLM.

- [ ] Add `javaducker_extract_entities` MCP tool — accepts:
  - `artifactId` — the file to extract from
  - `entities[]` — each with: name, type, description
  - `relationships[]` — each with: sourceName, targetName, type, description
  - Implementation: resolve entity names to IDs (create if new, merge if existing), upsert all entities and relationships, compute embeddings for descriptions, update source_artifact_ids/source_chunk_ids
- [ ] Add `javaducker_get_entities` MCP tool — accepts optional `artifactId`, `entityType`, `namePattern`. Returns entities with their relationships
- [ ] Add `javaducker_merge_entities` MCP tool — accepts `sourceEntityId`, `targetEntityId`. Merges source into target: combine descriptions, union source_ids, sum mention_counts, update all relationships pointing to source to point to target, delete source
- [ ] Add `javaducker_delete_entities` MCP tool — accepts `artifactId`. Removes all entities and relationships that are solely sourced from this artifact (decrement mention_count, remove from source_ids, delete if mention_count reaches 0)
- [ ] Add `javaducker_graph_stats` MCP tool — returns entity count, relationship count, top entity types, most connected entities, orphan entities
- [ ] Add REST endpoints: `POST /api/entities/extract`, `GET /api/entities`, `POST /api/entities/merge`, `DELETE /api/entities/by-artifact/{id}`, `GET /api/graph/stats`
- [ ] Add entity extraction prompt template in `EntityExtractionPrompt.java` — a reusable prompt template that Claude can use to self-prompt for extraction:
  - Input: file text, file type, existing entity names (for merge candidates)
  - Output: structured JSON with entities[] and relationships[]
  - Include code-specific entity types: class, interface, method, function, module, endpoint, table, config-key, event, exception
  - Include gleaning instruction: "re-read the text and check for missed entities"
- [ ] Write tests: entity extraction tool tests, merge tests, deletion cascade tests
- [ ] Write tests: verify embeddings are computed for entity/relationship descriptions

**Entity type taxonomy for code:**
- `class`, `interface`, `enum`, `annotation` — type declarations
- `method`, `function` — callable units
- `module`, `package` — organizational units
- `endpoint` — REST/API endpoints (e.g., "POST /api/search")
- `table` — database tables referenced in code
- `config-key` — configuration properties (e.g., "spring.datasource.url")
- `event` — events published/consumed
- `exception` — custom exception types
- `concept` — abstract concepts (e.g., "caching", "retry logic")
- `service` — logical services (e.g., "search service", "ingestion pipeline")
- `pattern` — design patterns detected (e.g., "builder pattern", "factory")

---

## Chapter 4: Graph-Based Retrieval Modes
**Status:** completed
**Depends on:** Chapter 2, Chapter 3

Add LightRAG-style retrieval modes that use the knowledge graph alongside vector search. Extend `SearchService` with new modes.

- [ ] Add `GraphSearchService.java`:
  - `localSearch(query, topK)` — **entity-centric retrieval**:
    1. Embed the query
    2. Find top-K entities by cosine similarity on entity.embedding
    3. For each entity, gather: entity description, connected relationships, source chunks
    4. Assemble context with source provenance
    5. Return ranked results with entity references
  - `globalSearch(query, topK)` — **relationship-centric retrieval**:
    1. Embed the query
    2. Find top-K relationships by cosine similarity on relationship.embedding
    3. For each relationship, gather: source entity, target entity, relationship description, community context
    4. Assemble higher-level context about how concepts relate
    5. Return ranked results with relationship references
  - `hybridGraphSearch(query, topK)` — combine local + global results
  - `mixSearch(query, topK)` — combine graph search (hybrid) + chunk vector search (existing), deduplicate, rerank
- [ ] Extend `SearchService.java` to support new search modes:
  - Add `mode` parameter options: `exact`, `semantic`, `hybrid` (existing), `local`, `global`, `graph_hybrid`, `mix`
  - `mix` becomes the recommended default for Claude when graph is populated
  - Fall back to existing `hybrid` mode when graph has no entities
- [ ] Update `javaducker_search` MCP tool to accept new mode values
- [ ] Add `javaducker_graph_search` MCP tool — dedicated tool for graph-only search with more options:
  - `query` — search text
  - `mode` — local/global/hybrid
  - `topK` — max results
  - `entityTypes[]` — optional filter by entity type
  - `includeProvenance` — include source file references
- [ ] Add provenance/citation to search results — each result includes `sourceFiles[]` with file paths and line ranges
- [ ] Write tests: `GraphSearchServiceTest.java` — local, global, hybrid, mix modes with fixture data
- [ ] Write tests: verify fallback to chunk search when graph is empty
- [ ] Write tests: verify provenance/citation data in results

---

## Chapter 5: Entity Merging & Deduplication
**Status:** completed
**Depends on:** Chapter 3

When the same logical entity appears in multiple files (e.g., "SearchService" referenced in tests, controllers, and config), merge descriptions into a consolidated summary. This is equivalent to LightRAG's map-reduce entity summarization.

- [ ] Add merge detection to `KnowledgeGraphService`:
  - `findDuplicateCandidates()` — find entities with similar names (Levenshtein distance < 3, or same name different case)
  - `findMergeCandidates(entityId)` — find entities with high embedding similarity to a given entity (cosine > 0.85)
  - Score candidates by: name similarity + embedding similarity + shared relationships
- [ ] Add `javaducker_merge_candidates` MCP tool — returns pairs of entities that may be duplicates, with confidence scores. Claude reviews and confirms merges
- [ ] Add merge summarization prompt template in `EntityMergePrompt.java`:
  - Input: entity A description, entity B description, shared relationships
  - Output: merged description that preserves key information from both
- [ ] Add auto-merge for exact name matches (same entity_name, same entity_type) — happens automatically during `upsertEntity`
- [ ] Add `javaducker_confirm_merge` MCP tool — Claude provides merged description after reviewing candidates, tool executes the merge
- [ ] Write tests: duplicate detection, merge execution, relationship rewiring, mention count accumulation
- [ ] Write tests: auto-merge on exact match during upsert

---

## Chapter 6: Community Detection
**Status:** completed
**Depends on:** Chapter 4, Chapter 5

Group related entities into communities for global-level retrieval. Uses a simple modularity-based approach (no external library needed — Louvain-like algorithm on the entity relationship graph).

- [ ] Add `CommunityDetectionService.java`:
  - `detectCommunities()` — run community detection on the entity relationship graph:
    1. Build adjacency list from entity_relationships
    2. Apply label propagation algorithm (simpler than Louvain, sufficient for code graphs)
    3. Each community gets a generated name based on its most prominent entities
    4. Store communities in `entity_communities` table
  - `summarizeCommunity(communityId)` — returns the entities and their descriptions for Claude to generate a summary
  - `getCommunities()` — list all communities with stats
  - `getCommunity(communityId)` — community details with member entities
  - `rebuildCommunities()` — full re-detection
- [ ] Add `javaducker_detect_communities` MCP tool — triggers community detection, returns community list
- [ ] Add `javaducker_summarize_community` MCP tool — accepts communityId + summary text (Claude generates the summary after reading community members). Stores summary in entity_communities
- [ ] Add `javaducker_communities` MCP tool — list communities with member counts
- [ ] Update `globalSearch` in `GraphSearchService` to use community summaries when available — search community summary embeddings first, then drill into member entity relationships
- [ ] Write tests: community detection on fixture graph, community CRUD, search integration
- [ ] Write tests: verify community summaries improve global search relevance

---

## Chapter 7: Incremental Graph Updates
**Status:** completed
**Depends on:** Chapter 3, Chapter 5

When a file is re-indexed (content changed), the knowledge graph must be updated incrementally — remove stale entities/relationships, extract new ones, merge updated descriptions. This parallels LightRAG's incremental insert capability.

- [ ] Add `GraphUpdateService.java`:
  - `onArtifactReindexed(artifactId)` — called after a file is re-indexed:
    1. Decrement mention_count for all entities sourced from this artifact
    2. Remove artifact from source_artifact_ids arrays
    3. Delete entities with mention_count = 0
    4. Delete relationships with empty source_artifact_ids
    5. Mark affected communities as stale
  - `onArtifactDeleted(artifactId)` — same as reindexed but without re-extraction
- [ ] Hook into `UploadService` re-index flow — after chunks/embeddings are updated, call `onArtifactReindexed`
- [ ] Add `javaducker_reindex_graph` MCP tool — given an `artifactId`, Claude can trigger entity re-extraction after viewing the updated file. Calls `onArtifactReindexed` + prompts Claude to call `extract_entities` again
- [ ] Add `javaducker_graph_stale` MCP tool — list entities/relationships that reference artifacts which have been re-indexed since last extraction
- [ ] Write tests: incremental update scenarios (file changed, file deleted, entity shared across files)
- [ ] Write tests: verify entity survives deletion of one source if it has other sources
- [ ] Write tests: verify community staleness detection

---

## Chapter 8: Enrichment Pipeline Orchestration
**Status:** completed
**Depends on:** Chapter 1, Chapter 3, Chapter 6

Create an MCP tool that gives Claude a structured enrichment pipeline to run on newly indexed files. Claude calls this tool to get the next batch of files needing enrichment, then processes each one.

- [ ] Add `javaducker_enrichment_pipeline` MCP tool — returns a structured work plan:
  ```json
  {
    "pending_files": [...],
    "steps_per_file": [
      "1. Read file text via javaducker_get_file_text",
      "2. Call javaducker_synthesize_tags with 4-10 semantic tags",
      "3. Call javaducker_extract_entities with entities and relationships",
      "4. Call javaducker_classify if not yet classified",
      "5. Call javaducker_mark_enriched when done"
    ],
    "batch_size": 10,
    "graph_stats": { "entities": N, "relationships": N, "communities": N }
  }
  ```
- [ ] Add `javaducker_enrichment_status` MCP tool — returns progress: total files, enriched count, pending count, graph stats, community count
- [ ] Add `javaducker_rebuild_graph` MCP tool — nuclear option: clear all entities/relationships/communities, return list of all indexed artifacts for full re-extraction
- [ ] After enrichment of a batch, auto-trigger community re-detection if entity count changed by >10%
- [ ] Write tests: pipeline tool returns correct work plan, status reports accurate counts
- [ ] Write tests: rebuild clears graph cleanly

---

## Chapter 9: Search UX Improvements
**Status:** completed
**Depends on:** Chapter 4, Chapter 1

Make the graph-augmented search and semantic tags useful in practice for Claude Code.

- [ ] Update `javaducker_search` tool description to explain when to use each mode:
  - `exact` — known string/identifier lookup
  - `semantic` — conceptual similarity (existing TF-IDF)
  - `hybrid` — default, combines exact + semantic
  - `local` — find specific entities/classes/methods (graph)
  - `global` — understand how concepts relate across the codebase (graph)
  - `mix` — best overall: graph + vector combined (recommended when graph is populated)
- [ ] Add semantic tag search to `javaducker_search` — when mode is `hybrid` or `mix`, also match against semantic tags and boost results that match tag queries
- [ ] Add `javaducker_find_related` MCP tool — given an `artifactId`, find related files via:
  1. Shared semantic tags (same tags → high relevance)
  2. Entity co-occurrence (share entities → medium relevance)
  3. Relationship paths (connected via graph → lower relevance)
  4. Existing co-change data (git history)
  - Return unified ranked list with relationship explanation per result
- [ ] Add `javaducker_explain` enhancement — include semantic tags and entity participation in the explain output
- [ ] Write tests: tag-boosted search, find_related ranking, explain output includes graph data

---

## Chapter 10: Coverage & Integration Tests (Closed Loop)
**Status:** completed
**Depends on:** all previous chapters

Ensure all new functionality has tests and overall coverage stays at or above ~70%. **This chapter runs as a closed-loop** per `workflows/closed-loop.md`.

### Closed-loop protocol
- **Pass condition:** instruction coverage >= 70%, all tests green, zero regressions
- **Max iterations:** 5
- **Capture per iteration:** total tests, pass/fail counts, instruction coverage %, branch coverage %, files with lowest coverage

### Steps

- [ ] Run `mvn test` — establish baseline: total test count, pass/fail
- [ ] Run `mvn jacoco:report` — capture baseline instruction coverage %
- [ ] Identify new classes with < 70% coverage (SemanticTagService, KnowledgeGraphService, GraphSearchService, CommunityDetectionService, GraphUpdateService, enrichment tools)
- [ ] **Iteration loop** — for each under-covered class:
  - [ ] Add targeted tests to raise its coverage
  - [ ] Re-run `mvn test` — verify no regressions (test count must not decrease, failures must be 0)
  - [ ] Re-run `mvn jacoco:report` — check coverage delta
  - [ ] If coverage regressed vs previous iteration → revert, try different approach
  - [ ] Log iteration to context/MEMORY.md: iteration #, coverage %, tests added, classes covered
- [ ] Add integration tests:
  - [ ] `GraphSearchServiceTest.java` — end-to-end: index files → extract entities → search via all graph modes
  - [ ] `SemanticTagIntegrationTest.java` — full pipeline: index → synthesize tags → search by tags
  - [ ] `CommunityDetectionIntegrationTest.java` — detect communities → global search
  - [ ] `IncrementalGraphUpdateTest.java` — re-index changed file → verify graph updated
  - [ ] `EnrichmentPipelineTest.java` — pipeline tool output, status, rebuild
- [ ] Final verification: all tests green, coverage >= 70%, no regressions from baseline 740+ tests
- [ ] Log final summary to context/MEMORY.md

---

## Implementation Notes

### What Claude Code does vs what the server does
- **Server (Java)**: stores entities/relationships/tags, computes embeddings, runs graph traversal, executes search queries
- **Claude Code (LLM)**: reads file text, identifies entities and relationships, generates semantic tags, writes merged entity descriptions, generates community summaries
- This split mirrors LightRAG's architecture but uses Claude Code as the LLM instead of an embedded model

### DuckDB considerations
- Entity embeddings use same DOUBLE[256] format as chunk_embeddings
- HNSW index can be extended to entity/relationship embeddings
- JSON arrays for source_ids stored as VARCHAR (DuckDB has json_extract functions if needed)
- DELETE+INSERT pattern for upserts (known DuckDB ART index constraint)

### Semantic tag synthesis prompt (for Claude to use)
When Claude calls `javaducker_synthesize_tags`, it should:
1. Read the file text and summary
2. Consider: what does this file DO? (functional), how is it structured? (architectural), what domain does it serve? (domain), what patterns does it use? (pattern), what cross-cutting concerns? (concern)
3. Generate 4-10 tags covering at least 3 categories
4. Include rationale for each tag (helps future Claude understand tag assignment)

### Migration from existing concept tables
- `artifact_concepts` and `concept_links` tables remain — they serve a different (simpler) purpose
- New `entities` and `entity_relationships` tables are the knowledge graph
- Over time, concept data can be migrated to entities if desired
