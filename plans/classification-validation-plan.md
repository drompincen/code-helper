# Classification Validation Plan — Multi-Agent (jbang)

Validates the content classification system described in `content-classification-ideas.md`
by running 5 coordinated jbang agent scripts end-to-end: create fixtures → load → enrich
→ assert → report.

Depends on: classification schema + `EnrichmentWorker` being implemented first.

---

## Architecture Overview

```
validate-classification.sh
  │
  ├─► CorpusAgent.java      (jbang)  — write 7 fixture files to disk
  ├─► run-client.sh upload-dir       — upload all fixtures, collect artifact_ids
  ├─► run-client.sh wait-indexed     — poll until all 7 reach INDEXED
  ├─► EnrichAgent.java      (jbang)  — trigger enrichment, poll until all ENRICHED
  ├─► AssertAgent.java      (jbang)  — assert doc_type, tags, salient points, concepts
  └─► ReportAgent.java      (jbang)  — aggregate results → RESULTS.md
```

All scripts use `run-client.sh` conventions or the same `JavaDuckerClient` HTTP helpers.
Each jbang script is a self-contained file in `src/test/agents/`.

---

## Files to Create

```
src/test/agents/
  CorpusAgent.java          — writes fixture files to test-corpus/classification/
  EnrichAgent.java          — POST /api/enrich-all, polls /api/status until ENRICHED
  AssertAgent.java          — queries classification endpoints, diffs vs ground truth
  ReportAgent.java          — reads assert-results/*.json, writes RESULTS.md

test-corpus/classification/   (created by CorpusAgent)
  decisions/adr-001-kafka-async.md
  scratch/brainstorm-caching-ideas.md
  retros/retro-sprint-14.md
  plans/design-auth-rewrite.md
  threads/slack-export-db-migration.txt
  plans/roadmap-q2-2026.md
  scratch/working-notes-transaction-refactor.md
  assert-results/            (written by AssertAgent, consumed by ReportAgent)

validate-classification.sh    — top-level orchestrator
```

---

## New CLI Subcommands (add to JavaDuckerClient.java)

The assert agent calls the server via HTTP directly (same pattern as existing subcommands).
Add these subcommands so they are also available interactively:

| Subcommand | Flag | Description |
|------------|------|-------------|
| `enrich` | `--id <id>` | Trigger enrichment for one artifact |
| `enrich-all` | — | Trigger enrichment for all INDEXED artifacts |
| `wait-indexed` | `--dir <path> --timeout <s>` | Poll stats until all uploads are INDEXED |
| `classification` | `--id <id>` | Print doc_type, confidence, method |
| `salient-points` | `--id <id> [--type X]` | List salient points for an artifact |
| `tags` | `--id <id>` | List tags for an artifact |
| `concepts` | `--id <id>` | List concepts for an artifact |

These depend on the REST endpoints from `content-classification-ideas.md` being implemented.

---

## Agent 1 — CorpusAgent.java

**Role:** Write all 7 fixture files to disk. Idempotent — overwrites if they already exist.

**Jbang header:**
```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
```

**Behaviour:**
1. Accept `--root <path>` (default: `test-corpus/classification`)
2. Create subdirectories: `decisions/`, `scratch/`, `retros/`, `plans/`, `threads/`, `assert-results/`
3. Write each fixture file from the content defined in the **Corpus Spec** section below
4. Print `WRITTEN: <path>` for each file
5. Exit 0 when all 7 are written

**Invocation:**
```bash
jbang src/test/agents/CorpusAgent.java --root test-corpus/classification
```

---

## Agent 2 — Load (existing client)

No new agent needed. Use `run-client.sh` with new `wait-indexed` subcommand:

