# School Server — Roadmap & Status

## Vision

A small, private server on the family's own machine that watches the child's school e-journal
(parent account) and tells the family what's new: grades and messages. Read-only towards the
e-journal, one container, one `.env`, one API key. Picfort can show it on its school screen,
but never depends on it (ADR 0006).

**Updated:** 2026-11-16

---

## Phases

### Phase 1 — Foundation
Runnable, installable, secured server without e-journal data yet.

| Task | Description | Status |
|------|-------------|--------|
| [T-001](tasks/T-001-project-skeleton.md) | Project skeleton, config, `/health` | `done` |
| [T-002](tasks/T-002-docker-image.md) | Docker image, compose, example.env | `done` |
| [T-003](tasks/T-003-sqlite-store.md) | `SqliteStore` with migrations, school schema | `done` |
| [T-004](tasks/T-004-api-key-auth.md) | API key, wrong-key limiter, Cloudflare client IP | `done` |

### Phase 2 — E-journal adapter
Everything that knows the provider, isolated in `journal/`.

| Task | Description | Status |
|------|-------------|--------|
| [T-005](tasks/T-005-journal-login.md) | Journal client: login, session, `auth_failed`; data source decision (ADR 0011) | `done` |
| [T-006](tasks/T-006-grades-parser.md) | Grades fetch + parser with fixtures | `done` |
| [T-007](tasks/T-007-messages-parser.md) | Messages fetch + parser with fixtures | `ready` |

### Phase 3 — Sync and changes

| Task | Description | Status |
|------|-------------|--------|
| [T-008](tasks/T-008-change-detector.md) | Change detector and `change_events` | `ready` |
| [T-009](tasks/T-009-sync-scheduler.md) | Sync scheduler, lock, backoff, `/sync` | `ready` |

### Phase 4 — API and release

| Task | Description | Status |
|------|-------------|--------|
| [T-010](tasks/T-010-api-grades-messages.md) | API: grades, subjects, messages, read state; OpenAPI + `/docs` | `ready` |
| [T-011](tasks/T-011-api-changes-status.md) | API: `/changes` feed, `/status` | `ready` |
| [T-012](tasks/T-012-neutral-names-ci.md) | CI: tests, neutral-names check, GHCR release | `ready` |
| [T-013](tasks/T-013-e2e-and-readme.md) | End-to-end test, README check, first live run | `draft` |

Order: T-001 first. Then T-002/T-003/T-004 and T-005→T-006/T-007 can go in parallel.
T-008 needs T-003 + T-006/T-007; T-009 needs T-005 + T-008; T-010/T-011 need T-004 + T-009.

### Later (not v1)
Sending messages (v2, needs an ADR lifting AGENTS.md rule 1), announcements, attendance,
homework, test calendar, timetable, lucky number; push / webhooks / SSE to Picfort; several
parent accounts or children; attachment download.

---

## Assumptions

Agents may change these on their own by recording the decision (AGENTS.md "Autonomy").

- **One child** in v1, chosen with `SCHOOL_CHILD` (default: first, with a warning if several).
- **Change history kept forever** (rows are tiny).
- **Repository and image are public** (owner, 2026-11-16) → provider never named in git (ADR 0006).
- **Data source** decided: parent portal + JSON API v3 + new messages service (ADR 0011).
- **Live verification** against the real account happens only in T-013 (`blocked: needs owner`).

## Known risks

- The e-journal can change its login flow or pages at any time (the reference libraries had to
  fix their login flow in 2026-09). Mitigation: fixtures, `layout_changed:*` errors, data served from DB meanwhile.
- The JSON APIs are unofficial; the reference SDK's releases are the early-warning signal.
- Unofficial access may breach the provider's terms; the public project stays provider-neutral
  and the README tells users to check their provider's terms.

## Done log

| Task | Commit | Date |
|------|--------|------|
| — | | |
