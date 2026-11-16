# ADR 0003 — Poll every 15 minutes, detect changes by content hash

**Status:** accepted
**Date:** 2026-11-16

## Context

The e-journal has no push or webhook for a scraper. We need "what's new" for the Picfort
school screen and scripts. We must not hammer the site or lock the parent's account.

## Decision

### Polling
- Every `SYNC_INTERVAL_MINUTES` (default 15, min 5) plus random jitter 0–60 s.
- A coroutine loop in the app's scope; journal calls on `Dispatchers.IO`. One sync at a time (`Mutex`). `POST /api/v1/sync` triggers one, at most once per 5 minutes
  (`429 SYNC_TOO_SOON` otherwise).
- Network errors / 5xx: exponential backoff (1, 2, 4 … max 60 min), reset on success.
- **Rejected login:** set `auth_state = auth_failed`, stop every login attempt until the process
  restarts. `/status` shows it. Never retry credentials automatically.
- Expired session (redirect to login page): one fresh login, then retry the fetch once.
- Polite fetching: sequential requests, ~0.5 s between them, a realistic browser User-Agent,
  message bodies fetched only for messages not yet stored.

### Change detection
- Each item has a stable `source_id` from the e-journal and a `content_hash` = SHA-256 of its
  canonical JSON of meaningful fields (not our metadata, not `read`).
- New id → `created`; same id, different hash → `updated` with `diff` {field: [old, new]};
  id missing from a **complete** fetch → `deleted` (row soft-deleted, `deleted_at` set).
- A partial or failed fetch never produces `deleted` events.
- Rows and events are written in one transaction per sync.
- **First sync reports everything as `created`** (owner decision 2026-11-16). Clients that
  want only new things start from `lastEventId` in `/status`.

## Consequences

- Up to ~15 min latency, acceptable for school grades.
- `change_events.id` is a monotonic cursor; clients store the last id they processed.
- The first sync can produce hundreds of events; `/changes` paginates (`limit` max 500).
