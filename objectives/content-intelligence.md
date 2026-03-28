# Content Intelligence — Objectives

JavaDucker indexes both code and non-code content: ideas, design threads, working notes, ticket statuses, plans, decisions, brainstorms, retros. This content is alive — plans get revised, statuses update weekly, ideas get superseded, decisions get reversed. The challenge is not just finding content, but finding the **current truth**: the latest plan, the latest status, the thinking that hasn't been contradicted.

## Execution Model

**All post-intake intelligence — classification, tagging, synthesis, pruning, freshness ranking, concept tracking — is performed by Claude Code, triggered by hooks, running asynchronously.** There is no separate worker process, no background service, no cron job. Claude Code hooks fire after content intake (upload, re-index) and at session start. The hook launches Claude Code in async mode to process the enrichment queue. This means:

- **Zero infrastructure:** no daemons, no queues, no scheduler — just Claude Code and DuckDB
- **LLM-native:** every decision (classify, tag, detect supersession, decide what to prune) is made by Claude, not regex or heuristics
- **Session-aligned:** enrichment runs when the user is working, so the index is fresh when it matters
- **Hook-triggered:** intake hooks queue artifacts for processing; session-start hooks drain the queue in background
- **Non-blocking:** async execution means the user's session is never waiting on enrichment

---

## O1: Content Classification & Tagging

Classify and tag every ingested artifact so it can be filtered, grouped, and prioritized.

- **Doc types:** ADR, design doc, plan, meeting notes, thread, scratch/working notes, ticket/status, code, reference
- **Tags:** auto-derived from content (topics, systems, people, time markers) plus user-supplied
- **Salient points:** per-chunk extraction of decisions, ideas, questions, actions, risks, insights, constraints, status updates

**Execution:** Claude Code performs classification via hook after each intake. No rule engine — Claude reads the content and decides the doc type, tags, and salient points directly.

**Benefit:** Turns a flat pile of indexed text into a structured knowledge base. Users can ask "show me all plans" or "find decisions about Kafka" instead of guessing keywords.

**Why:** Code search alone misses the 80% of project knowledge that lives outside source files. Without classification, non-code content is just noise in search results — you can't distinguish a current plan from an abandoned brainstorm.

---

## O2: Temporal Awareness & Freshness Ranking

Track when content was written, when it was last updated, and whether it has been superseded.

- Every artifact carries a temporal signal: explicit dates from content, file modification time, ingestion time
- When multiple artifacts cover the same topic, rank newer content higher
- Detect supersession: a newer plan on the same topic demotes the older one; a decision that reverses a prior decision marks the prior as stale
- Surface staleness: flag content that hasn't been updated in a configurable window (e.g., a status report older than 2 weeks)

**Execution:** Claude Code evaluates temporal relationships during the async post-intake hook pass. When new content arrives, Claude compares it against existing artifacts on the same topic and updates freshness/supersession metadata in DuckDB.

**Benefit:** Users always get the current state of thinking, not yesterday's abandoned draft. "What's the plan for auth?" returns the latest revision, not the first brainstorm.

**Why:** Knowledge bases rot. Without freshness ranking, old content accumulates and buries current thinking. Teams waste time acting on outdated plans or re-debating settled decisions because they found the wrong version.

---

## O3: Hook-Driven Async Post-Processor

The central engine. Claude Code, triggered by hooks, performs all enrichment asynchronously.

- **Intake hook:** fires after content upload/re-index. Queues new or updated artifacts for processing (e.g., writes artifact IDs to an enrichment queue table in DuckDB)
- **Session-start hook:** fires when Claude Code starts. Launches an async Claude Code pass that drains the queue — classifies (O1), ranks freshness (O2), prunes and synthesizes (O5), updates concept lifecycles (O6)
- **Incremental:** only processes what's queued, skips already-enriched artifacts unless their source was re-ingested
- **Non-blocking:** runs in background via Claude Code async mode — the user's session is unaffected
- **All decisions are LLM-native:** Claude reads content, compares against existing artifacts, and decides classification, tags, supersession, what to prune — no regex, no heuristics, no rules engine

