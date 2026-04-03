---
title: Migrate MCP Server from JBang to Spring AI
status: completed
created: 2026-03-29
updated: 2026-03-29
current_chapter: 7
---

# Migrate MCP Server from JBang to Spring AI

## Problem

The current MCP server (`JavaDuckerMcpServer.java`) is a standalone JBang script that:
- Uses outdated MCP SDK 0.8.1 (latest is 1.1.1) — Claude Code can't see tools
- Proxies all calls via HTTP to the Spring Boot REST API (unnecessary round-trip)
- Must start and manage the Spring Boot server as a subprocess
- Has fragile stdout/stderr handling that can corrupt JSON-RPC stdio messages
- References moved scripts (`run-server.sh`) that no longer exist at expected paths

## Solution

Replace the JBang script with Spring AI's built-in MCP server support. Tools become Spring beans that call services directly — no HTTP proxy, no subprocess management, no SDK version drift.

## Architecture

```
Before:  Claude Code → stdio → JBang MCP (0.8.1) → HTTP → Spring Boot REST → Services
After:   Claude Code → stdio → Spring Boot + Spring AI MCP → Services (direct)
```

## Key Decisions

- **Spring Boot 3.4+** required for Spring AI 1.0 compatibility (currently 3.2.5)
- **`spring-ai-mcp-server-spring-boot-starter`** provides stdio + SSE transport auto-config
- **`@McpTool`/`@McpToolParam` annotations** on methods in `@Component` classes (Spring AI 1.1+)
- **`spring-ai-starter-mcp-server-webmvc`** allows SSE alongside existing REST + optional stdio
- **Two Spring profiles**: `mcp` (stdio, no web server) and `server` (REST API, web)
- Tools call injected services directly — no HTTP client, no REST controller in the loop
- The REST API continues to work independently for non-MCP clients

## Tool Inventory (40 tools from JBang → Spring AI)

### Group 1: Core (8 tools) — UploadService, ArtifactService, SearchService, StatsService
- `javaducker_health`, `javaducker_stats`
- `javaducker_index_file`, `javaducker_index_directory`
- `javaducker_search`
- `javaducker_get_file_text`, `javaducker_get_artifact_status`, `javaducker_wait_for_indexed`

### Group 2: Analysis (8 tools) — ExplainService, GitBlameService, CoChangeService, DependencyService, ProjectMapService, StalenessService
- `javaducker_explain`, `javaducker_blame`, `javaducker_related`
- `javaducker_dependencies`, `javaducker_dependents`
- `javaducker_map`, `javaducker_stale`, `javaducker_index_health`, `javaducker_summarize`

### Group 3: Watch (1 tool) — FileWatcher
- `javaducker_watch`

### Group 4: Content Intelligence Write (8 tools) — ContentIntelligenceService
- `javaducker_classify`, `javaducker_tag`, `javaducker_extract_points`
- `javaducker_set_freshness`, `javaducker_synthesize`, `javaducker_link_concepts`
- `javaducker_enrich_queue`, `javaducker_mark_enriched`

### Group 5: Content Intelligence Read (8 tools) — ContentIntelligenceService
- `javaducker_latest`, `javaducker_find_by_type`, `javaducker_find_by_tag`, `javaducker_find_points`
- `javaducker_concepts`, `javaducker_concept_timeline`, `javaducker_concept_health`
- `javaducker_stale_content`, `javaducker_synthesis`

### Group 6: Reladomo (9 tools) — ReladomoQueryService
- `javaducker_reladomo_relationships`, `javaducker_reladomo_graph`, `javaducker_reladomo_path`
- `javaducker_reladomo_schema`, `javaducker_reladomo_object_files`
- `javaducker_reladomo_finders`, `javaducker_reladomo_deepfetch`
- `javaducker_reladomo_temporal`, `javaducker_reladomo_config`

### Group 7: Session Transcripts (5 tools) — SessionIngestionService
- `javaducker_index_sessions`, `javaducker_search_sessions`, `javaducker_session_context`
- `javaducker_extract_decisions`, `javaducker_recent_decisions`

---

## Chapter 1: Spring Boot Upgrade and Spring AI Dependencies

**Status:** completed

Upgrade Spring Boot to 3.4+ and add Spring AI MCP server starter.

- [ ] Update `pom.xml`: Spring Boot parent `3.2.5` → `3.4.4` (or latest 3.4.x)
- [ ] Add Spring AI BOM to `<dependencyManagement>` (version 1.1.4)
- [ ] Add dependency: `spring-ai-starter-mcp-server-webmvc`
- [ ] Verify `mvn compile` succeeds with no breaking changes from Boot upgrade
- [ ] Run `mvn test` — fix any compilation or deprecation issues from Spring Boot 3.4
- [ ] **TEST**: Verify all existing tests still pass (baseline: 585 tests, 0 failures)
- [ ] **TEST**: Verify existing REST API still works: `mvn spring-boot:run` + `curl localhost:8080/api/health`

## Chapter 2: MCP Tool Beans — Core Group

**Status:** completed

Create tool provider class for the 8 core tools (health, index, search, stats, text, status, wait).

