# T-013 — End-to-end test, README check, first live run

**Status:** draft (needs the owner for the live run)
**Depends on:** T-010, T-011, T-012
**ADRs:** all

## Summary

Prove the whole thing works: an automated end-to-end test against a fake e-journal, a README
walk-through on a clean machine, and one supervised run against the real account.

## Scope

1. **E2E test** (`:server` `EndToEndTest`): MockWebServer serves login + grades + messages
   fixtures; start the real app (`Main` wiring with test config, temp DB); trigger sync; assert:
   `/changes` has all items `created`; change one grade fixture and add one message; sync again →
   exactly one `updated` and one `created`; mark message read; restart app on same DB → read state kept.
2. **SecretLeakTest** covers the E2E run at `DEBUG`.
3. **README walk-through**: follow README on a clean VM/container host with the released image
   (port 5100, Cloudflare section optional); fix every step that doesn't match.
4. **Live run (owner present)**: owner fills `.env` on the family server; check `/status`,
   compare a handful of grades and messages with the e-journal website; record new anonymised
   fixtures if the layout differs from the recorded ones; note findings in this task.
5. Tag `v0.1.0`.

## Acceptance (= SPEC §11)

All SPEC §11 checkboxes ticked, with links to the test or the live-run notes.

## Live-run notes

_(fill in during the run)_