```bash
# Upload all 7 fixtures
./run-client.sh upload-dir \
  --root test-corpus/classification \
  --ext .md,.txt \
  | tee /tmp/jd-upload.log

# Extract artifact IDs from the upload log and write to /tmp/jd-ids.txt
grep "Artifact ID:" /tmp/jd-upload.log | awk '{print $NF}' > /tmp/jd-ids.txt

# Wait until all are indexed (new subcommand)
./run-client.sh wait-indexed --ids-file /tmp/jd-ids.txt --timeout 120
```

`wait-indexed` polls `GET /api/stats` and exits 0 when all supplied artifact_ids show
`status = INDEXED`. Exits 1 on timeout or any `FAILED` status.

---

## Agent 3 — EnrichAgent.java

**Role:** Trigger enrichment for all INDEXED artifacts and poll until all reach `ENRICHED`.

**Jbang header:**
```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
```

**Behaviour:**
1. Accept `--host <h>` `--port <p>` `--ids-file <f>` `--timeout <s>` (default 120)
2. Read artifact_ids from `--ids-file` (one per line)
3. Call `POST /api/enrich-all` (or `POST /api/enrich/{id}` per id)
4. Poll `GET /api/status/{id}` every 3s for each artifact_id
5. Exit 0 when all show `ENRICHED`
6. Exit 1 if any reach `ENRICH_FAILED` or timeout expires
7. Print progress: `[3/7] ENRICHED adr-001-kafka-async.md`

**Invocation:**
```bash
jbang src/test/agents/EnrichAgent.java \
  --host localhost --port 8080 \
  --ids-file /tmp/jd-ids.txt \
  --timeout 120
```

---

## Agent 4 — AssertAgent.java

**Role:** For each fixture, query classification endpoints and assert against the ground truth
table embedded in the script. Write one JSON result file per fixture.

**Jbang header:**
```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
```

**Behaviour:**
1. Accept `--host <h>` `--port <p>` `--ids-file <f>` `--out-dir <d>`
2. `--ids-file` format: `filename TAB artifact_id` (one per line)
3. For each fixture, run the 4 assertion groups (see below)
4. Write `<out-dir>/<fixture-name>.json` per fixture
5. Print `PASS` / `FAIL` per assertion to stdout
6. Exit 0 if all pass, exit 1 if any fail

**Assertions per fixture:**

```java
// 1. doc_type
GET /api/classifications/{artifactId}
→ assert resp.doc_type equals expected (exact)
→ assert resp.confidence >= 0.6

// 2. tags (subset)
GET /api/tags?artifactId={artifactId}
→ assert all expectedTags[] are present in response list

// 3. salient points
GET /api/salient-points?artifactId={artifactId}
→ for each {type, regex} in groundTruth:
    find point where point.point_type == type
    AND point.point_text.toLowerCase().matches(".*" + regex + ".*")
    → PASS if found, FAIL with actual points of that type shown

// 4. concepts (subset)
GET /api/concepts?artifactId={artifactId}
→ assert all expectedConcepts[] appear in concept names
```

**Output file format per fixture:**
```json
{
  "fixture": "adr-001-kafka-async.md",
  "artifact_id": "...",
  "doc_type":  { "expected": "ADR",   "actual": "ADR",   "pass": true },
  "confidence":{ "expected": ">=0.6", "actual": 0.82,    "pass": true },
  "tags":      { "missing": [],                           "pass": true },
  "salient_points": [
    { "type": "DECISION", "pattern": "chose kafka over direct", "pass": true,
      "matched": "We chose Kafka over direct synchronous RPC calls..." },
    { "type": "RISK", "pattern": "eventual consistency", "pass": true, "matched": "..." },
    { "type": "ACTION", "pattern": "@drom will implement the dead-letter", "pass": true, "matched": "..." },
    { "type": "CONSTRAINT", "pattern": "must not exceed 50k", "pass": false,
      "matched": null, "actual_constraints": ["Cannot use the orders topic"] }
  ],
  "concepts":  { "missing": ["dead-letter queue"],        "pass": false },
  "total_pass": 14, "total_fail": 2
}
```

