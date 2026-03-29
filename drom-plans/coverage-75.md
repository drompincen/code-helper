---
title: Code Coverage to 75%
status: completed
created: 2026-03-28
updated: 2026-03-28
current_chapter: 1
loop: true
loop_target: 75.0
loop_metric: instruction_coverage_percent
loop_max_iterations: 3
---

# Plan: Code Coverage to 75%

Raise JaCoCo instruction coverage from **67.9%** to **≥75%** using a closed-loop approach.

**Baseline (2026-03-28):** 15,122 / 22,277 instructions covered (67.9%), 386 tests passing.
**Need:** ~1,580 more instructions covered (16,708 / 22,277).

## Priority targets (testable classes, by uncovered instructions)

| Class | Coverage | Uncovered | Plan |
|-------|----------|-----------|------|
| ReladomoService | 56.5% | 346 | Expand: store/query edge cases |
| IngestionWorker | 74.1% | 323 | Expand: error paths, HNSW build |
| TextExtractor | 76.2% | 294 | Expand: RTF, more ODF/EPUB edges |
| SearchService | 76.4% | 222 | Expand: HNSW path, edge cases |
| CoChangeService | 59.2% | 216 | Expand: DB-backed buildIndex |
| GitBlameService | 57.3% | 195 | Expand: DB-backed blame, edges |
| ReladomoConfigParser | 20.1% | 195 | New: parse config XML tests |
| SessionIngestionService | 83.0% | 177 | Expand: search, edge cases |

## Chapter 1: Service Coverage Push (target: ≥75%)
**Status:** completed
**Depends on:** none

Parallel agents targeting the 8 classes above. Each agent writes tests to cover the uncovered branches.

- [ ] ReladomoService: test storeReladomoObject edge cases, update/delete paths — target 56% → 75%
- [ ] CoChangeService + GitBlameService: DB-backed tests for buildCoChangeIndex with real git, blame edge cases — target 59%/57% → 75%
- [ ] SearchService: test HNSW search path, extractEmbedding variants, empty results — target 76% → 85%
- [ ] ReladomoConfigParser: test XML config parsing for connection managers, object configs — target 20% → 60%
- [ ] IngestionWorker + TextExtractor + SessionIngestionService: expand edge case coverage — target 74%/76%/83% → 85%
- [ ] Write all tests, run `mvn verify`, measure coverage
- [ ] If ≥75%, mark plan completed

## Closed-Loop Protocol

1. Run `mvn verify -B`, parse `jacoco.csv` → instruction coverage %
2. If ≥75%: **STOP**, mark plan completed
3. If <75%: identify remaining gaps, write targeted tests, re-measure (max 2 extra iterations)

## Exclusions (CLI/UI — 0% coverage, low ROI)
IndexCommand, StatsPanel, InteractiveCli, CommandDispatcher, CatCommand, ApiClient,
JavaDuckerClient (and nested Cmd classes), ResultsFormatter, ProgressBar, SearchCommand, StatusCommand

---
