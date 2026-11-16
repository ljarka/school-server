# T-011 — API: `/changes` feed and `/status`

**Status:** ready
**Depends on:** T-004, T-009
**ADRs:** 0003

## Summary

The change feed with a numeric cursor, and the status endpoint Picfort and humans use to see
whether syncing works.

## `GET /api/v1/changes`

Params: `after` (event id, default 0) **or** `since` (ISO instant; both → 400), `type`
(`grade|message`), `limit` (1–500, default 100).

```json
{
  "items": [
    {"id": 121, "entityType": "grade", "entityId": "g_8812", "changeType": "created",
     "detectedAt": "2026-11-16T14:05:00Z",
     "entity": { ...current Grade DTO... },
     "diff": null}
  ],
  "nextCursor": 121,
  "hasMore": false
}
```

- `entity` = current state (for `deleted`: last known state with `deletedAt`).
- Messages in `entity` have no body (fetch `/messages/{id}`).
- `nextCursor` = last item id, or the request's `after` if empty.

## `GET /api/v1/status`

```json
{
  "auth": "ok | auth_failed | unknown",
  "lastSync": {"startedAt": "...", "finishedAt": "...", "status": "ok", "errorCode": null,
               "itemsFetched": 42, "changesDetected": 3},
  "lastSuccessfulSyncAt": "...",
  "nextSyncAt": "...",
  "lastEventId": 121,
  "counts": {"grades": 40, "messages": 12, "unreadMessages": 2},
  "version": "0.1.0"
}
```

No login, URL or provider name in the response.

## Tests

- First sync of N items → `/changes?after=0` pages through exactly N `created` events.
- `after=lastEventId` → empty, `nextCursor` unchanged.
- `type` filter, `since`, both params → 400, limit bounds.
- `/status` before any sync, after ok, after `auth_failed`.
- Contract JSON fixtures as in T-010.

## Acceptance

Tests green; README "Following changes" example matches real output.