**Invocation (can run 7 in parallel):**
```bash
jbang src/test/agents/AssertAgent.java \
  --host localhost --port 8080 \
  --ids-file /tmp/jd-ids.txt \
  --out-dir test-corpus/classification/assert-results
```

---

## Agent 5 — ReportAgent.java

**Role:** Read all JSON results from AssertAgent, compute totals, write `RESULTS.md`.

**Jbang header:**
```java
///usr/bin/env jbang "$0" "$@" ; exit $?
//JAVA 21
//DEPS com.fasterxml.jackson.core:jackson-databind:2.17.0
```

**Behaviour:**
1. Accept `--in-dir <d>` `--out <f>` (default: `test-corpus/classification/RESULTS.md`)
2. Read all `*.json` from `--in-dir`
3. Compute: total fixtures, total assertions, total pass, total fail
4. Write `RESULTS.md` (see format below)
5. Print summary to stdout
6. Exit 0 if zero failures, exit 1 otherwise

**RESULTS.md format:**
```markdown
# Classification Validation Results — 2026-03-22

## Summary
| Metric | Value |
|--------|-------|
| Fixtures tested | 7 |
| Total assertions | 67 |
| Passed | 65 |
| Failed | 2 |
| Result | ❌ FAIL |

## Failures

### adr-001-kafka-async.md — 1 failure
**[salient_point] CONSTRAINT** — pattern: `must not exceed 50k messages/day`
- No CONSTRAINT point matched. Actual CONSTRAINT points:
  - "Cannot use the orders topic for fulfillment"

### adr-001-kafka-async.md — concept missing
**[concept]** — expected: `dead-letter queue` — not found in concept list

## Full Results by Fixture
| Fixture | doc_type | tags | salient_points | concepts | result |
|---------|----------|------|----------------|----------|--------|
| adr-001-kafka-async.md | ✅ | ✅ | ❌ 1 fail | ❌ 1 fail | FAIL |
| brainstorm-caching-ideas.md | ✅ | ✅ | ✅ | ✅ | PASS |
...
```

**Invocation:**
```bash
jbang src/test/agents/ReportAgent.java \
  --in-dir test-corpus/classification/assert-results \
  --out test-corpus/classification/RESULTS.md
```

---

## Orchestrator — validate-classification.sh

Single entry point. Runs all 5 agents in sequence. Fails fast.

```bash
#!/bin/bash
set -e

HOST=${JAVADUCKER_HOST:-localhost}
PORT=${JAVADUCKER_PORT:-8080}
ROOT=test-corpus/classification
IDS=/tmp/jd-classification-ids.txt
RESULTS_DIR=$ROOT/assert-results

echo "=== Step 1: Write fixture files ==="
jbang src/test/agents/CorpusAgent.java --root $ROOT

echo "=== Step 2: Upload fixtures ==="
./run-client.sh --host $HOST --port $PORT upload-dir \
  --root $ROOT --ext .md,.txt \
  | tee /tmp/jd-upload.log

# Build ids file: "filename\tartifact_id"
grep "Uploaded:" /tmp/jd-upload.log \
  | sed 's|.*classification/||; s| -> |\t|' > $IDS

echo "=== Step 3: Wait for INDEXED ==="
./run-client.sh --host $HOST --port $PORT wait-indexed \
  --ids-file $IDS --timeout 120

echo "=== Step 4: Trigger enrichment and wait for ENRICHED ==="
jbang src/test/agents/EnrichAgent.java \
  --host $HOST --port $PORT \
  --ids-file $IDS --timeout 120

echo "=== Step 5: Assert classification results ==="
mkdir -p $RESULTS_DIR
jbang src/test/agents/AssertAgent.java \
  --host $HOST --port $PORT \
  --ids-file $IDS \
  --out-dir $RESULTS_DIR

echo "=== Step 6: Generate report ==="
jbang src/test/agents/ReportAgent.java \
  --in-dir $RESULTS_DIR \
  --out $ROOT/RESULTS.md

echo ""
echo "Report written to $ROOT/RESULTS.md"
```

