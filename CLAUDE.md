# Project Configuration

## Behavioral Rules

- Do what has been asked; nothing more, nothing less
- NEVER create files unless absolutely necessary for the goal
- ALWAYS prefer editing an existing file to creating a new one
- NEVER proactively create documentation files unless explicitly requested
- NEVER save working files, tests, or docs to the root folder
- ALWAYS read a file before editing it
- Keep files under 500 lines
- NEVER commit secrets, credentials, or .env files

## File Organization

- Use `src/` for source code
- Use `tests/` for test files
- Use `docs/` for documentation
- Use `scripts/` for utility scripts and orchestration scripts
- Use `config/` for configuration files

## Parallelism — ALWAYS parallel by default

- EVERY task must be analyzed for parallelism BEFORE execution
- Batch ALL related file reads in ONE message
- Batch ALL file edits in ONE message
- Batch ALL independent Bash commands in ONE message
- Spawn ALL independent Agent calls in ONE message with `run_in_background: true`
- After spawning background agents, STOP and wait for results — do NOT poll
- When a task has multiple independent fix targets, spawn one Agent per target in a single message
- When reviewing results from parallel agents, read ALL results before deciding next action
- Sequential steps run only when there is a true data dependency on a prior step

## Closed-Loop Execution

When a workflow specifies a loop (repeat-until-pass), follow this protocol:

1. **Read the workflow** to identify: steps, pass condition, max iterations, and what to capture per iteration
2. **Run the check/capture step** to establish baseline metrics
3. **Analyze results** — categorize issues, group by fix type
4. **Spawn parallel fix agents** — one Agent per independent issue category, ALL in one message
5. **Wait for all agents** — review ALL results together
6. **Re-run the check** — compare metrics to previous iteration
7. **Log iteration** — append to `context/MEMORY.md`: iteration number, pass/fail counts, key fixes, regressions
8. **Decide**:
   - All pass → exit loop, run final confirmation
   - Regression detected → revert, log what failed, try different approach
   - Issues remain and under max iterations → go to step 3
   - Max iterations reached → stop, report remaining issues
9. **On exit** — write final summary to `context/MEMORY.md`

### Regression handling
- If an iteration produces MORE issues than the previous one, it is a regression
- Revert the changes from that iteration immediately
- Log what was attempted and why it regressed
- Try a different fix approach in the next iteration
- Never repeat the same fix that caused a regression

## Security

- NEVER hardcode API keys, secrets, or credentials in source files
- NEVER commit .env files or any file containing secrets
- Always validate user input at system boundaries
- Always sanitize file paths to prevent directory traversal

## Memory Protocol

- At session start, read `context/MEMORY.md` for ongoing context
- Before session ends, update `context/MEMORY.md` with progress and findings
- Log important architectural decisions in `context/DECISIONS.md`
- Check `context/CONVENTIONS.md` for project-specific patterns before writing code
- During loops, append iteration results to `context/MEMORY.md` after each iteration

## Orchestration Scripts

- Orchestration scripts live in `scripts/` and automate multi-step pipelines
- Scripts should be idempotent — safe to re-run from any iteration
- Scripts must accept `--iteration N` to resume from a specific point
- Scripts must write machine-readable output (JSON) for Claude to parse
- Scripts must exit with code 0 on success, non-zero on failure
- Use `scripts/orchestrate.sh` as the template for new orchestration scripts

## Workflows

When the task matches a common pattern, follow the corresponding workflow:

- Bug fixes: follow `workflows/bug-fix.md`
- New features: follow `workflows/new-feature.md`
- Refactoring: follow `workflows/refactor.md`
- Code reviews: follow `workflows/code-review.md`
- Closed-loop QA: follow `workflows/closed-loop.md`

## Skills

Use these agent profiles when the task calls for a specialized role:

- `/planner` — Task decomposition, parallel execution planning
- `/implementer` — Writing production code
- `/reviewer` — Code review with severity ratings
- `/debugger` — Systematic bug investigation
- `/refactorer` — Safe code restructuring
- `/architect` — System design and architecture decisions
- `/orchestrator` — Design and run closed-loop pipelines
