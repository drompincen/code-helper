# JavaDucker: Reladomo/Mithra Support — Objectives Plan

## Problem

Codebases built on Reladomo (Goldman Sachs' ORM) store their domain model truth in XML definitions, not Java code. Generic code indexing treats these as opaque text, missing the rich structure: object-table mappings, typed attributes, relationship graphs, temporal semantics, and the generated-vs-handwritten code boundary. Claude Code working on a Reladomo codebase today has to piece this together manually from scattered files — slow, error-prone, and context-heavy.

## Goal

Make JavaDucker Reladomo-native: parse the XML model, build a queryable domain graph, and expose tools that let Claude reason about data objects, relationships, SQL schemas, and query patterns in one call — without reading generated code or guessing at table structures.

## Constraints

- Java only — no new Maven dependencies (XML parsing via built-in javax.xml / org.w3c.dom)
- All processing local — no network calls
- Existing interfaces must not break
- Must handle real-world Reladomo projects: hundreds of XML definitions, deep relationship chains, bitemporal and non-temporal objects mixed together

---

## Feature Overview

### Phase 1 — Foundation (must ship together)

#### F9: MithraObject XML Parser
**Objective**: Parse `*MithraObject.xml` and `*MithraInterface.xml` files into a structured in-memory model during ingestion.

**What it unlocks**: Every other Reladomo feature depends on the parsed model. Without this, we're doing string matching on XML.

**Key extractions**:
- Object name, package, table name, object type (transactional, read-only, embedded-value)
- Attributes: name, javaType, columnName, nullable, primaryKey, maxLength, trim, truncate
- Relationships: name, cardinality (one-to-one, one-to-many, many-to-many), relatedObject, reverseRelationshipName, parameters, join expression
- Indices: name, columns, unique flag
- Temporal type: none, businessDate only, processingDate only, bitemporal
- Source attribute: name, type (for multi-schema routing)
- Superclass info and interface implementations

**Storage**: New DuckDB tables to persist parsed model:
- `reladomo_objects` — one row per MithraObject
- `reladomo_attributes` — one row per attribute
- `reladomo_relationships` — one row per relationship
- `reladomo_indices` — one row per index

**Integration**: During ingestion of `.xml` files, detect Reladomo XML by root element (`<MithraObject>`, `<MithraInterface>`), parse and store in addition to normal text indexing.

#### F10: Relationship Graph
**Objective**: Build a queryable graph of all Reladomo objects and their relationships, enabling Claude to navigate the domain model without reading XML.

**MCP tools**:
- `javaducker_reladomo_relationships` — given an object name, return all direct relationships (name, cardinality, target object, reverse name)
- `javaducker_reladomo_graph` — given an object name and depth, return the transitive relationship graph (breadth-first traversal up to N hops)
- `javaducker_reladomo_path` — given two object names, find the shortest relationship path between them

**Why this matters**: In a 200-object domain model, "how does Order connect to Currency?" is a question that takes a developer minutes to trace. This answers it in one call.

---

### Phase 2 — SQL & Search Quality

#### F11: Schema View
**Objective**: Derive the implied SQL DDL from parsed MithraObject definitions so Claude can understand table structures without accessing a database.

**MCP tool**: `javaducker_reladomo_schema` — given an object name, return:
- CREATE TABLE DDL with column names, SQL types (mapped from Java types), NOT NULL constraints
- Primary key definition
- Index definitions
- Temporal columns added automatically based on temporal type:
  - processingDate: `IN_Z TIMESTAMP`, `OUT_Z TIMESTAMP`
  - businessDate: `FROM_Z TIMESTAMP`, `THRU_Z TIMESTAMP`
  - bitemporal: all four

**Type mapping**: boolean->BIT, int->INTEGER, long->BIGINT, double->DOUBLE, float->REAL, String->VARCHAR(maxLength), Timestamp->TIMESTAMP, Date->DATE, BigDecimal->DECIMAL, byte[]->VARBINARY

#### F12: Generated vs Hand-Written Detection
**Objective**: Automatically classify Reladomo artifacts as generated or hand-written so search results prioritize business logic over boilerplate.

**Detection patterns**:
- Generated: `*Abstract.java`, `*Finder.java`, `*List.java`, `*ListAbstract.java`, `*DatabaseObject.java`, `*DatabaseObjectAbstract.java`, `*Data.java`
- Hand-written: concrete subclasses (e.g., `Order.java` extends `OrderAbstract.java`)

**Integration**:
- Tag artifacts during ingestion with `reladomo_type` metadata: `generated`, `hand-written`, `xml-definition`, `config`, `none`
- Add `reladomo_type` column to `artifacts` table
- `javaducker_search` gains optional `exclude_generated` parameter (default true for Reladomo projects)
- `javaducker_reladomo_object_files` — given an object name, return all associated files grouped by type (xml definition, abstract class, concrete class, finder, list, etc.)

---

### Phase 3 — Usage Patterns

#### F13: Finder Pattern Index
**Objective**: Parse how domain objects are queried in hand-written code so Claude understands access patterns.

**What to parse**:
- `*Finder.<attr>().eq/greaterThan/in/...()` — attribute-level filter operations
- `*Finder.<rel>().<attr>()...` — relationship navigation in queries
- `Operation` composition: `.and()`, `.or()` — compound query patterns

**Storage**: `reladomo_finder_usage` table — object_name, attribute_or_path, operation, source_file, line_number

**MCP tool**: `javaducker_reladomo_finders` — "how is Order queried?" returns ranked list of query patterns with frequency and source locations

#### F14: Deep Fetch Profile Extraction
**Objective**: Identify which relationships are deep-fetched together, revealing the intended data access patterns and N+1 prevention strategy.

**What to parse**:
- `list.deepFetch(OrderFinder.orderItem())` — direct deep fetch
- Chained: `OrderFinder.orderItem().product()` — multi-level deep fetch
- `addOrderBy`, `setMaxObjectsToRetrieve` alongside deep fetches — full query profile

**MCP tool**: `javaducker_reladomo_deepfetch` — "what gets loaded with Order?" returns the deep fetch tree with source locations

---

### Phase 4 — Advanced Model Understanding

#### F15: Temporal Model Documentation
**Objective**: Auto-generate a temporal model reference so Claude understands which objects are temporal, what that implies for queries, and how milestoning works.

**MCP tool**: `javaducker_reladomo_temporal` — returns:
- Classification of all objects by temporal type (none, business, processing, bitemporal)
- For temporal objects: the timestamp columns, the default infinity date, processing history behavior
- Temporal query patterns: as-of queries, equalsEdgePoint, history navigation

#### F16: MithraRuntime Config Parser
**Objective**: Parse `MithraRuntime*.xml` configuration files to understand the runtime topology.

**What to extract**:
- Connection manager class and properties per source
- Object-to-connection-manager mapping (which objects use which database)
- Cache strategy per object: full, partial, none
- Replication config, notification config

**MCP tool**: `javaducker_reladomo_config` — "which DB does Order connect to?" "what's the cache strategy for Position?"

---

## New MCP Tools Summary

| Tool | Phase | Purpose |
|------|-------|---------|
| `javaducker_reladomo_relationships` | 1 | Direct relationships for an object |
| `javaducker_reladomo_graph` | 1 | Transitive relationship graph (N hops) |
| `javaducker_reladomo_path` | 1 | Shortest path between two objects |
| `javaducker_reladomo_schema` | 2 | Derived SQL DDL for an object |
| `javaducker_reladomo_object_files` | 2 | All files associated with an object |
| `javaducker_reladomo_finders` | 3 | Query patterns for an object |
| `javaducker_reladomo_deepfetch` | 3 | Deep fetch profiles for an object |
| `javaducker_reladomo_temporal` | 4 | Temporal classification and patterns |
| `javaducker_reladomo_config` | 4 | Runtime config: connections, caches |

## Success Criteria

After Phase 1+2, Claude should be able to:
1. Answer "what does Order look like?" — attributes, types, table, relationships — in one tool call
2. Answer "how does Order connect to Currency?" — relationship path — in one tool call
3. Answer "what's the schema for OrderItem?" — full DDL — in one tool call
4. Search for business logic without wading through generated code

After Phase 3+4, Claude should additionally:
1. Know how objects are typically queried (finder patterns, deep fetch profiles)
2. Understand temporal semantics without being told
3. Know which database backs each object

## Open Questions

- Should the parser handle Reladomo's XML generation config (`MithraClassList.xml`) to discover all object XMLs automatically?
- Should we detect and parse `mithra-cache-replicator` config for distributed cache topology?
- For projects mixing Reladomo with JPA/Hibernate, should the generated-code filter be opt-in or auto-detected?
- Should `javaducker_reladomo_graph` support filtering by cardinality (e.g., only one-to-many) or temporal type?
