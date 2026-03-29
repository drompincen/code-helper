---
title: Code Coverage to 65%
status: completed
created: 2026-03-28
updated: 2026-03-28
current_chapter: 1
loop: true
loop_target: 65.0
loop_metric: instruction_coverage_percent
loop_max_iterations: 5
---

# Plan: Code Coverage to 65%

Raise JaCoCo instruction coverage from **48.6%** to **≥65%** using a closed-loop approach: write tests, measure, repeat until target is met.

**Baseline (2026-03-28):** 10,627 / 21,869 instructions covered (48.6%), 186 tests passing.

**Strategy:** Target the classes with the highest uncovered instruction count first (biggest bang per test). Skip CLI classes (`JavaDuckerClient`, `InteractiveCli`, `CommandDispatcher`, etc.) — they are thin wrappers over REST calls and hard to unit-test without major infra. Focus on service/ingestion/REST classes.

## Priority targets (by uncovered instructions, descending)

| Class | Covered | Total | Gap | Instructions to cover |
|-------|---------|-------|-----|----------------------|
| ReladomoQueryService | 684 | 1507 | 45% | 823 |
| HnswIndex | 0 | 853 | 0% | 853 |
| JavaDuckerRestController | 633 | 1191 | 53% | 558 |
| TextExtractor | 668 | 1235 | 54% | 567 |
| ExplainService | 52 | 499 | 10% | 447 |
| IngestionWorker | 701 | 1249 | 56% | 548 |
| StalenessService | 43 | 307 | 14% | 264 |
| CoChangeService | 245 | 530 | 46% | 285 |
| ProjectMapService | 0 | 273 | 0% | 273 |
| FileWatcher | 13 | 282 | 5% | 269 |
| DependencyService | 0 | 127 | 0% | 127 |
| ReladomoConfigParser | 7 | 244 | 3% | 237 |
| GitBlameService | 262 | 457 | 57% | 195 |
| ImportParser | 91 | 175 | 52% | 84 |

**Need:** ~3,550 more instructions covered to reach 65% (14,215 / 21,869).

## Chapter 1: High-Impact Service Tests (target: ~55%)
**Status:** completed
**Depends on:** none

Write tests for the services with highest uncovered instruction count that can be tested with DuckDB in-memory:

- [ ] `ExplainServiceTest` — expand: test `explain()` and `explainByPath()` with a real DuckDB + seeded artifacts (not just static helpers). Cover classification, tags, salient_points, related_artifacts sections. Target: 10% → 70%
- [ ] `StalenessServiceTest` — expand: test `checkStaleness()` and `checkAll()` with real DuckDB + temp files on disk. Cover file-exists, file-missing, file-modified paths. Target: 14% → 70%
- [ ] `DependencyServiceTest` — new: test `getDependencies()` and `getDependents()` with seeded `artifact_imports` data. Target: 0% → 80%
- [ ] `CoChangeServiceTest` — expand: test `buildCoChangeIndex()` and `getRelatedFiles()` with real DuckDB. Target: 46% → 70%
- [ ] `ProjectMapServiceTest` — new: test `getProjectMap()` with seeded artifacts. Target: 0% → 60%

**Measure:** Run `mvn verify -B`, parse `jacoco.csv`, check if ≥55%.

## Chapter 2: Ingestion & Parser Tests (target: ~60%)
**Status:** completed
**Depends on:** Chapter 1

- [ ] `HnswIndexTest` — new: test `add()`, `search()`, `isEmpty()`, `size()`, `buildIndex()` with synthetic embeddings. HnswIndex is 853 uncovered instructions — biggest single-class gap. Target: 0% → 60%
- [ ] `ImportParserTest` — new/expand: test Java import parsing, XML namespace extraction, edge cases. Target: 52% → 80%
- [ ] `ReladomoConfigParserTest` — new: test parsing of Reladomo runtime config XML. Target: 3% → 60%
- [ ] `FileWatcherTest` — new: test start/stop/status with temp directory. Target: 5% → 40%
- [ ] `TextExtractorTest` — expand: test more file types (HTML, XML, plain text). Target: 54% → 70%

**Measure:** Run `mvn verify -B`, parse `jacoco.csv`, check if ≥60%.

## Chapter 3: REST Controller + Integration (target: ~65%)
**Status:** completed
**Depends on:** Chapter 2

- [ ] `JavaDuckerRestControllerTest` — expand: cover newly added endpoints (stale/summary, related, blame, explain) and uncovered existing endpoints (map, watch/start, watch/stop, dependencies, dependents, content intelligence write endpoints)
- [ ] `IngestionWorkerTest` — new: test the ingestion pipeline with a real file through upload → parse → chunk → embed → index. Target: 56% → 70%
- [ ] `ReladomoQueryServiceTest` — expand: cover uncovered query methods (getGraph, getPath, getSchema, getObjectFiles, getFinderPatterns, getDeepFetchProfiles, getTemporalInfo, getConfig). Target: 45% → 65%

**Measure:** Run `mvn verify -B`, parse `jacoco.csv`, check if ≥65%. If not, identify remaining gaps and add targeted tests.

---

## Closed-Loop Protocol

After each chapter:

1. Run `mvn verify -B`
2. Parse `target/site/jacoco/jacoco.csv` → compute instruction coverage %
3. If coverage ≥ 65%: **STOP** — mark plan completed
4. If coverage < 65% and chapters remain: proceed to next chapter
5. If coverage < 65% and all chapters done: identify the top 5 uncovered classes, write targeted tests, re-measure (max 2 extra iterations)
6. Log each iteration: coverage %, delta, tests added

## Exclusions

Do NOT write tests for these CLI/UI classes (low ROI, hard to test):
- `JavaDuckerClient` and all nested `*Cmd` classes
- `InteractiveCli`, `CommandDispatcher`, `SearchCommand`, `CatCommand`, `IndexCommand`, `StatsCommand`, `StatusCommand`
- `ResultsFormatter`, `ProgressBar`, `StatsPanel`, `Theme`
- `ApiClient`

---
