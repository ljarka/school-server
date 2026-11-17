# School Server — Roadmap & Status

## Vision

A small, private server on the family's own machine that watches the child's school e-journal
(parent account) and tells the family what's new: grades and messages. Read-only towards the
e-journal, one container, one `.env`, one API key. Picfort can show it on its school screen,
but never depends on it (ADR 0006).

**Updated:** 2026-11-17

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
| [T-007](tasks/T-007-messages-parser.md) | Messages fetch + parser with fixtures | `done` |

### Phase 3 — Sync and changes

| Task | Description | Status |
|------|-------------|--------|
| [T-008](tasks/T-008-change-detector.md) | Change detector and `change_events` | `done` |
| [T-009](tasks/T-009-sync-scheduler.md) | Sync scheduler, lock, backoff, `/sync` | `done` |

### Phase 4 — API and release

| Task | Description | Status |
|------|-------------|--------|
| [T-010](tasks/T-010-api-grades-messages.md) | API: grades, subjects, messages, read state; OpenAPI + `/docs` | `done` |
| [T-011](tasks/T-011-api-changes-status.md) | API: `/changes` feed, `/status` | `done` |
| [T-012](tasks/T-012-neutral-names-ci.md) | CI: tests, neutral-names check, GHCR release | `done` |
| [T-013](tasks/T-013-e2e-and-readme.md) | End-to-end test, README check, first live run | `draft` |

### Phase 5 — After the first review

| Task | Description | Status |
|------|-------------|--------|
| [T-014](tasks/T-014-multi-child.md) | Multi-child sync, `/children`, `?child=` (ADR 0013) | `done` |
| [T-015](tasks/T-015-review-fixes.md) | Fixes from the code review (ADR 0014) | `done` |

Order: T-001 first. Then T-002/T-003/T-004 and T-005→T-006/T-007 can go in parallel.
T-008 needs T-003 + T-006/T-007; T-009 needs T-005 + T-008; T-010/T-011 need T-004 + T-009.

### Later (not v1)
Sending messages (v2, needs an ADR lifting AGENTS.md rule 1), announcements, attendance,
homework, test calendar, timetable, lucky number; push / webhooks / SSE to Picfort; several
parent accounts; attachment download.

---

## Assumptions

Agents may change these on their own by recording the decision (AGENTS.md "Autonomy").

- **All linked children** are synced; `SCHOOL_CHILD` restricts it to one (ADR 0013, supersedes the
  single-child assumption).
- **Change history kept forever** (rows are tiny).
- **Repository and image are public** (owner, 2026-11-16) → provider never named in git (ADR 0006).
- **Data source** decided: parent portal + JSON API v3 + new messages service (ADR 0011); school-issued
  logins via the gateway (ADR 0012).
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
| T-001 | `aa92294`, `353c138` | 2026-11-16, 2026-11-17 |
| T-002 | `cbe693d`, `29d5677`, `dbe45b5`, `0edbfd6` | 2026-11-16 – 17 |
| T-003 | `67ea9bb` | 2026-11-16 |
| T-004 | `4d775f1` | 2026-11-16 |
| T-005 | `1924232`, `e56f84e` … `b443605` (ADR 0012: `9921251`) | 2026-11-16 – 17 |
| T-006 | `c53bae1`, `25df2d4`, `756cf7a`, `74a364d` | 2026-11-16 – 17 |
| T-007 | `4b80a10`, `8b38a09` | 2026-11-16 – 17 |
| T-008 | `d782fd9` | 2026-11-16 |
| T-009 | `7d58ddf` | 2026-11-16 |
| T-010, T-011 | `80d96d3` | 2026-11-16 |
| T-012 | `cb5ddba` | 2026-11-16 |
| T-014 | `4e4222d`, `59ec50c`, `05f414e` | 2026-11-17 |
| T-015 | see git log `T-015:` | 2026-11-17 |