---

## Corpus Spec — Fixture File Contents

The authoritative content for each file. CorpusAgent writes these verbatim.

### decisions/adr-001-kafka-async.md
```
# ADR-001: Use Kafka for Order-Fulfillment Async Handoff

Status: Accepted
Date: 2026-03-22
Owner: @drom

## Context

The order API must hand off to fulfillment after payment confirmation. Fulfillment processing
takes 2–10 seconds. Blocking the order API on fulfillment violates our 200ms SLA.

## Decision

We chose Kafka over direct synchronous RPC calls for the order-to-fulfillment handoff.

## Rationale

Kafka allows us to decouple the order write path from fulfillment processing entirely.
Direct gRPC would require a timeout strategy that hides errors from callers.

## Trade-offs

Risk: This introduces eventual consistency between order and fulfillment state.
Risk: We need a dead-letter queue to handle failed fulfillment events.

We must implement a compensating transaction if fulfillment fails after the order is confirmed.
Action item: @drom will implement the dead-letter queue handler by 2026-04-01.

## Constraints

Must not exceed 50k messages/day on the shared Kafka cluster without a capacity review.
Cannot use the orders topic for fulfillment — create a dedicated fulfillment-events topic.

## Open Questions

How should we handle fulfillment timeouts beyond 30 seconds?
What retry policy applies when the fulfillment consumer is unavailable?
```

**Ground truth:**
| type | regex pattern |
|------|---------------|
| `DECISION` | `chose kafka over direct synchronous rpc` |
| `RISK` | `eventual consistency between order and fulfillment` |
| `RISK` | `dead-letter queue` |
| `ACTION` | `@drom will implement the dead-letter queue handler` |
| `CONSTRAINT` | `must not exceed 50k messages` |
| `CONSTRAINT` | `cannot use the orders topic` |
| `QUESTION` | `fulfillment timeouts beyond 30 seconds` |
| `QUESTION` | `retry policy applies when the fulfillment consumer` |

**Expected doc_type:** `ADR`
**Expected tags (subset):** `kafka`, `async`, `fulfillment`, `eventual-consistency`
**Expected concepts (subset):** `Kafka`, `OrderService`, `fulfillment`, `dead-letter queue`

---

### scratch/brainstorm-caching-ideas.md
```
# Caching Brainstorm — Payment Service

Working notes from 2026-03-20 exploration.

## Ideas

What if we introduced a read-through cache in front of the PaymentGateway client?
Consider adding a TTL-based cache for currency exchange rates — they change at most hourly.
What if we moved the customer profile cache to Redis Cluster instead of local JVM heap?

## Observations

The payment service currently has no caching at all. Every call to PaymentGateway hits
the network. This is the primary source of the p99 latency we saw in the Grafana dashboard.

Key insight: 80% of payment lookups are for the same 100 customers. A small LRU cache
would dramatically cut outbound calls.

## Concerns

Risk: If we cache payment status incorrectly we could allow a double-charge scenario.
Risk: Cache invalidation across multiple PaymentService instances is hard to coordinate.

## Open Questions

What cache eviction policy is safest for payment status — TTL or event-driven invalidation?
How do we test cache correctness without a real payment provider?
```

**Ground truth:**
| type | regex pattern |
|------|---------------|
| `IDEA` | `read-through cache in front of the paymentgateway` |
| `IDEA` | `ttl-based cache for currency exchange rates` |
| `IDEA` | `moved the customer profile cache to redis cluster` |
| `INSIGHT` | `80% of payment lookups are for the same 100 customers` |
| `RISK` | `cache payment status incorrectly.*double-charge` |
| `RISK` | `cache invalidation across multiple paymentservice` |
| `QUESTION` | `cache eviction policy is safest for payment status` |
| `QUESTION` | `test cache correctness without a real payment provider` |