- [ ] Create `src/main/java/com/javaducker/server/mcp/CoreTools.java` as `@Component`
- [ ] Inject `UploadService`, `ArtifactService`, `SearchService`, `StatsService`, `AppConfig`
- [ ] Implement `@Tool` methods: `health`, `indexFile`, `indexDirectory`, `search`, `getFileText`, `getArtifactStatus`, `waitForIndexed`, `stats`
- [ ] Preserve tool names (e.g., `@Tool(name = "javaducker_search")`) and descriptions from JBang
- [ ] Preserve the staleness warning enrichment on search results
- [ ] Add `@ToolParam` annotations with descriptions matching JBang schema
- [ ] **TEST**: Create `src/test/java/com/javaducker/server/mcp/CoreToolsTest.java`
- [ ] **TEST**: Test `health` returns status map with expected keys
- [ ] **TEST**: Test `indexFile` delegates to UploadService and returns artifact_id
- [ ] **TEST**: Test `search` delegates to SearchService with correct mode/limit params
- [ ] **TEST**: Test `search` appends staleness warning when present
- [ ] **TEST**: Test `getFileText` and `getArtifactStatus` delegate correctly
- [ ] **TEST**: Test `waitForIndexed` polls and returns on INDEXED status
- [ ] **TEST**: Test `waitForIndexed` returns error on FAILED status
- [ ] **TEST**: Run `mvn test` — all pass including new tests

## Chapter 3: MCP Tool Beans — Analysis & Watch Groups

**Status:** completed

Create tool providers for analysis (9 tools) and watch (1 tool).

- [ ] Create `src/main/java/com/javaducker/server/mcp/AnalysisTools.java` as `@Component`
- [ ] Inject `ExplainService`, `GitBlameService`, `CoChangeService`, `DependencyService`, `ProjectMapService`, `StalenessService`, `ArtifactService`
- [ ] Implement `@Tool` methods: `explain`, `blame`, `related`, `dependencies`, `dependents`, `map`, `stale`, `indexHealth`, `summarize`
- [ ] Create `src/main/java/com/javaducker/server/mcp/WatchTools.java` as `@Component`
- [ ] Inject `FileWatcher` (or the service that wraps it)
- [ ] Implement `@Tool` method: `watch` (start/stop actions)
- [ ] **TEST**: Create `src/test/java/com/javaducker/server/mcp/AnalysisToolsTest.java`
- [ ] **TEST**: Test `explain` delegates to ExplainService with file path
- [ ] **TEST**: Test `blame` passes optional start_line/end_line params
- [ ] **TEST**: Test `related` passes file path and max_results to CoChangeService
- [ ] **TEST**: Test `dependencies` and `dependents` delegate by artifact_id
- [ ] **TEST**: Test `stale` handles both file_paths and git_diff_ref params
- [ ] **TEST**: Test `indexHealth` returns recommendation and health_status fields
- [ ] **TEST**: Test `summarize` appends staleness warning when file changed on disk
- [ ] **TEST**: Create `src/test/java/com/javaducker/server/mcp/WatchToolsTest.java`
- [ ] **TEST**: Test `watch` start action passes directory and extensions
- [ ] **TEST**: Test `watch` stop action works without directory param
- [ ] **TEST**: Run `mvn test` — all pass

## Chapter 4: MCP Tool Beans — Content Intelligence Groups

**Status:** completed

Create tool provider for content intelligence write (8) and read (8) tools.

- [ ] Create `src/main/java/com/javaducker/server/mcp/ContentIntelligenceTools.java` as `@Component`
- [ ] Inject `ContentIntelligenceService`
- [ ] Implement write `@Tool` methods: `classify`, `tag`, `extractPoints`, `setFreshness`, `synthesize`, `linkConcepts`, `enrichQueue`, `markEnriched`
- [ ] Implement read `@Tool` methods: `latest`, `findByType`, `findByTag`, `findPoints`, `concepts`, `conceptTimeline`, `conceptHealth`, `staleContent`, `synthesis`
- [ ] Handle JSON array string parameters (tags, points, links) — parse with ObjectMapper
- [ ] **TEST**: Create `src/test/java/com/javaducker/server/mcp/ContentIntelligenceToolsTest.java`
- [ ] **TEST**: Test `classify` delegates with artifactId, docType, confidence, method
- [ ] **TEST**: Test `tag` parses JSON array string and delegates tag list
- [ ] **TEST**: Test `extractPoints` parses JSON array string and delegates points list
- [ ] **TEST**: Test `setFreshness` delegates with freshness enum and optional superseded_by
- [ ] **TEST**: Test `synthesize` delegates all fields including optional ones
- [ ] **TEST**: Test `linkConcepts` parses JSON array of link objects
- [ ] **TEST**: Test `enrichQueue` passes limit param with default fallback
- [ ] **TEST**: Test `latest` delegates topic to service
- [ ] **TEST**: Test `findByType`, `findByTag`, `findPoints` delegate correctly
- [ ] **TEST**: Test `synthesis` routes to by-id or by-keyword based on params
- [ ] **TEST**: Test malformed JSON input returns error, not exception
- [ ] **TEST**: Run `mvn test` — all pass

