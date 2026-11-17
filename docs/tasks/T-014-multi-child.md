# T-014 — Multi-child sync and child filter

**Status:** done
**Depends on:** T-009, T-010, T-011
**ADRs:** 0013

## Summary

Sync every child linked to the parent account (unless `SCHOOL_CHILD` restricts it to one), store
`child_id` on grades, messages and change events, expose `GET /api/v1/children` and an optional
`?child=<id_or_login>` filter on the list endpoints and `/changes`.

(Task file written after the fact, during the T-015 review; the work landed in the commits below.)

## Scope

- `:model` — `ChildAccount`, `JournalClient.listChildren()`, `childId` on fetch methods and DTOs.
- `:journal` — portal accounts → children; gateway login → one child (the login itself).
- `:server` — `child_account` table, `child_id` columns (base v1 schema), `childrenRoutes`, `child`
  query parameter, `childId` in responses.

## Tests

- `ApiContractTest` — two children, filter by id and by login, `/changes?child=`.
- `HttpJournalClientLoginTest` — `listChildren` returns all linked accounts.

## Acceptance

- [x] `./scripts/check.sh` green.
- [x] OpenAPI and `docs/api/README.md` describe `/children` and `?child=`.

## Decisions

- Multi-child columns folded into schema v1 instead of a v2 migration, since nothing had been
  released (see ADR 0013, Consequences). Alternative: keep `1.sqm`; rejected as noise before v1.