**Expected doc_type:** `SCRATCH`
**Expected tags (subset):** `caching`, `redis`, `performance`
**Expected concepts (subset):** `PaymentGateway`, `Redis`, `LRU`

---

### retros/retro-sprint-14.md
```
# Sprint 14 Retrospective — 2026-03-21

Attendees: @drom, @alice, @bob

## What Went Well

We shipped the payment gateway integration on time.
The new CI pipeline reduced build times by 40%.

## What Went Poorly

The staging deployment broke twice due to missing environment variables.
We discovered the auth middleware has a memory leak under load — this was not caught in tests.

## Key Insights

Key insight: Our staging environment does not mirror production secrets management.
Key insight: We have no load tests for the auth middleware.

## Action Items

@alice will add the missing env vars to the staging deployment checklist by 2026-03-25.
@bob will write a load test for the auth middleware and run it before the next release.
@drom will document the production secrets management approach in Confluence.

## Status

As of sprint 14 close: the payment integration is live but the memory leak in AuthMiddleware
is still open. We are treating it as P1 for sprint 15.

## Risks

Risk: The AuthMiddleware memory leak may cause OOM restarts under peak load before the fix lands.
```

**Ground truth:**
| type | regex pattern |
|------|---------------|
| `INSIGHT` | `staging environment does not mirror production secrets` |
| `INSIGHT` | `no load tests for the auth middleware` |
| `ACTION` | `@alice will add the missing env vars` |
| `ACTION` | `@bob will write a load test for the auth middleware` |
| `ACTION` | `@drom will document the production secrets management` |
| `STATUS` | `payment integration is live` |
| `STATUS` | `memory leak in authmiddleware is still open` |
| `RISK` | `authmiddleware memory leak may cause oom` |

**Expected doc_type:** `MEETING_NOTES`
**Expected tags (subset):** `sprint-14`, `retro`, `performance`
**Expected concepts (subset):** `AuthMiddleware`, `CI pipeline`, `staging`

---

### plans/design-auth-rewrite.md
```
# Design: Auth Middleware Rewrite

Author: @drom
Status: Draft
Created: 2026-03-18

## Problem Statement

The current auth middleware stores session tokens in-memory with no TTL enforcement.
Legal has flagged this as non-compliant with our data retention policy.
We must replace it with a stateless JWT-based approach before the Q2 audit.

## Proposed Solution

We chose JWT (RS256) over session tokens because it is stateless, auditable, and aligns
with the OAuth2 standard our IdP already supports.

Consider adding a short-lived access token (15 min) paired with a longer-lived refresh token
(7 days) to balance security and UX.

What if we used token binding to tie JWTs to the client TLS certificate?

## Constraints

Must comply with GDPR Article 25 data minimisation — JWTs must not contain PII beyond user_id.
Cannot use symmetric signing (HS256) — the shared secret creates key-rotation risk.
The rewrite must be backwards compatible with existing API clients for at least 90 days.

## Risks

Risk: JWT revocation before expiry requires a blocklist, which reintroduces statefulness.
Risk: Clients that cache tokens aggressively may use expired tokens for up to 15 minutes.

## Action Items

@drom will produce a threat model for the JWT implementation by 2026-03-28.
@alice will survey API clients to identify those that cannot handle 401 refresh flows.

## Open Questions

How do we handle token revocation for immediate logout (e.g., account compromise)?
Should the refresh token be stored in an HttpOnly cookie or returned in the response body?
```

**Ground truth:**
| type | regex pattern |
|------|---------------|
| `DECISION` | `chose jwt.*rs256.*over session tokens` |
| `IDEA` | `short-lived access token.*15 min.*paired with.*refresh token` |
| `IDEA` | `token binding to tie jwts to the client tls certificate` |
| `CONSTRAINT` | `must comply with gdpr article 25` |
| `CONSTRAINT` | `cannot use symmetric signing` |
| `CONSTRAINT` | `backwards compatible.*existing api clients.*90 days` |
| `RISK` | `jwt revocation.*requires a blocklist` |
| `RISK` | `clients that cache tokens.*expired tokens` |
| `ACTION` | `@drom will produce a threat model` |
| `ACTION` | `@alice will survey api clients` |
| `QUESTION` | `handle token revocation for immediate logout` |
| `QUESTION` | `refresh token.*httonly cookie or.*response body` |

