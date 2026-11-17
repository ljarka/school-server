# ADR 0014 — Sync never deletes on partial data; rejected credentials always stop it

**Status:** accepted (agent decision, 2026-11-17)
**Date:** 2026-11-17

## Context

A code review found three ways the sync could report false changes or keep logging in:

1. When the e-journal answered `403 AccessDeny` for grades, the adapter returned an empty list. The
   change detector treated it as a complete listing, so every stored grade became `deleted`, and came
   back as `created` on the next good sync (a burst of false notifications in Picfort).
2. The message inbox is read up to a safety limit (pages / total). A cut listing was still diffed as
   complete, so messages beyond the limit became `deleted`.
3. When a token refresh fell back to a full re-login and the e-journal rejected the password, the
   adapter wrapped `AuthRejected` into `JournalUnavailable`. The sync backed off and logged in again,
   forever, which risks locking the account (AGENTS.md rule 4, ADR 0003).

## Decision

- `JournalClient.fetchGrades` returns `List<Grade>?`; `null` = the e-journal denies the grades
  resource for this account. The sync then skips the grades diff for that child (stored grades stay,
  no events) and the run still counts as successful.
- `JournalClient.fetchMessageHeaders` returns `MessageHeaderListing(headers, complete)`. `complete` is
  `false` when a safety limit was hit; the diff then reports new/changed messages but no deletions.
- `AuthRejected` is never wrapped. Any login attempt that the e-journal rejects, including one made
  inside a token refresh, reaches `SyncService` as `AuthRejected` → `auth_failed` until restart.
- Gateway login steps **before** the password is posted treat `401/403` as `JournalUnavailable`
  (retryable; e.g. a WAF page), because they say nothing about the credentials. Steps after it keep
  treating them as `AuthRejected`.
- "Interactive step required" is detected from concrete elements (captcha widgets, one-time-code
  inputs, explicit JSON flags), not from any occurrence of words like `2fa` in the page.

## Consequences

- A child whose account never sees grades keeps an empty grades list and no error in `/status`.
- If a family really has more than 5,000 messages, the oldest ones are no longer tracked for deletion;
  a warning is logged.
- The `JournalClient` interface changed (both implementations and test fakes updated).
