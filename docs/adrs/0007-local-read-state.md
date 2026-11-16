# ADR 0007 — Message read state lives only on this server

**Status:** accepted
**Date:** 2026-11-16

## Context

v1 is read-only towards the e-journal. Opening a message body there may mark it read on the
provider side; the owner accepts that. The family still wants "unread" to mean "we haven't
looked at it in our app".

## Decision

- `message.read_at` (NULL = unread) is our own state. The provider's read flag is ignored
  and not stored.
- `PUT /api/v1/messages/{id}/read`, `DELETE /api/v1/messages/{id}/read`,
  `POST /api/v1/messages/read-all` change only our DB. They do not create change events.
- Fetching a message body from the e-journal is allowed (needed for the content).
- The server never sends any form to the e-journal except the login.

## Consequences

- Read state is shared by everyone using the same API key (one family, fine).
- An `updated` change for a message never comes from read state alone (read is not hashed).