## Chapter 5: MCP Tool Beans — Reladomo & Session Groups

**Status:** completed

Create tool providers for Reladomo (9 tools) and session transcript (5 tools).

- [ ] Create `src/main/java/com/javaducker/server/mcp/ReladomoTools.java` as `@Component`
- [ ] Inject `ReladomoQueryService`, `ReladomoService`
- [ ] Implement `@Tool` methods: `relationships`, `graph`, `path`, `schema`, `objectFiles`, `finders`, `deepfetch`, `temporal`, `config`
- [ ] Create `src/main/java/com/javaducker/server/mcp/SessionTools.java` as `@Component`
- [ ] Inject `SessionIngestionService`
- [ ] Implement `@Tool` methods: `indexSessions`, `searchSessions`, `sessionContext`, `extractDecisions`, `recentDecisions`
- [ ] **TEST**: Create `src/test/java/com/javaducker/server/mcp/ReladomoToolsTest.java`
- [ ] **TEST**: Test `relationships` delegates object_name to ReladomoQueryService
- [ ] **TEST**: Test `graph` passes depth param with default of 3
- [ ] **TEST**: Test `path` passes from_object and to_object params
- [ ] **TEST**: Test `config` handles optional object_name (present vs absent)
- [ ] **TEST**: Test all 9 Reladomo tools delegate to correct service method
- [ ] **TEST**: Create `src/test/java/com/javaducker/server/mcp/SessionToolsTest.java`
- [ ] **TEST**: Test `indexSessions` passes projectPath, maxSessions, incremental
- [ ] **TEST**: Test `searchSessions` passes phrase and max_results with defaults
- [ ] **TEST**: Test `sessionContext` delegates topic to service
- [ ] **TEST**: Test `extractDecisions` parses JSON decisions array
- [ ] **TEST**: Test `recentDecisions` passes maxSessions and optional tag filter
- [ ] **TEST**: Run `mvn test` — all pass

## Chapter 6: Transport Configuration and Profiles

**Status:** completed

Configure Spring profiles for stdio (MCP) vs web (REST API) operation.

- [ ] Add to `application.yml` under `spring.ai.mcp.server`: `name: javaducker`, `version: 1.0.0`
- [ ] Create `application-mcp.yml`: `spring.main.web-application-type: none`, stdio transport enabled
- [ ] Create `application-server.yml` (or keep default): web enabled, MCP disabled
- [ ] Update run-mcp script instructions to use `--spring.profiles.active=mcp`
- [ ] Update run-server script instructions to use `--spring.profiles.active=server`
- [ ] Ensure no `System.out` calls in any tool or service code (would corrupt stdio)
- [ ] **TEST**: Create `src/test/java/com/javaducker/server/mcp/McpProfileTest.java`
- [ ] **TEST**: Test MCP profile loads with `web-application-type: none` (no port binding)
- [ ] **TEST**: Test server profile loads with web enabled and MCP beans excluded
- [ ] **TEST**: Test default profile (no profile) loads REST API normally
- [ ] **TEST**: Grep all src/main/java for `System.out` — assert zero occurrences (except main methods)
- [ ] **TEST**: Run `mvn test` — all pass including profile tests

## Chapter 7: Integration Testing and JBang Retirement

**Status:** completed

End-to-end validation, tool parity check, and cleanup.

- [ ] **TEST**: Create `src/test/java/com/javaducker/integration/McpToolRegistrationTest.java`
- [ ] **TEST**: Load Spring context with MCP profile, inject `ToolCallbackProvider` beans
- [ ] **TEST**: Assert exactly 40 tools registered (match count from JBang)
- [ ] **TEST**: Assert every tool name from JBang inventory is present (hardcoded list of all 40 names)
- [ ] **TEST**: Assert every tool has a non-empty description
- [ ] **TEST**: Create `src/test/java/com/javaducker/integration/McpToolCallTest.java`
- [ ] **TEST**: Call `javaducker_health` tool via ToolCallback, verify response contains status
- [ ] **TEST**: Call `javaducker_stats` tool, verify response contains expected keys
- [ ] **TEST**: Call `javaducker_search` tool with phrase param, verify response structure
- [ ] **TEST**: Call a tool with missing required param, verify error response (not exception)
- [ ] **TEST**: Run full `mvn test` — all existing (65+) and new tests pass
- [ ] **TEST**: Record final test count and verify coverage >= 75%
- [ ] Move `JavaDuckerMcpServer.java` to `script-instructions/jbang-mcp-server-legacy.md` (preserve for reference)
- [ ] Remove JBang from run-mcp script instructions (update `script-instructions/run-scripts.md`)
- [ ] Update `script-instructions/run-scripts.md` with new run-mcp command: `java -jar target/javaducker-1.0.0.jar --spring.profiles.active=mcp`
- [ ] Update README: remove JBang mention, document `--spring.profiles.active=mcp` usage
- [ ] Update `start-here.md`: remove JBang prerequisite, simplify setup
- [ ] Verify Claude Code can see all tools when connected via stdio
