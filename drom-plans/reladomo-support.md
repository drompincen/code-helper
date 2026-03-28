---
title: Reladomo/Mithra ORM Support
status: completed
created: 2026-03-28
updated: 2026-03-28
current_chapter: 5
---

# Plan: Reladomo/Mithra ORM Support

Make JavaDucker Reladomo-native: parse XML model definitions, build a queryable domain graph, and expose MCP tools for objects, relationships, schemas, and query patterns. No new Maven deps — XML parsing via built-in `javax.xml`.

## Chapter 1: Data Model & XML Parser (F9)
**Status:** completed
**Depends on:** none

- [x] Create `src/main/java/com/javaducker/server/model/ReladomoParseResult.java` (~80 lines) — top-level record with nested records: `ReladomoAttribute`, `ReladomoRelationship`, `ReladomoIndex`. Fields: objectName, packageName, tableName, objectType, temporalType, superClass, interfaces, sourceAttribute, attribute/relationship/index lists
- [x] Create `src/main/java/com/javaducker/server/ingestion/ReladomoXmlParser.java` (~350 lines) — `@Component` using `DocumentBuilder` + `org.w3c.dom`. Methods: `isReladomoXml(String)`, `parse(String, String)`. Private helpers: `parseAttributes()`, `parseRelationships()`, `parseIndices()`, `detectTemporalType()`
- [x] Add 4 new DuckDB tables to `SchemaBootstrap.createSchema()` — `reladomo_objects` (object_name PK, package_name, table_name, object_type, temporal_type, super_class, interfaces, source_attribute_name/type, artifact_id), `reladomo_attributes` (object_name+attribute_name PK, java_type, column_name, nullable, primary_key, max_length, trim, truncate), `reladomo_relationships` (object_name+relationship_name PK, cardinality, related_object, reverse_relationship_name, parameters, join_expression), `reladomo_indices` (object_name+index_name PK, columns, is_unique)
- [x] Add `reladomo_type VARCHAR DEFAULT 'none'` column to `artifacts` table via ALTER TABLE try-catch
- [x] Hook into `IngestionWorker.processArtifact()` — after imports step, detect Reladomo XML by root element, parse via `ReladomoXmlParser`, store via `ReladomoService.storeReladomoObject()`. Inject parser + service into constructor
- [x] Create `src/test/java/com/javaducker/server/ingestion/ReladomoXmlParserTest.java` (~200 lines) — test simple object, bitemporal object, relationships, indices, MithraInterface, malformed XML, non-Reladomo XML

**Notes:**
> Use DELETE+INSERT pattern for DuckDB storage (not UPDATE — known ART index bug). Each step in try-catch so failures don't block pipeline. IngestionWorker stays under 440 lines.

## Chapter 2: Relationship Graph & Service Layer (F10)
**Status:** completed
**Depends on:** Chapter 1

- [x] Create `src/main/java/com/javaducker/server/service/ReladomoService.java` (~250 lines initial) — `@Service` with `DuckDBDataSource`. Storage: `storeReladomoObject(artifactId, parsed)` inserts into all 4 tables, `tagArtifact(artifactId, type)` updates artifacts.reladomo_type. Queries: `getRelationships(objectName)` returns direct relationships + attributes + metadata
- [x] Add graph traversal methods to `ReladomoService` — `getGraph(objectName, maxDepth)`: BFS using in-memory adjacency list from `reladomo_relationships`, returns nodes + edges. `getPath(fromObject, toObject)`: BFS shortest path, returns list of (object, relationship) pairs
- [x] Add 3 REST endpoints to `JavaDuckerRestController` — `GET /reladomo/relationships/{objectName}`, `GET /reladomo/graph/{objectName}?depth=3`, `GET /reladomo/path?from=X&to=Y`
- [x] Add 3 MCP tools to `JavaDuckerMcpServer.java` — `javaducker_reladomo_relationships` (object_name required), `javaducker_reladomo_graph` (object_name required, depth optional), `javaducker_reladomo_path` (from_object + to_object required)
- [x] Create `src/test/java/com/javaducker/server/service/ReladomoServiceTest.java` (~150 lines initial) — test store/retrieve, direct relationships, BFS graph (depth limiting), shortest path (direct, 2-hop, no-path)
- [x] Extend `FullFlowIntegrationTest` (+40 lines) — upload MithraObject XML, process, verify relationships tool returns data

