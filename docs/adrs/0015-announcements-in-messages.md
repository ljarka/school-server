# ADR 0015 — Announcements are served as messages with `type: announcement`

**Status:** accepted (owner decision, 2026-11-17)
**Date:** 2026-11-17

## Context

The e-journal has two separate places where the school talks to parents: the inbox in the
messages service (ADR 0011) and the school notice board (announcements) in the JSON API v3.
v1 reads only the inbox, so a notice posted only on the board never reaches the family.
For the family (and Picfort) both are "something the school wrote to us", read in one list.

## Decision

- Announcements are returned **by the existing message endpoints**, not by separate ones:
  `GET /api/v1/messages`, `GET /api/v1/messages/{id}`, `PUT|DELETE /api/v1/messages/{id}/read`,
  `POST /api/v1/messages/read-all`.
- `MessageSummary` (and so `Message`) gains a required field `type: message | announcement`.
  Clients must already tolerate unknown enum values (ADR 0010), so more types can follow.
- `GET /api/v1/messages` and `POST /api/v1/messages/read-all` accept an optional `type=` filter;
  without it both types are returned / marked.
- Announcement ids are `a_<provider id>` (messages stay `m_<id>`), so ids never collide.
- Field mapping for announcements: `sender` = author's name (`""` if the author can't be resolved),
  `subject`, `sentAt` = creation time (or the start date at 00:00 Europe/Warsaw if there is no time),
  `hasAttachments = false`, `attachments = []`, body sanitised like message bodies.
- `MessageSummary` gains `validFrom` / `validTo` (`date`, nullable): the board visibility window;
  always `null` for `type: message`.
- Change feed: announcements use `entityType: message`; `entity.type` tells them apart.
  `EntityType` does not grow.
- Storage: the same message table with `type` (default `message`), `valid_from`, `valid_to` columns,
  added to the base v1 schema without a migration (owner, 2026-11-17: no installs yet, as in ADR 0013). Sync fetches
  inbox and board separately and diffs each type on its own, so a failed or partial board fetch never
  deletes inbox messages and vice versa (ADR 0014). Read state stays local (ADR 0007).

## Consequences

- Existing clients start seeing announcements in the message list without changes; ones that want
  only the inbox pass `type=message`.
- One list, one unread counter, one read-state flow for Picfort.
- An announcement that leaves the board (deleted by the school) becomes `deletedAt` like a message;
  one whose `validTo` has passed but is still listed by the e-journal stays active.
- Alternative considered: separate `/api/v1/announcements` endpoints and `EntityType: announcement`
  (first draft of T-016) — rejected by the owner in favour of a single list.
