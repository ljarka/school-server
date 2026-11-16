# T-008 — Change detector and change events

**Status:** done
**Depends on:** T-003, T-006, T-007
**ADRs:** 0003, 0007

## Summary

Pure logic that compares a fresh snapshot with stored rows and produces rows to upsert plus
`created` / `updated` / `deleted` events; and the store method that applies it in one transaction.

## Scope (`:server` `sync/`)

- `ContentHash`: SHA-256 hex of canonical JSON (kotlinx.serialization, sorted keys) of the
  meaningful fields only.
  - Grade: everything except `sourceId`.
  - Message: sender, subject, sentAt, bodyText, attachments. **Not** `read_at`, not `bodyHtml`.
- `ChangeDetector.diff(stored: List<StoredX>, fresh: List<X>, complete: Boolean, now): Changes<X>`
  - new id → `Created(x)`
  - same id, different hash → `Updated(x, diff = mapOf(field to (old to new)))` for changed fields
  - stored (not deleted) id missing from fresh **and** `complete == true` → `Deleted(id)`
  - previously deleted id reappears → `Created` again (and `deleted_at` cleared)
- `Store.applyChanges(syncRunId, gradeChanges, messageChanges)` — one transaction: upsert rows,
  soft-delete, insert events in a stable order (grades by issuedOn then id; messages by sentAt).
- First sync: stored is empty → everything `Created` (owner decision; no special case needed —
  write a test that proves it).

## Tests (`ChangeDetectorTest`, `StoreApplyChangesTest`)

- Empty stored → all created, ordered.
- One field changed → one `Updated` with exactly that field in `diff`.
- Read state change only → no event.
- `complete=false` → never `Deleted`.
- Deleted then back → `Created`.
- Transaction: exception mid-apply → no rows and no events written.
- Event ids strictly increase across two runs.

## Acceptance

Detector is pure (no IO, clock injected); tests green.
