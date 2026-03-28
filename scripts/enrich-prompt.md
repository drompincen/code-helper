# Content Intelligence Enrichment Prompt

You are enriching artifacts in JavaDucker's content intelligence system. For each artifact, read its extracted text and produce a structured enrichment result.

## Input

You will receive the artifact's text content, file name, and any related artifacts sharing concepts.

## Output

Return a JSON object with these fields:

```json
{
  "doc_type": "ADR|DESIGN_DOC|PLAN|MEETING_NOTES|THREAD|SCRATCH|CODE|REFERENCE|TICKET",
  "confidence": 0.0-1.0,
  "tags": [
    {"tag": "string", "tag_type": "topic|entity|person|time|manual", "source": "llm"}
  ],
  "salient_points": [
    {"point_type": "DECISION|IDEA|QUESTION|ACTION|RISK|INSIGHT|CONSTRAINT|STATUS", "point_text": "string"}
  ],
  "concepts": [
    {"concept": "string", "concept_type": "system|person|topic|term", "mention_count": 1}
  ],
  "freshness": {
    "assessment": "current|stale|superseded",
    "superseded_by": "artifact_id or null",
    "reason": "why this assessment"
  },
  "prune": {
    "should_prune": false,
    "summary": "compact summary if pruning",
    "key_points": "key points if pruning",
    "outcome": "outcome/resolution if pruning"
  }
}
```

## Document Type Taxonomy

| Type | Signals |
|------|---------|
| ADR | Architecture decision records, "Status: Accepted/Proposed", "Decision:", alternatives considered |
| DESIGN_DOC | Design docs, RFCs, proposals, "Problem Statement:", "Proposed Solution:" |
| PLAN | Roadmaps, sprint plans, milestones, objectives with dates |
| MEETING_NOTES | Meeting notes, standups, retros, attendees, action items |
| THREAD | Chat/email exports, timestamped messages, @mentions |
| SCRATCH | Working notes, brainstorms, "What if", ideas, paused/WIP |
| CODE | Source files (.java, .py, .js, etc.) |
| REFERENCE | Documentation, READMEs, wikis, how-tos |
| TICKET | Issue/ticket content, status updates, acceptance criteria |

## Salient Point Types

| Type | Signals |
|------|---------|
| DECISION | "We chose X over Y", "decided to", explicit choice between alternatives |
| IDEA | "What if", "Consider", hypotheses, proposals, brainstorms |
| QUESTION | "How should we", "TBD", open questions, unknowns |
| ACTION | "TODO:", "@person will", action items with owners |
| RISK | "Risk:", "Watch out for", concerns, caveats |
| INSIGHT | "Key insight:", important findings, observations |
| CONSTRAINT | "Must", "Cannot", hard requirements, compliance |
| STATUS | "As of today", current state reports, progress updates |

## Freshness Evaluation

When related artifacts are provided, evaluate:
- Is this the latest artifact on its topic?
- Does a newer artifact supersede this one?
- Has this been contradicted by newer content?
- Is this a status/plan that hasn't been updated in a while?

## Pruning Criteria

Only recommend pruning for artifacts that are:
- Superseded AND the replacement is clearly better/more complete
- Old scratch notes that evolved into formal documents
- Status updates that have been replaced by newer status updates
- Abandoned ideas explicitly marked as such

Never prune: current artifacts, the only document on a topic, historical decisions (even if reversed).