**Expected doc_type:** `DESIGN_DOC`
**Expected tags (subset):** `auth`, `jwt`, `security`, `session`
**Expected concepts (subset):** `JWT`, `RS256`, `OAuth2`, `IdP`

---

### threads/slack-export-db-migration.txt
```
[2026-03-19 09:14] @alice: heads up — the Postgres migration for the orders schema is scheduled for Thursday
[2026-03-19 09:15] @bob: do we have a rollback script ready?
[2026-03-19 09:17] @alice: yes, rollback is tested. The concern is the ALTER TABLE on orders will lock for ~3min
[2026-03-19 09:18] @drom: that's a risk — 3 min table lock on orders will cause timeouts at peak traffic
[2026-03-19 09:20] @alice: we decided to run it in the Thursday 02:00 UTC maintenance window to avoid peak load
[2026-03-19 09:21] @bob: action item — @alice will send a maintenance notice to customers by Wednesday EOD
[2026-03-19 09:22] @drom: what happens if the migration fails halfway? Is the rollback atomic?
[2026-03-19 09:24] @alice: yes, it's wrapped in a transaction. Key insight: DuckDB-based integration tests confirmed the rollback works end-to-end.
[2026-03-19 09:26] @bob: status: migration is go for Thursday. Risk accepted for the 3-min lock window.
[2026-03-19 09:27] @drom: what's our escalation path if we exceed the 3-min window?
```

**Ground truth:**
| type | regex pattern |
|------|---------------|
| `RISK` | `3 min table lock on orders will cause timeouts` |
| `DECISION` | `decided to run it in the thursday 02:00 utc maintenance window` |
| `ACTION` | `@alice will send a maintenance notice` |
| `QUESTION` | `migration fails halfway.*rollback atomic` |
| `INSIGHT` | `duckdb-based integration tests confirmed the rollback works` |
| `STATUS` | `migration is go for thursday` |
| `QUESTION` | `escalation path if we exceed the 3-min window` |

**Expected doc_type:** `THREAD`
**Expected tags (subset):** `postgres`, `migration`, `schema`, `downtime`
**Expected concepts (subset):** `Postgres`, `orders`, `maintenance window`

---

### plans/roadmap-q2-2026.md
```
# Q2 2026 Engineering Roadmap

Status: Draft — pending sign-off
Owner: @drom

## Objectives

1. Complete the auth middleware rewrite (target: end of April)
2. Ship Kafka-based fulfillment pipeline (target: mid-May)
3. Achieve 99.9% uptime SLA for the payment service (ongoing)

## Key Milestones

- 2026-04-15: Auth middleware rewrite merged and in production
- 2026-05-15: Kafka fulfillment pipeline in production
- 2026-06-30: Q2 close — OKR review

## Constraints

Must not disrupt the mobile team release scheduled for 2026-04-20.
All production changes must pass the security review checklist.

## Open Risks

Risk: Auth rewrite slips if JWT threat model reveals blocking issues.
Risk: Kafka capacity review may delay fulfillment pipeline if volume projections are wrong.

## Status

As of 2026-03-22: auth rewrite is in design phase, Kafka pipeline is in planning.
The Q2 roadmap is not yet approved — waiting for VP sign-off.
```

**Ground truth:**
| type | regex pattern |
|------|---------------|
| `CONSTRAINT` | `must not disrupt the mobile team release` |
| `CONSTRAINT` | `all production changes must pass the security review` |
| `RISK` | `auth rewrite slips if jwt threat model` |
| `RISK` | `kafka capacity review may delay fulfillment` |
| `STATUS` | `auth rewrite is in design phase.*kafka pipeline is in planning` |
| `STATUS` | `q2 roadmap is not yet approved` |

