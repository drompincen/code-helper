---
title: Session Transcript Indexing
status: in-progress
created: 2026-03-28
updated: 2026-03-28
current_chapter: 1
---

# Plan: Session Transcript Indexing

Index Claude Code conversation transcripts from `~/.claude/projects/` so that past sessions are searchable. When Claude asks "what did we decide about auth last week?" it searches actual session history, not just MEMORY.md summaries.

## Chapter 1: Transcript Parser & Data Model
**Status:** in-progress
**Depends on:** none

- [ ] Create `SessionTranscriptParser.java` in `server/ingestion/` (~200 lines) — parse JSONL conversation files from `~/.claude/projects/`. Extract: role (user/assistant/tool), text content, tool names, timestamps, session ID. Skip binary/image content
- [ ] Create `SessionTranscript` record in `server/model/` — fields: sessionId, projectPath, messageIndex, role, content, toolName, timestamp, tokenEstimate
- [ ] Add `session_transcripts` table to `SchemaBootstrap` — `session_id VARCHAR NOT NULL, project_path VARCHAR, message_index INTEGER, role VARCHAR, content VARCHAR, tool_name VARCHAR, timestamp TIMESTAMP, token_estimate INTEGER, PRIMARY KEY (session_id, message_index)`
- [ ] Add `session_decisions` table — `session_id VARCHAR NOT NULL, decision_text VARCHAR, context VARCHAR, decided_at TIMESTAMP, tags VARCHAR` — extracted decisions/conclusions from sessions
- [ ] Add index: `idx_transcripts_session` on session_transcripts(session_id), `idx_transcripts_role` on session_transcripts(role), `idx_decisions_tags` on session_decisions(tags)
- [ ] Write `SessionTranscriptParserTest` — test JSONL parsing, skip binary, handle malformed lines, empty files

**Notes:**
> Claude Code stores sessions as JSONL in ~/.claude/projects/<project-hash>/. Each line is a JSON object with role, content, tool_use fields. The parser should be tolerant of format changes.

## Chapter 2: Ingestion & Chunking
**Status:** pending
**Depends on:** Chapter 1

- [ ] Create `SessionIngestionService.java` in `server/service/` (~180 lines) — scan a project directory for session files, parse each, chunk assistant messages (user messages are usually short), store in DuckDB. Dedup by session_id + message_index
- [ ] Add session-aware chunking to `Chunker` or create `SessionChunker` — chunk long assistant responses, preserve tool call boundaries as chunk breaks, tag chunks with session_id
- [ ] Hook into existing embedding pipeline — session chunks get TF-IDF embeddings like any other artifact, stored in `chunks` table with `source_type = 'session'`
- [ ] Add incremental ingestion — track last-indexed session file modification time, only re-parse changed files
- [ ] Write `SessionIngestionServiceTest` — test dedup, incremental skip, chunking boundaries

**Notes:**
> Sessions can be large (100K+ tokens). Only index assistant and user messages — skip tool_result payloads (they're the code itself, already indexed). Decision extraction happens in Chapter 4.

## Chapter 3: REST Endpoints & MCP Tools
**Status:** pending
**Depends on:** Chapter 2

- [ ] Add REST endpoints to `JavaDuckerRestController`: `POST /api/index-sessions` (body: `{projectPath, maxSessions?}`), `GET /api/sessions` (list indexed sessions with date, token count), `GET /api/session/{sessionId}` (full transcript), `POST /api/search-sessions` (body: `{phrase, mode, maxResults}` — search only session content)
- [ ] Add MCP tools to `JavaDuckerMcpServer.java`: `javaducker_index_sessions` (index sessions from a project path), `javaducker_search_sessions` (search past conversations — phrase, mode), `javaducker_session_decisions` (list decisions extracted from sessions, optionally filtered by tag)
- [ ] Add `javaducker_session_context` MCP tool — given a topic/query, return a compact context bundle: relevant session excerpts + any related MEMORY.md entries + related artifacts. One call to get full historical context
- [ ] Write integration test — index sample session JSONL, search it, verify results

**Notes:**
> `javaducker_session_context` is the high-value tool — it's what Claude calls when it needs to understand history. Keep the response compact: excerpts, not full transcripts.

## Chapter 4: Decision Extraction & Session Summaries
**Status:** pending
**Depends on:** Chapter 3

- [ ] Add `POST /api/extract-session-decisions` endpoint — accepts sessionId + list of decisions (text, context, tags). Stores in `session_decisions` table. Designed to be called by Claude after reading a session
- [ ] Add `javaducker_extract_decisions` MCP tool — write side for decision storage
- [ ] Add `javaducker_recent_decisions` MCP tool — return decisions from last N sessions, filterable by tag/topic
- [ ] Create `scripts/index-sessions.sh` — helper script that finds the project path in `~/.claude/projects/` and calls the index endpoint. Can be wired as a SessionStart hook for auto-indexing
- [ ] Write tests for decision storage and retrieval

**Notes:**
> Decision extraction is LLM-driven — Claude reads the transcript and identifies decisions. The MCP tool just stores what Claude extracts. This pairs with the content intelligence enrichment pipeline (O3).

---

## Risks
- Session JSONL format may change across Claude Code versions — parser should be defensive
- Large session files (100K+ tokens) may need streaming parse, not load-all-at-once
- Privacy: transcripts may contain secrets typed by user — warn in docs, don't index `.env` content

## Open Questions
- Should session indexing happen automatically via a SessionStart hook, or only on demand?
- Should tool_result content be indexed (it's often code that's already indexed separately)?
