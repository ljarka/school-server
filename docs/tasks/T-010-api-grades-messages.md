# T-010 — API: grades, subjects, messages, read state

**Status:** done
**Depends on:** T-004, T-009
**ADRs:** 0004, 0007, 0010

## Contract first

`docs/api/openapi.yaml` is the source of truth. Also in this task:
- copy the spec into `:server` resources at build time (Gradle `processResources` from `../docs/api`);
- serve `GET /api/v1/openapi.yaml` (no key) and Swagger UI at `GET /docs` (Ktor `swagger` plugin);
- `ApiContractTest` validates every response from API tests against the spec (OpenAPI 3.1 validator);
- if the implementation needs to differ from the spec, change the spec first and record the decision.

## Summary

Implement the read endpoints and local read-state endpoints from SPEC §6.2 with stable JSON
DTOs in `:model` (the contract Picfort will reuse).

## Endpoints

| Method | Path | Notes |
|---|---|---|
| GET | `/api/v1/grades` | `subject`, `semester` (1/2), `since` (ISO instant, by `firstSeenAt`), `kind`; sorted by `issuedOn` desc |
| GET | `/api/v1/grades/subjects` | `[{subject, grades: [...], average}]`; weighted average of `countsToAverage && numericValue != null`, rounded to 2 places, `null` if none |
| GET | `/api/v1/messages` | `unread=true`, `since`; `limit` (1–100, default 50), `cursor` (opaque base64 of `sentAt|sourceId`); newest first; list items **without** body |
| GET | `/api/v1/messages/{id}` | full message incl. `bodyText`, `bodyHtml`, `attachments`, `read`, `readAt` |
| PUT | `/api/v1/messages/{id}/read` | 200 with the message; idempotent |
| DELETE | `/api/v1/messages/{id}/read` | 200 with the message |
| POST | `/api/v1/messages/read-all` | `{"marked": n}` |
| POST | `/api/v1/sync` | `202 {"status":"started"}` / `409 SYNC_IN_PROGRESS` / `429 SYNC_TOO_SOON` / `409 AUTH_FAILED` |

DTO ids are the `sourceId`s. Soft-deleted items are hidden unless `includeDeleted=true`.

## Errors

`400 BAD_REQUEST` (bad param, message names the param), `404 NOT_FOUND`,
`503 STORAGE_UNAVAILABLE` (migration failed), `503 NO_DATA_YET` (no successful sync and none stored).

## Tests

- `ApiContractTest`: for every endpoint, JSON keys and types match SPEC §5/§6 (fixed fixture
  DB, compares against `src/test/resources/contract/*.json` with ignored timestamps).
- Filters, paging (no duplicates, stable across inserts), average calculation, read/unread flows
  survive app restart (new `Store` on the same file), 404s, 400s.

## Acceptance

Tests green; SPEC §6.2 table matches implementation (update SPEC if a detail changed).
