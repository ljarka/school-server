# Task queue

One implementation brief per task (`T-xxx-<slug>.md`).

- **Order, status, commits:** [`../STATUS.md`](../STATUS.md)
- **Decisions behind tasks:** [`../adrs/`](../adrs/README.md)
- **Product contract:** [`../SPEC.md`](../SPEC.md)

## Conventions

- Status: `draft`, `ready`, `in-progress`, `done`, `closed` (dropped; say why).
- `ready` = an agent can build it without further product decisions.
- Each task has: Status, Depends on, ADRs, Summary, Scope (files), Behaviour, Tests, Acceptance.
- Finished task: set `done`, add the commit hash in STATUS, keep the file as the record.
- Found a missing decision? **Decide yourself** and record it under `## Decisions` in the task
  (or as an ADR if it is architectural) — see "Autonomy" in [AGENTS.md](../../AGENTS.md).
  Don't wait for the owner. Steps that truly need the owner (live account, GitHub settings) are
  marked `blocked: needs owner`, and you continue with the rest.

## Template

```markdown
# T-0xx — Title

**Status:** draft
**Depends on:** T-0yy
**ADRs:** 000n

## Summary
## Scope
## Behaviour
## Tests
## Acceptance
## Decisions
<!-- agent fills in: decision — why — alternatives -->
## Remaining
<!-- anything not finished, or `blocked: needs owner` -->
```
