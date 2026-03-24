# Content Classification & Tagging — Ideas

The current pipeline stores text, chunks it, and embeds it — but everything is flat. There's no way to ask
"show me all decisions across my notes" or "what ideas have I captured about caching" or "find all action
items from last quarter". These ideas would fix that.

---

## 1. What to Extract (the taxonomy)

### Salient Point Types
The highest-value layer. Rather than just making content searchable, extract the *kind of content* each
passage represents:

| Type | Description | Example signal |
|------|-------------|----------------|
| `DECISION` | A choice was made between alternatives | "We chose X over Y because..." |
| `IDEA` | Hypothesis, proposal, or brainstorm | "What if we...", "Consider..." |
| `QUESTION` | Open question, unknown, TBD | "How should we...", "TBD:", "?" |
| `ACTION` | Task assigned to someone | "@person will...", "TODO:", "Action:" |
| `RISK` | Concern, caveat, potential problem | "Risk:", "Watch out for...", "May cause..." |
| `INSIGHT` | Key finding or observation | "Key insight:", "Importantly...", "Note:" |
| `CONSTRAINT` | Hard requirement or boundary | "Must...", "Cannot...", compliance language |
| `STATUS` | Current state report | "We are currently...", "As of today..." |

Storing these per-chunk (not per-document) means you can query: *"find all DECISION points across all files
that mention Kafka"*.

---

### Concepts & Entities
Named things that appear across files — the vocabulary of your domain:

- **Technical concepts**: `Kafka`, `Redis`, `OAuth`, `circuit breaker`
- **System names**: `OrderService`, `PaymentGateway`, `AuthMiddleware`
- **People & teams**: `@drom`, `mobile team`, `legal`
- **Time markers**: dates, sprints, quarters ("Q2", "sprint 14")
- **Topics/themes**: `performance`, `security`, `tech debt`, `caching`

Concepts are the connective tissue between documents. The same concept appearing in a design doc, a Slack
thread, and a retro is a signal that those three things are related — even if they never cross-reference
each other.

---

### Document-Level Classification
A single label per document describing what it *is*:

| Class | Examples |
|-------|---------|
| `ADR` | Architecture decision records |
| `DESIGN_DOC` | Design docs, RFCs, proposals |
| `MEETING_NOTES` | Meeting notes, standups, retros |
| `THREAD` | Slack exports, email threads |
| `CODE` | Source files |
| `SCRATCH` | Working notes, scratchpads |
| `REFERENCE` | Docs, READMEs, wikis |
| `PLAN` | Roadmaps, sprint plans, milestones |

This enables: *"search only in ADRs"* or *"find all PLAN documents that mention the auth rewrite"*.

---

### Tags (free-form facets)
Lightweight multi-value labels, less structured than doc_type but more than raw keywords:

- Auto-generated from concepts + content (e.g., `#caching`, `#kafka`, `#q2-2026`)
- Manually assigned (user can add tags at upload time or after)
- Tags are additive — a document can have 10 tags
- Enable: *"filter search to #performance documents"*

---

## 2. Where to Run It (pipeline placement)

### Option A: Inline during ingestion (new Step 4.5)
After embedding, before marking INDEXED, call a ClassificationService.

```
PARSING → CHUNKED → EMBEDDED → [CLASSIFYING] → INDEXED
```

**Pros:** one pipeline, simple status model, classification always present when INDEXED
**Cons:** slows ingestion, LLM cost blocks the pipeline, single point of failure

**Best for:** rule-based or local-model classification (fast, no external dependency)

---

### Option B: Post-processing worker (recommended)
Add a new status `ENRICHED` after `INDEXED`. A separate `EnrichmentWorker` polls for INDEXED artifacts and
runs classification asynchronously.

```
... → INDEXED → [ENRICHING] → ENRICHED
```

**Pros:**
- Ingestion speed unaffected
- Enrichment can be re-run without re-indexing
- Can be enabled/disabled independently
- Enrichment can be swapped (rule-based today, LLM tomorrow)
- Existing search still works before enrichment completes

**Cons:** artifacts are searchable before classification is ready; need to handle partially-enriched state

**Best for:** LLM-based classification (Claude API call per document)

---

### Option C: On-demand endpoint
No automatic classification. Expose `/api/enrich/{artifactId}` and `/api/enrich-all`.

**Pros:** zero ingestion overhead, user controls cost/timing
**Cons:** classification never happens unless manually triggered; not suitable for large automated indexing