**Notes:**
> Adjacency list built per-request from SELECT on reladomo_relationships. Fast enough for hundreds of objects.

## Chapter 3: Schema View & Generated Code Detection (F11 + F12)
**Status:** completed
**Depends on:** Chapter 2

- [x] Add `getSchema(objectName)` to `ReladomoService` (~80 lines) — builds CREATE TABLE DDL with type mapping (boolean->BIT, int->INTEGER, long->BIGINT, String->VARCHAR(maxLength), Timestamp->TIMESTAMP, Date->DATE, BigDecimal->DECIMAL, byte[]->VARBINARY), temporal columns (IN_Z/OUT_Z for processing, FROM_Z/THRU_Z for business, all 4 for bitemporal), PK constraint, index definitions
- [x] Add `classifyReladomoArtifact(fileName)` to `ReladomoService` (~60 lines) — pattern match: `*Abstract.java`/`*Finder.java`/`*List.java`/`*DatabaseObject.java`/`*Data.java` -> generated, concrete class matching known object -> hand-written, `*MithraObject.xml` -> xml-definition, `MithraRuntime*.xml` -> config
- [x] Add `getObjectFiles(objectName)` to `ReladomoService` — query artifacts table by filename patterns derived from object name, group by reladomo_type
- [x] Hook classification into `IngestionWorker` — after Reladomo XML step, classify `.java` files via `classifyReladomoArtifact()`, tag via `tagArtifact()`
- [x] Add 2 REST endpoints — `GET /reladomo/schema/{objectName}`, `GET /reladomo/files/{objectName}`
- [x] Add 2 MCP tools — `javaducker_reladomo_schema` (object_name required), `javaducker_reladomo_object_files` (object_name required)
- [x] Add schema + classification tests to `ReladomoServiceTest` (~100 lines) — verify DDL output, temporal columns, type mapping, classification patterns

**Notes:**
> Classification queries `reladomo_objects` for known names. If XML not yet ingested, falls back to filename-only patterns (catches most generated code). F11 and F12 steps within this chapter are independent and can be worked in parallel.

## Chapter 4: Finder & Deep Fetch Parsing (F13 + F14)
**Status:** completed
**Depends on:** Chapter 1

- [x] Create `src/main/java/com/javaducker/server/ingestion/ReladomoFinderParser.java` (~200 lines) — `@Component`. Regex patterns: `(\w+)Finder\.(\w+)\(\)\.(\w+)\(` for attribute ops, `(\w+)Finder\.(\w+)\(\)\.(\w+)\(\)\.(\w+)\(` for relationship nav, `.deepFetch(XFinder.y())` for deep fetches. Methods: `parseFinderUsages(javaText, fileName)` -> `List<FinderUsage>`, `parseDeepFetchUsages(javaText, fileName)` -> `List<DeepFetchUsage>`
- [x] Add 2 DuckDB tables to `SchemaBootstrap` — `reladomo_finder_usage` (object_name, attribute_or_path, operation, source_file, line_number, artifact_id), `reladomo_deep_fetch` (object_name, fetch_path, source_file, line_number, artifact_id). Indexes on object_name
- [x] Add storage + query methods to `ReladomoService` — `storeFinderUsages()`, `storeDeepFetchUsages()`, `getFinderPatterns(objectName)` (grouped by attribute+op, ranked by frequency), `getDeepFetchProfiles(objectName)` (tree of fetch paths with sources)
- [x] Hook into `IngestionWorker` — for `.java` files, parse finder + deep fetch usages, store via service
- [x] Add 2 REST endpoints — `GET /reladomo/finders/{objectName}`, `GET /reladomo/deepfetch/{objectName}`
- [x] Add 2 MCP tools — `javaducker_reladomo_finders` (object_name required), `javaducker_reladomo_deepfetch` (object_name required)
- [x] Create `src/test/java/com/javaducker/server/ingestion/ReladomoFinderParserTest.java` (~100 lines) — test finder pattern extraction, deep fetch extraction from sample Java strings

