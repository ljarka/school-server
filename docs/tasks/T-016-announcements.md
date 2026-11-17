# T-016 — Announcements (school notice board)

**Status:** ready
**Depends on:** T-014, T-015
**ADRs:** 0002, 0003, 0006, 0007, 0010, 0011, 0013, 0014

## Summary

Read the school's **announcements** (the notice board in the parent portal) per child, detect
changes and serve them under `/api/v1/announcements`. Announcements are a separate data source
from messages: they come from the **JSON API v3** (per-child bearer, same as grades), not from
the messages service. Today a notice posted only on the board is invisible to the server.

Read-only, like everything else (AGENTS.md rule 1): only GET requests.

## Reference

The reference SDK's JSON API v3 client and its `api_v3` OpenAPI file: the school-notices list
endpoint and its schema (id, subject, content, start/end date, author reference, creation
date). Links in `private/journal-references.md`. Put the path string only in `ProtocolPaths.kt`.

## Domain types (`:model`)

```kotlin
@Serializable data class Announcement(
    val sourceId: String,      // "a_" + provider id
    val subject: String,
    val bodyText: String,      // sanitised, line breaks kept
    val author: String?,       // resolved via the users endpoint, null if unknown
    val validFrom: LocalDate?, // board visibility window, Europe/Warsaw dates
    val validTo: LocalDate?,
    val createdAt: Instant,
)
```

`JournalClient.fetchAnnouncements(childId: String? = null): List<Announcement>?` —
`null` = "unknown / not available for this account" (same convention as `fetchGrades`),
never "no announcements" (ADR 0014).

## Scope

- `:journal`: fetch + DTO → domain mapping (pure, separately tested). Reuse the user/teacher
  lookup already used for grades. Content may contain HTML: sanitise like message bodies
  (Jsoup, scripts/handlers/styles removed). Unexpected shape →
  `JournalUnavailable("layout_changed:announcements")`.
- `:server` `store/`: new `announcement` table keyed by `(child_id, source_id)`, new `.sqm`
  migration + schema snapshot + migration test (rule 7). Local read state like messages (ADR 0007).
- `:server` `sync/`: fetch announcements in the same cycle as grades/messages; `ChangeDetector`
  emits `created` / `updated` / `deleted` with entity type `announcement`. `null` from the client
  → keep stored rows untouched (ADR 0014).
- API (OpenAPI first, ADR 0010):
  - `GET /api/v1/announcements?child=` — list, newest first; optional `active=true` filter
    (today within `validFrom..validTo`).
  - `GET /api/v1/announcements/{id}` — one announcement.
  - `PUT /api/v1/announcements/{id}/read`, `DELETE` same path, `POST /api/v1/announcements/read-all`
    — local read state, same semantics as messages.
  - `EntityType` enum gains `announcement` (additive; clients already must tolerate unknown values).
  - `/status` reports announcements sync like the other sources.
- `README.md` and `docs/api/README.md`: document the endpoints; remove announcements from
  "Planned" in `docs/api/README.md`, `docs/SPEC.md` 2.2 and `docs/STATUS.md` "Later".

## Tests

Synthetic fixtures `journal/src/test/resources/fixtures/announcements/` (invented Polish data,
from the reference SDK's schema): `notices.json`, `notices_empty.json`, `notices_html.json`
(with `<script>` / `onclick`), `notices_broken.json`.

- Mapping: subject, body, Polish characters, dates in `Europe/Warsaw`, author resolution, missing author.
- Sanitising removes scripts, event handlers, styles.
- MockWebServer: only GET requests reached the API.
- Store: migration test from the previous schema; read state per child.
- `ChangeDetector`: new / edited / removed announcement → correct events; `null` fetch → no deletes.
- API contract test covers all new endpoints, `?child=`, `active=true`, 404, auth.
- `SecretLeakTest` still green.

## Acceptance

`./scripts/check.sh` green; announcements appear in `/api/v1/announcements` and in `/changes`;
live check against the real account is part of T-013 (`blocked: needs owner`).

## Decisions
<!-- agent fills in: decision — why — alternatives -->

## Remaining
<!-- anything not finished, or `blocked: needs owner` -->
