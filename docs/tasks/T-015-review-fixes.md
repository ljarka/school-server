# T-015 — Fixes from the code review (2026-11-17)

**Status:** done
**Depends on:** T-014
**ADRs:** 0003, 0013, 0014

## Summary

Fix the issues found when the owner asked for a review of the agent-written code.

## Behaviour changes

1. Rejected password during a token refresh → `AuthRejected` (was retried forever). ADR 0014.
2. Grades `AccessDeny` → `fetchGrades` returns `null`; stored grades are kept, no `deleted` events.
3. Message listing cut at a safety limit → `complete = false`; no deletions from it.
4. `SyncService` rethrows `CancellationException` (run closed as `cancelled`, not `unexpected_error`);
   runs left `running` by a crash are closed as `interrupted` at startup; all DB work runs on the
   IO dispatcher.
5. SQLite: `foreign_keys` and `busy_timeout=5000` as connection properties (the driver opens one
   connection per thread, so a one-off PRAGMA reached only the first).
6. Interactive-step detection looks at captcha widgets / one-time-code inputs / explicit JSON flags,
   not at any `2fa` substring. Gateway steps before the password POST treat 401/403 as retryable.
7. Instants stored in a fixed millisecond format (`yyyy-MM-ddTHH:mm:ss.SSSZ`) so SQL text order =
   time order.
8. `GET /messages` paginates in SQL (keyset on `sent_at, source_id`) instead of loading 10,000 rows.
9. Unknown `?child=` → `404 CHILD_NOT_FOUND`.
10. `/changes` skips (and logs) an event whose entity is missing instead of failing the whole page.
11. Manual sync: one atomic `triggerManualSync` (cooldown + lock + launch) replaces the duplicated
    check in the route and the service.
12. `HttpJournalClient` split into `PortalAuth` / `GatewayAuth` behind `JournalAuth`, shared response
    checks in `JournalHttp.kt`.
13. `Main.kt` resolves journal endpoints without `!!`.
14. `markAllMessagesRead` counts with `UPDATE … RETURNING` (atomic).
15. `/status` `version` comes from the jar manifest (`-PappVersion`, set from the release tag via the
    Dockerfile `APP_VERSION` build arg); local builds report `dev`.
16. Docs: README (domain config, school-issued login, children, errors), OpenAPI (`CHILD_NOT_FOUND`),
    ADR 0013 note on the schema squash, ADR 0014, T-014 task file, STATUS.

## Tests

- `InteractiveStepTest` (new); `HttpJournalClientLoginTest`: rejected re-login during refresh,
  gateway 403 before password, `2fa` link not interactive.
- `HttpJournalClientMessagesTest`: cut listing is incomplete. `HttpJournalClientGradesTest`: AccessDeny → `null`.
- `SyncServiceTest`: denied grades keep data, incomplete listing keeps messages, rejected refresh stops
  syncing, cancellation, manual trigger cooldown / in-progress.
- `StoreTest`: sub-second ordering, keyset pages, mark-all count, interrupted runs, child selector.
- `ApiContractTest`: unknown child → 404.

## Decisions

- `fetchGrades` returns `null` rather than throwing a dedicated exception: "not available" is a normal
  state for some accounts, not an error, and must not count toward backoff.
- Stale `running` runs are closed in `Main.kt` at startup rather than in `SyncService`, so tests that
  construct the service are unaffected.
- No schema migration: only queries changed (`Schema.sq` tables untouched, `1.db` unchanged).
- Existing pre-release databases may hold instants in the old variable format; ordering within the
  same second may be off for those old rows only. Not migrated (no release yet).

## Remaining

- Live check against the real account (T-013) — `blocked: needs owner`.