**Notes:**
> F13 and F14 share ReladomoFinderParser.java but extract different patterns. Chapter 4 can run in parallel with Chapter 3 since both only depend on Chapter 1+2.

## Chapter 5: Temporal & Runtime Config (F15 + F16)
**Status:** completed
**Depends on:** Chapter 2

- [x] Add `getTemporalInfo()` to `ReladomoService` (~60 lines) — groups objects by temporal_type, describes implied columns, default infinity date (9999-12-01 23:59:00.000), query pattern hints (as-of, equalsEdgePoint, history nav)
- [x] Create `src/main/java/com/javaducker/server/ingestion/ReladomoConfigParser.java` (~180 lines) — `@Component`. DOM parse of `MithraRuntime*.xml`: extracts ConnectionManagers (class, properties), ObjectConfigurations (objectName, connectionManager, cacheType, loadCacheOnStartup)
- [x] Create `src/main/java/com/javaducker/server/model/ReladomoConfigResult.java` (~50 lines) — records for parsed config data
- [x] Add 2 DuckDB tables to `SchemaBootstrap` — `reladomo_connection_managers` (config_file+manager_name PK, manager_class, properties JSON, artifact_id), `reladomo_object_config` (object_name+config_file PK, connection_manager, cache_type, load_cache_on_startup, artifact_id)
- [x] Hook config parsing into `IngestionWorker` — detect `MithraRuntime` root element, delegate to `ReladomoConfigParser`, store via service
- [x] Add `getConfig(objectName)` to `ReladomoService` — joins object_config with connection_managers. If objectName null, returns full topology
- [x] Add 2 REST endpoints — `GET /reladomo/temporal`, `GET /reladomo/config?objectName=X`
- [x] Add 2 MCP tools — `javaducker_reladomo_temporal` (no required params), `javaducker_reladomo_config` (object_name optional)

**Notes:**
> F15 is purely a query on existing reladomo_objects data. F16 is independent (new parser + new tables). Both can be worked in parallel. Chapter 5 can run in parallel with Chapters 3+4.

---

## Agent Spawn Plan

- Chapter 1 steps 1-2 (model + parser) -> parallel agents
- Chapter 1 steps 3-4 (schema tables) -> single agent
- Chapter 3 steps 1-2 (schema view) + steps 2-3 (classification) -> parallel agents
- Chapter 4 step 1 (finder parser) -> single agent
- Chapter 5 steps 1 (temporal) + steps 2-3 (config parser) -> parallel agents
- Chapters 3, 4, 5 can all spawn in parallel after Chapter 2 completes

## Risks

- `ReladomoService.java` may exceed 500 lines across all chapters — split into `ReladomoQueryService` + `ReladomoStorageService` if needed
- `JavaDuckerMcpServer.java` (473 lines + 9 tools) — keep tool lambdas compact with direct `httpGet` calls, target ~540 lines max. If over 500, extract Reladomo tool registration into a helper method
- Generated code classification is best-effort if XML files haven't been ingested before Java files — acceptable for incremental indexing

## Open Questions

- Should the parser handle `MithraClassList.xml` to auto-discover object XMLs?
- For mixed Reladomo+JPA projects, should generated-code filter be opt-in or auto-detected?
- Should `javaducker_reladomo_graph` support filtering by cardinality or temporal type?
