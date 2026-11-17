# ADR 0013 — Multi-child sync and child selection API

**Status:** accepted (owner decision, 2026-11-17)
**Date:** 2026-11-17

## Context

ADR 0011 scoped v1 to a single child selected via `SCHOOL_CHILD`. In practice, a parent portal
account often links two or more children (e.g. kindergarten and school), and clients need to view
and filter each child's grades and messages without running multiple server instances or restarting
with a different `.env`.

## Decision

1. **Sync all linked children** (unless `SCHOOL_CHILD` is set to restrict to a single child).
2. **Store `child_id`** on `grade`, `message`, and `change_event` rows, with a `child_account`
   table (`id`, `login`, `name`, `updated_at`) populated on sync.
3. **Expose `GET /api/v1/children`** returning `{ "children": [{ "id", "login", "name" }] }`.
4. **Optional `?child=<id_or_login>` filter** on `/api/v1/grades`, `/api/v1/grades/subjects`,
   `/api/v1/messages`, `/api/v1/messages/{id}`, `/api/v1/messages/read-all`, and `/api/v1/changes`.
   When omitted, all children's items are returned (backward compatible with single-child setups).
   Responses include `childId` on grades, messages, and change events.

## Consequences

- Requires SQLDelight migration `1.sqm` (`grade`, `message`, `change_event`, `child_account`).
- `JournalClient` accepts `childId: String?` on fetch methods and exposes `listChildren()`.
- Supersedes the single-child limitation in ADR 0011.
