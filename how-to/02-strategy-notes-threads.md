# Use Case 2: Strategy, Plans, Threads, and Notes

Use JavaDucker as a searchable knowledge base for your own thinking — design docs, architectural decisions,
meeting notes, threads, plans, and anything else you want to find later.

## The Problem

Ideas, decisions, and context get scattered across Notion, Slack, email, local notes, and your head.
When you start a new Claude conversation you lose the thread. JavaDucker gives you one place to put
everything — and lets Claude search across it all to reconstruct context instantly.

## What to Store

| Document type | Why it helps |
|---------------|--------------|
| Architecture decision records (ADRs) | Find the *why* behind past choices |
| Design docs / RFCs | Recall the original intent before modifying a system |
| Sprint retrospectives | Spot recurring problems over time |
| Meeting notes | Find decisions and action items by topic |
| Slack thread exports | Surface buried context without scrolling |
| Personal engineering notes | Your own observations while working through a problem |
| Strategy docs | Align Claude with current team direction |

## Setup

Same server setup as the codebase memory use case. Index a dedicated notes directory:

```bash
./run-client.sh upload-dir --root ~/notes/project-alpha --ext .md,.txt,.pdf
```

Or ask Claude:
> "Index my notes folder at `/home/me/notes/project-alpha` — all .md and .txt files"

## Workflows

### Start a new conversation with full context

At the beginning of any session, ask Claude to orient itself:

> "Search javaducker for our current architecture strategy and any open decisions"
> "Find notes about the payment service redesign — what was decided and what is still open"

This reconstructs your working context in seconds instead of re-explaining everything.

---

### Capture decisions as you make them

After a design discussion, write a short note and index it immediately:

```markdown
# Decision: Use async messaging for order fulfillment (2026-03-21)

We chose Kafka over direct service calls for the order→fulfillment handoff.
Reason: fulfillment is slow (2–10s) and we can't block the order API.
Trade-off accepted: eventual consistency, need dead-letter queue handling.
Owner: @drom. Revisit if message volume exceeds 50k/day.
```

Then:
> "Index this file: `/home/me/notes/decisions/async-fulfillment.md`"

Now that decision is findable forever.

---

### Pick up an interrupted thread

You were deep in a complex refactor and had to stop. Next session:

> "Search javaducker for 'transaction boundary refactor' — what did I decide and where did I leave off?"

If you saved a note before stopping ("current state: extracted TransactionManager, next step: migrate callers in OrderService"),
Claude can resume from exactly where you were.

---

### Cross-reference plans against code

Index both your notes and your codebase. Then ask questions that span both:

> "Search for our planned caching strategy in my design docs, then search for the current cache implementation in the code"
> "Find my ADR for the authentication rewrite and check if the implementation matches it"

---

### Surface patterns across time

> "Semantic search for 'performance problems' across all my notes — what issues keep recurring?"
> "Find all mentions of 'tech debt' in retros and group them by theme"

## File Naming Conventions That Help

```
notes/
  decisions/   YYYY-MM-DD-short-title.md
  plans/       current-quarter-roadmap.md
  retros/      YYYY-MM-sprint-N-retro.md
  threads/     slack-export-auth-redesign.txt
  scratch/     working-notes-payment-service.md
```

Consistent naming makes search results self-explanatory — the file path in a result tells you what you're
looking at before you read the chunk.

## Tips

- Write notes *during* the work, not after — even rough notes ("trying approach X, hit problem Y") are searchable
- Export important Slack threads as `.txt` before they scroll away
- Use semantic search for concepts ("what did we decide about caching") and exact for proper nouns ("RedisCluster")
- PDF meeting notes and design docs work fine — PDFBox extracts the text automatically
- Re-index a notes file whenever you update it — the new version replaces the old content in search results