**Best for:** low-volume / high-value documents (design docs, ADRs) where you want explicit control

---

## 3. How to Classify (implementation approaches)

### Approach 1: LLM-based (Claude API)
Call Claude with each document's extracted text. Prompt it to return structured JSON:

```json
{
  "doc_type": "ADR",
  "tags": ["kafka", "async", "fulfillment", "q1-2026"],
  "salient_points": [
    { "type": "DECISION", "text": "Chose Kafka over direct calls for order fulfillment" },
    { "type": "RISK",     "text": "Eventual consistency — need dead-letter queue handling" },
    { "type": "CONSTRAINT","text": "Revisit if message volume exceeds 50k/day" }
  ],
  "concepts": ["Kafka", "OrderService", "fulfillment", "dead-letter queue"]
}
```

**Pros:** highest accuracy, handles nuance and implicit meaning, no schema design for rules
**Cons:** cost (esp. for large codebases), latency, external dependency
**Sweet spot:** notes, design docs, ADRs — high-value, low-volume documents

For code files, a lighter heuristic approach is better since structure is predictable.

---

### Approach 2: Heuristic / rule-based
Derive classification from signals already present in the content:

**Doc type from path:**
```
decisions/  → ADR
plans/      → PLAN
retros/     → MEETING_NOTES
*.java      → CODE
```

**Salient point patterns (regex on chunk text):**
```
"We (chose|decided|selected|went with)" → DECISION
"TODO:|Action item:|@\w+ will"          → ACTION
"Risk:|Concern:|Watch out"             → RISK
"What if|Consider|Proposal:"          → IDEA
"Open question:|TBD:|How should"      → QUESTION
```

**Concept extraction:**
- Extract capitalized multi-word phrases (PascalCase → system names)
- Extract `@mentions` → people
- Regex for known patterns (`#sprint-\d+`, `Q[1-4]-\d{4}`)
- TF-IDF of chunk keywords relative to corpus average → topic keywords

**Pros:** fast, free, explainable, no external service, works offline
**Cons:** brittle, English-centric, misses implicit meaning, needs maintenance
**Sweet spot:** code files, structured notes with known conventions

---

### Approach 3: Embedding-based clustering (post-processing, no LLM)
Group documents by topic using their existing embeddings. Assign topic labels from cluster centroids.

- K-means or HDBSCAN on document-level embeddings (average chunk vectors)
- Label each cluster by its most representative terms (top TF-IDF words near centroid)
- Store `cluster_id` and `cluster_label` per artifact

**Pros:** free (uses existing embeddings), discovers emergent topics, scales well
**Cons:** unsupervised → labels are imprecise, need to choose K, clusters drift as corpus grows
**Sweet spot:** large code corpora where you want to discover topic groups you didn't know existed

---

### Approach 4: Hybrid (recommended default)
- **Doc type**: path heuristics (free, instant)
- **Tags**: TF-IDF keywords + path-derived tags (free, fast)
- **Salient points**: LLM for notes/docs, regex for code (balanced cost)
- **Concepts**: regex for proper nouns + LLM for abstract concepts in key docs
- **Clustering**: run periodically (nightly/weekly) as a background job

---

## 4. Schema Changes

```sql
-- Per-document: what kind of thing is this?
CREATE TABLE artifact_classifications (
    artifact_id  VARCHAR PRIMARY KEY,
    doc_type     VARCHAR,           -- ADR, DESIGN_DOC, CODE, etc.
    confidence   FLOAT,
    method       VARCHAR,           -- 'llm', 'heuristic', 'hybrid'
    classified_at TIMESTAMP
);

-- Per-document: free-form labels
CREATE TABLE artifact_tags (
    artifact_id  VARCHAR NOT NULL,
    tag          VARCHAR NOT NULL,
    tag_type     VARCHAR,           -- 'topic', 'entity', 'person', 'time', 'manual'
    source       VARCHAR,           -- 'llm', 'tfidf', 'path', 'user'
    PRIMARY KEY (artifact_id, tag)
);

-- Per-chunk: extracted salient points
CREATE TABLE artifact_salient_points (
    point_id     VARCHAR PRIMARY KEY,
    artifact_id  VARCHAR NOT NULL,
    chunk_id     VARCHAR,
    point_type   VARCHAR NOT NULL,  -- DECISION, IDEA, ACTION, RISK, etc.
    point_text   VARCHAR NOT NULL,
    source       VARCHAR,           -- 'llm', 'regex'
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Concept index: named things mentioned in documents
CREATE TABLE artifact_concepts (
    concept_id   VARCHAR PRIMARY KEY,
    artifact_id  VARCHAR NOT NULL,
    concept      VARCHAR NOT NULL,
    concept_type VARCHAR,           -- 'system', 'person', 'topic', 'term'
    mention_count INTEGER,
    chunk_ids    VARCHAR[]          -- which chunks mention it
);

-- Cross-document concept links (built by post-processing)
CREATE TABLE concept_links (
    concept      VARCHAR NOT NULL,
    artifact_a   VARCHAR NOT NULL,
    artifact_b   VARCHAR NOT NULL,
    strength     FLOAT,             -- overlap score
    PRIMARY KEY (concept, artifact_a, artifact_b)
);
```

