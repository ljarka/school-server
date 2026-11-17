# T-016 — Announcements (school notice board) in the message list

**Status:** done
**Depends on:** T-014, T-015
**ADRs:** 0015, 0002, 0003, 0006, 0007, 0010, 0011, 0013, 0014

## Summary

Read the school's **announcements** (the notice board in the parent portal) per child and serve
them **through the existing message endpoints**, marked with `type: announcement`
([ADR 0015](../adrs/0015-announcements-in-messages.md)). Announcements come from the
**JSON API v3** (per-child bearer, same as grades), not from the messages service. Today a notice
posted only on the board is invisible to the server.

Read-only, like everything else (AGENTS.md rule 1): only GET requests.

## Reference

The reference SDK's JSON API v3 client and its `api_v3` OpenAPI file: the school-notices list
endpoint and its schema (id, subject, content, start/end date, author reference, creation
date). Links in `private/journal-references.md`. Put the path string only in `ProtocolPaths.kt`.

## Domain types (`:model`)

```kotlin
enum class MessageType { MESSAGE, ANNOUNCEMENT }   // JSON: "message", "announcement"

@Serializable data class Announcement(
    val sourceId: String,      // "a_" + provider id
    val subject: String,
    val author: String?,       // resolved via the users endpoint, null if unknown
    val bodyText: String, val bodyHtml: String,   // sanitised like message bodies
    val validFrom: LocalDate?, val validTo: LocalDate?,  // Europe/Warsaw dates
    val createdAt: Instant,
)
```

`JournalClient.fetchAnnouncements(childId: String? = null): List<Announcement>?` —
`null` = "unknown / not available for this account" (same convention as `fetchGrades`),
never "no announcements" (ADR 0014).

## Scope

- `:journal`: fetch + DTO → domain mapping (pure, separately tested). Reuse the user/teacher
  lookup already used for grades. Unexpected shape →
  `JournalUnavailable("layout_changed:announcements")`.
- `:server` `store/`: add `type` (`message` | `announcement`, default `message`) and nullable
  `valid_from` / `valid_to` to the message table in the base v1 schema + updated `1.db` snapshot
  (no migration, see Decisions).
- `:server` `sync/`: fetch announcements in the same cycle as grades/messages and map them to
  message rows (ADR 0015 field mapping). Diff each type separately: the announcements diff never
  touches `m_` rows and the inbox diff never touches `a_` rows. `null` → skip the announcements diff
  (no events, rows stay). Change events use `entityType: message`.
- API (OpenAPI first, ADR 0010):
  - `MessageSummary` gets required `type` (`MessageType` enum schema) and nullable
    `validFrom` / `validTo` (`format: date`).
  - `GET /api/v1/messages` and `POST /api/v1/messages/read-all`: optional `type` query parameter
    (`message` | `announcement`); invalid value → `400 BAD_REQUEST`.
  - `GET/PUT/DELETE /api/v1/messages/{id}[/read]` work for `a_` ids unchanged.
  - Update the `MessageId` parameter description and the endpoint summaries ("messages and
    announcements").
- Docs: `README.md`, `docs/api/README.md` (explain `type`, `type=` filter, `a_` ids); remove
  announcements from "Planned"/"Later" in `docs/api/README.md`, `docs/SPEC.md` 2.2 and `docs/STATUS.md`.

## Tests

Synthetic fixtures `journal/src/test/resources/fixtures/announcements/` (invented Polish data,
from the reference SDK's schema): `notices.json`, `notices_empty.json`, `notices_html.json`
(with `<script>` / `onclick`), `notices_broken.json`.

- Mapping: subject, body, Polish characters, dates in `Europe/Warsaw`, author resolution, missing
  author → `sender: ""`, missing creation time → start date.
- Sanitising removes scripts, event handlers, styles.
- MockWebServer: only GET requests reached the API.
- Store: type round-trip, `type` filter in lists and mark-all-read.
- Sync: new / edited / removed announcement → `created` / `updated` / `deleted` with
  `entityType: message`; announcements `null` → no events and inbox diff unaffected; inbox cut
  (`complete = false`) → announcements still fully diffed.
- API contract test: mixed list sorted by `sentAt`, `type=` filter, invalid `type` → 400,
  `read-all?type=announcement` marks only announcements, `GET /messages/a_…`, `validFrom/validTo`
  null for messages.
- `SecretLeakTest` still green.

## Acceptance

`./scripts/check.sh` green; announcements appear in `GET /api/v1/messages` with
`type: announcement` and in `/changes`; live check against the real account is part of T-013
(`blocked: needs owner`).

## Decisions

- Announcements are merged into the message endpoints with a `type` field — owner, 2026-11-17,
  see ADR 0015 (the first draft had separate `/announcements` endpoints).
- **No migration** — owner, 2026-11-17: nobody runs the server yet, so the columns go straight into
  the base v1 schema (`Schema.sq`, `1.db`), like T-014. Once there are installs, rule 7 applies again.
- **No separate `Announcement` domain type** — `fetchAnnouncements` returns `List<Message>` with
  `type = ANNOUNCEMENT` and `validFrom`/`validTo`; the store, detector and API already handle `Message`.
  Alternative: own type + mapping in sync (more code, same result).
- **Field names** — the reference SDK's schema only guarantees `Id`, `StartDate`, `EndDate`, `AddedBy`;
  `Subject`, `Content`, `CreationDate` are taken from other reference clients. Missing text fields map to
  `""` (no false `layout_changed`); a missing `Id`, or no usable date at all, is `layout_changed:announcements`.
  Verify in the live run (T-013).
- **Content** — HTML if it contains a tag, otherwise plain text: escaped, line breaks → `<br>`, then the
  same sanitising as message bodies. The provider's read flag is ignored (ADR 0007).
- **`fetchAnnouncements` default** returns `null` in `JournalClient`, so other clients/fakes keep working
  and simply never touch stored announcements.
- **Hash** — `validFrom`/`validTo` are hashed only when present, so inbox message hashes are unchanged.
- **Users lookup** is fetched only when a notice has an author; access denied → authors `""`.
- **Board failure** fails the run like a grades/messages failure (backoff), but inbox and grades changes
  from the same run are still stored.

## Remaining

- Live check of the notice-board field names and dates against the real account — part of T-013,
  `blocked: needs owner`.