**Benefit:** The knowledge base stays organized automatically. No manual tagging, no curation burden. Every session leaves the index smarter than it found it. Zero infrastructure beyond Claude Code and DuckDB.

**Why:** Rule-based classifiers miss implicit meaning — they can't tell that a new status update contradicts last week's, or that a working note evolved into a formal design doc. LLM-driven enrichment is the only way to handle the messy, evolving nature of real project knowledge. Hook-triggered execution means it happens at exactly the right time (after intake, at session start) with zero operational overhead.

---

## O4: "Current Truth" Query Layer

Expose the enriched, temporally-aware knowledge base through search and MCP tools.

- Filtered search: by doc type, tags, salient point type, freshness
- "Latest" queries: "latest status on X", "current plan for Y" — returns the most recent, non-superseded artifact on a topic
- Concept browsing: see all concepts across the corpus, which docs mention them, and which version is current
- Timeline reconstruction: given a topic, show the evolution of thinking — from first idea through decisions to current state, with superseded content clearly marked

**Benefit:** Claude Code can answer "what's the current state of the auth rewrite?" in one tool call, pulling the latest plan, open risks, and recent status — not a list of every document that ever mentioned auth.

**Why:** The whole point of indexing non-code content is to make project knowledge accessible in-context. Without a query layer that understands freshness and supersession, you just have a better grep. With it, JavaDucker becomes the team's living memory.

---

## O5: Content Pruning & Synthesis

Keep DuckDB lean by pruning stale content and synthesizing what remains into compact, retrievable summaries.

- When the post-processor (O3) detects superseded, contradicted, or cold content, it doesn't just flag it — it **prunes** the full text and embeddings from DuckDB
- Before pruning, synthesize the artifact into a compact summary: tags, key points, dates, outcome, and the **original file path**
- The synthesis record stays in DuckDB (small, cheap) so searches still surface it with enough context to decide if the full content matters
- If Claude Code needs the full original, it reads the file from disk via the stored path — the source of truth stays on the filesystem, DuckDB only holds what's current and active
- Pruning runs as part of the hook-triggered async post-processor pass (O3): after enriching new content, Claude evaluates what's now stale and prunes it

**Execution:** Claude Code decides what to prune and what to synthesize — not a TTL rule or a date threshold. Claude reads the content, understands that "we abandoned the Redis approach" means the Redis brainstorm gets synthesized and pruned, even if it's only a week old.

**Benefit:** DuckDB stays fast and focused on current knowledge. Old content doesn't pollute search results or consume memory. But nothing is lost — the original files remain on disk, and synthesis records provide a breadcrumb trail back to them.

**Why:** Without pruning, the index grows monotonically and search quality degrades over time. A 6-month-old brainstorm about caching has no business ranking alongside this week's design doc. Synthesis lets you keep the signal (tags, summary, path) without the cost (full text, chunks, embeddings). And since Claude Code can read files directly, the full content is always one `Read` call away when needed.

---

## O6: Concept Lifecycle Tracking

Track how concepts evolve across documents over time.

- A concept (e.g., "Kafka fulfillment pipeline") appears in brainstorms, gets formalized in a design doc, referenced in tickets, and reported on in status updates
- Link these into a concept timeline showing the arc from idea to implementation to current state
- Detect when a concept goes cold (no new mentions in N weeks) or is explicitly abandoned
- Surface contradictions: two active documents making conflicting claims about the same concept
- When a concept is fully resolved or abandoned, its detailed records become candidates for pruning (O5) — synthesize and archive

**Execution:** Concept lifecycle updates are part of the async post-processor pass (O3). When Claude processes a new artifact, it identifies concepts, links them to existing concept timelines, and detects when a concept has gone cold or been contradicted — all via hook-triggered async Claude Code execution.