---

## 5. New Search & Query Capabilities

### Filtered search (extend existing `/api/search`)
```json
{
  "phrase": "caching strategy",
  "mode": "semantic",
  "filters": {
    "doc_type": ["ADR", "DESIGN_DOC"],
    "tags": ["#performance"],
    "point_types": ["DECISION"]
  }
}
```

### New endpoints

| Endpoint | Returns |
|----------|---------|
| `GET /api/concepts` | All known concepts across corpus, with mention counts |
| `GET /api/concepts/{concept}/documents` | All docs mentioning a concept |
| `GET /api/salient-points?type=DECISION` | All decision points across all docs |
| `GET /api/salient-points?type=IDEA&tag=caching` | Ideas tagged with 'caching' |
| `GET /api/tags` | All tags with document counts (facet browse) |
| `GET /api/threads` | Documents linked by shared concepts (implicit threads) |
| `POST /api/enrich/{artifactId}` | Trigger classification for a specific doc |
| `POST /api/enrich-all` | Re-run classification on all INDEXED artifacts |

### New MCP tools (for Claude integration)

| Tool | Description |
|------|-------------|
| `javaducker_find_decisions` | Search DECISION salient points across all docs |
| `javaducker_find_ideas` | Search IDEA salient points |
| `javaducker_browse_concepts` | List all concepts and which docs they appear in |
| `javaducker_find_by_tag` | Get documents matching a tag |
| `javaducker_get_salient_points` | Get all salient points for a given artifact |
| `javaducker_related_documents` | Find docs linked by shared concepts |

---

## 6. Use Case: "Thread" Reconstruction

A thread is a set of documents related by a shared concept or timeline — like all notes, decisions, and
code related to "the auth rewrite". Currently this has to be reconstructed manually.

**With concept links:**
- Search `concept_links` for documents sharing `AuthMiddleware` or `auth rewrite`
- Sort by `artifacts.created_at`
- Return as an ordered timeline: the thread of work

**MCP tool: `javaducker_get_thread`**
```
Input: "auth rewrite"
Output: Timeline of related documents with their type, date, and salient points
```

This lets Claude reconstruct the full history of a topic in one shot.

---

## 7. Incremental Enrichment Strategy

Don't block indexing on enrichment. Keep them decoupled:

1. Ingest as today: fast, no LLM cost
2. `EnrichmentWorker` polls `status = INDEXED` artifacts
3. Runs classification (heuristic or LLM based on doc_type)
4. Updates `artifact_classifications`, `artifact_tags`, `artifact_salient_points`, `artifact_concepts`
5. Sets status to `ENRICHED`
6. Search works on both INDEXED and ENRICHED artifacts; classification filters only apply to ENRICHED

Priority queue: enrich notes/docs first (high signal), code last (lower classification value).

---

## 8. Implementation Order (suggested)

1. **Schema** — add the four new tables (non-breaking, existing search unaffected)
2. **Heuristic classifier** — doc_type from path, tags from TF-IDF, regex salient points (fast, no cost)
3. **`EnrichmentWorker`** — post-processing pipeline, polls INDEXED artifacts
4. **Filtered search** — extend `/api/search` with doc_type/tag filters
5. **New query endpoints** — `/api/salient-points`, `/api/concepts`, `/api/tags`
6. **New MCP tools** — `javaducker_find_decisions`, `javaducker_find_ideas`, etc.
7. **LLM enrichment** — optional Claude API step for high-value doc types (ADR, DESIGN_DOC, MEETING_NOTES)
8. **Concept linking** — nightly job to build `concept_links` across corpus
9. **Thread reconstruction** — `javaducker_get_thread` MCP tool