**Expected doc_type:** `PLAN`
**Expected tags (subset):** `q2-2026`, `roadmap`, `auth`, `kafka`
**Expected concepts (subset):** `auth middleware`, `Kafka`, `Q2 2026`

---

### scratch/working-notes-transaction-refactor.md
```
# Working Notes — Transaction Boundary Refactor

Started: 2026-03-17. Paused: 2026-03-19.

## Where I am

Current state: extracted TransactionManager from OrderService. The service no longer manages
its own connection lifecycle — that's now owned by TransactionManager.

Next step: migrate callers in OrderService to use the new TransactionManager API.
TODO: update PaymentService — it still calls the old OrderService.beginTransaction() directly.

## What I tried

I tried wrapping the entire OrderService in a single @Transactional but this caused
deadlocks when PaymentService called back into OrderService mid-transaction.

Key insight: the deadlock happens because Spring's default propagation is REQUIRED — both
services joined the same transaction and then tried to lock the same row.

## Blocked on

How do we handle the case where PaymentService and OrderService must coordinate a distributed
transaction? XA transactions are available but known to be slow.

What if we used the Outbox pattern instead of XA to coordinate cross-service writes?

## Risk

Risk: Migrating callers incrementally means there will be a period where some callers use
the old API and some use the new one — inconsistent transaction semantics during the window.
```

**Ground truth:**
| type | regex pattern |
|------|---------------|
| `STATUS` | `extracted transactionmanager from orderservice` |
| `ACTION` | `todo.*update paymentservice.*still calls the old.*begintransaction` |
| `INSIGHT` | `deadlock happens because spring.*default propagation is required` |
| `QUESTION` | `paymentservice and orderservice must coordinate a distributed transaction` |
| `IDEA` | `outbox pattern instead of xa` |
| `RISK` | `migrating callers incrementally.*inconsistent transaction semantics` |

**Expected doc_type:** `SCRATCH`
**Expected tags (subset):** `transactions`, `refactor`, `spring`
**Expected concepts (subset):** `TransactionManager`, `OrderService`, `PaymentService`, `Outbox`

---

## Cross-Document Concept Link Assertions (AssertAgent extra step)

After per-fixture assertions, AssertAgent also validates concept links across documents:

| Concept | Must appear in these fixtures |
|---------|-------------------------------|
| `Kafka` | `adr-001-kafka-async.md`, `roadmap-q2-2026.md` |
| `AuthMiddleware` | `retro-sprint-14.md`, `design-auth-rewrite.md` |
| `PaymentService` | `brainstorm-caching-ideas.md`, `working-notes-transaction-refactor.md` |
| `OrderService` | `adr-001-kafka-async.md`, `working-notes-transaction-refactor.md` |

These are validated via:
```
GET /api/concepts/{conceptName}/documents
→ assert returned artifact_ids include both expected fixture artifact_ids
```

---

## Pre-conditions Before Running

| Pre-condition | Needed by |
|---------------|-----------|
| Classification schema tables exist (see `content-classification-ideas.md` §4) | EnrichAgent, AssertAgent |
| `EnrichmentWorker` implemented with `ENRICHED` status | EnrichAgent |
| REST endpoints: `/api/enrich-all`, `/api/classifications/{id}`, `/api/tags`, `/api/salient-points`, `/api/concepts` | AssertAgent |
| `wait-indexed` subcommand added to `JavaDuckerClient` | orchestrator |
| Server running (`./run-server.sh`) | all agents |

---

## Definition of Done

- `validate-classification.sh` exits 0
- `test-corpus/classification/RESULTS.md` exists with 0 failures
- All 7 ground truth tables fully satisfied (every row a PASS)
- All 4 cross-document concept link assertions pass
- No fixture shows `FAILED` or `ENRICH_FAILED` status at end of run