**Benefit:** Provides the "story" behind any concept — not just where it's mentioned, but where it came from, where it is now, and whether it's still alive.

**Why:** Real projects don't have a single source of truth per topic. Knowledge is scattered across threads, notes, plans, and retros. Concept lifecycle tracking stitches these fragments into a coherent narrative that Claude Code can use to give informed, current answers.

---

## O7: MCP Tools & CLI for Content Intelligence

JavaDucker must expose MCP tools and CLI commands so Claude Code can both **read** enriched data and **write** enrichment decisions back. The hook-driven post-processor (O3) needs write tools to do its job. The query layer (O4) needs read tools to serve results. Without these, Claude Code has no interface to JavaDucker for content intelligence — hooks fire but have nothing to call.

### Write tools (used by the async post-processor)

| Tool / CLI | Purpose |
|------------|---------|
| `javaducker_classify` | Set doc_type, confidence for an artifact |
| `javaducker_tag` | Add/replace tags on an artifact |
| `javaducker_extract_points` | Write salient points (decisions, risks, actions, etc.) for an artifact |
| `javaducker_set_freshness` | Mark an artifact as current, stale, or superseded; record what supersedes it |
| `javaducker_synthesize` | Write a synthesis record (summary + tags + file path) and prune full text/embeddings |
| `javaducker_link_concepts` | Create/update concept entries and cross-document concept links |
| `javaducker_enrich_queue` | List artifacts queued for enrichment (status = INDEXED, not yet ENRICHED) |
| `javaducker_mark_enriched` | Mark an artifact as ENRICHED after post-processing is complete |

### Read tools (used by Claude Code during normal sessions)

| Tool / CLI | Purpose |
|------------|---------|
| `javaducker_latest` | Get the most recent, non-superseded artifact on a topic — the "current truth" |
| `javaducker_find_by_type` | Find artifacts by doc_type (e.g., all plans, all ADRs) |
| `javaducker_find_by_tag` | Find artifacts matching a tag |
| `javaducker_find_points` | Search salient points by type (DECISION, RISK, ACTION, etc.) across all docs |
| `javaducker_concepts` | List all concepts with mention counts and which docs reference them |
| `javaducker_concept_timeline` | Show the evolution of a concept: all related docs ordered by time, with status |
| `javaducker_stale` | List artifacts flagged as stale or superseded, with what replaced them |
| `javaducker_synthesis` | Retrieve synthesis records (summary + file path) for pruned artifacts |

### CLI equivalents

Every MCP tool has a matching CLI subcommand via `run-client.sh` (e.g., `./run-client.sh classify --id <id> --doc-type ADR`). This allows hooks to call JavaDucker without MCP when running in script mode.

**Benefit:** Claude Code has a complete read/write interface to the content intelligence layer. The post-processor can classify, tag, prune, and synthesize through JavaDucker's API. Normal sessions can query current truth, browse concepts, and find the latest plans — all via MCP tool calls.

**Why:** Hooks trigger Claude Code, but Claude Code needs tools to act. Without MCP tools for writing enrichment data back and reading it out, the entire async post-processor model has no hands. The tools are the contract between Claude Code and JavaDucker — they define what intelligence operations are possible.

---

## Implementation Dependencies

```
O1 (classification schema)
  │
  └──► O7 (MCP tools & CLI) ──► O3 (hook-driven async post-processor)
          │                          │
          │                          ├──► O2 (temporal/freshness)
          │                          ├──► O5 (pruning & synthesis)
          │                          └──► O6 (concept lifecycle)
          │
          └──► O4 (query layer — read tools)
```

O1 provides the schema and taxonomy. O7 provides the MCP tools and CLI that Claude Code uses to read and write enrichment data — it must exist before O3 can operate. O3 is the hook-triggered Claude Code engine that uses O7's write tools to classify, tag, prune, and synthesize. O4 uses O7's read tools to expose current truth. Claude Code reads original files from disk on demand — DuckDB is the index, not the archive.
