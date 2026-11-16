# T-009 — Sync service: scheduler, lock, backoff, auth_failed, manual sync

**Status:** done
**Depends on:** T-005, T-008
**ADRs:** 0003, 0008

## Summary

Run a sync every `SYNC_INTERVAL_MINUTES` in a coroutine, one at a time, with backoff on errors
and a hard stop after a rejected login. Expose a manual trigger for `POST /api/v1/sync`.

## Scope (`:server` `sync/SyncService.kt`)

```kotlin
class SyncService(journal: JournalClient, store: Store, detector: ChangeDetector,
                  clock: Clock, config: SyncConfig, random: Random) {
    fun start(scope: CoroutineScope)          // loop
    suspend fun syncNow(): SyncResult         // used by loop and POST /sync
    fun state(): SyncState                    // for /status
}
sealed interface SyncResult { Ok(changes: Int); Skipped(reason); Failed(code); AuthFailed }
```

One sync run:
1. If `authState == AUTH_FAILED` → `AuthFailed` without any network call.
2. `Mutex.tryLock()`; busy → `Skipped("in_progress")`.
3. Insert `sync_run(status=running)`.
4. On `Dispatchers.IO`: `ensureSession`, `fetchGrades()`, `fetchMessageHeaders()`; for headers
   not in DB (or with changed header hash) → `fetchMessage(id)`; others reuse stored body.
5. Detector (complete = both lists fetched without error) → `applyChanges`.
6. Finish run `ok` with counts.

Errors:
- `AuthRejected` → `meta.auth_state = auth_failed`, run `auth_failed`, log `ERROR "E-journal
  rejected the login. Fix SCHOOL_LOGIN/SCHOOL_PASSWORD and restart."`; no more attempts until restart
  (state reset at process start).
- `JournalUnavailable` / IO → run `error` with code; backoff 1, 2, 4, 8 … max 60 min instead of
  the interval; reset after success.

Loop: first run 5 s after start; then interval + jitter 0–60 s. Cancelled cleanly on shutdown
(Ktor `ApplicationStopping`).

Manual: `syncNow()` from API allowed if last manual trigger ≥ 5 min ago, else `Skipped("too_soon")`
→ API 429 `SYNC_TOO_SOON` with `Retry-After`.

## Tests (fake `JournalClient`, fake clock, `runTest` virtual time)

- Two concurrent `syncNow()` → one runs, other `Skipped`.
- `AuthRejected` once → later calls do no network calls, state `AUTH_FAILED`.
- Unavailable 3× → delays 1, 2, 4 min; success resets.
- Only new messages trigger `fetchMessage`.
- Partial failure (grades ok, messages fail) → no `deleted` events for messages, run `error`.
- Manual sync throttle.

## Acceptance

Tests green; `Main.kt` starts the service; logs readable and secret-free.
