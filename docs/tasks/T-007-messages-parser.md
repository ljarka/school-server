# T-007 — Messages from the new messages module

**Status:** done
**Depends on:** T-005
**ADRs:** 0011, 0003, 0006, 0007

## Summary

Implement `fetchMessageHeaders()` (received inbox, all pages) and `fetchMessage(sourceId)`
using the **new, separate messages module's JSON API** (the owner's school uses it; ADR 0011).

## Reference

The reference SDK's messages-service client (list with `afterId` / `page` / `limit` options,
get by id, unread list) and its messages-service OpenAPI file; plus the "new messages module"
issue. All links in `private/journal-references.md`. Endpoint shape:
`<messages>/api/inbox/messages/<id>`.

**Never port** sending, deleting, archiving or marking read (AGENTS.md rule 1). Only GET requests.

## Domain types (`:model`)

```kotlin
@Serializable data class MessageHeader(
    val sourceId: String,           // "m_" + message id
    val sender: String, val subject: String,
    val sentAt: Instant, val hasAttachments: Boolean,
)
@Serializable data class Message(
    val sourceId: String, val sender: String, val subject: String, val sentAt: Instant,
    val bodyText: String, val bodyHtml: String, val attachments: List<Attachment>,
)
@Serializable data class Attachment(val name: String)
```

## Scope

- Paging: follow the API's paging until exhausted; hard cap 50 pages / 5 000 messages.
  Use `afterId` for incremental reads if it is reliable (record the decision).
- Dates: if the API gives local `YYYY-MM-DD HH:mm:ss`, parse in `Europe/Warsaw` → `Instant`.
- Body: take the HTML body if the API offers it (else text); sanitise HTML with Jsoup
  `Safelist.basic()` + `br`, links `rel=noopener`; `bodyText` from the sanitised HTML with
  line breaks kept.
- The provider's read flag is ignored (ADR 0007). Opening a message via the API may mark it read
  there — accepted by the owner.
- Unexpected JSON shape → `JournalUnavailable("layout_changed:messages")`; truly empty inbox → empty list.
- Mapping DTO → domain is a pure, separately tested function.

## Tests

JSON fixtures `journal/src/test/resources/fixtures/messages/` — **synthetic** (from the
reference SDK's messages-service OpenAPI schemas/examples, invented data) until T-013 adds
anonymised real ones: `inbox_page1.json`,
`inbox_page2.json`, `inbox_empty.json`, `message_plain.json`, `message_with_attachments.json`,
`message_html_body.json` (contains `<script>` and `onclick` to prove sanitising), `inbox_broken.json`.

- Paging collects all pages without duplicates; cap respected.
- Sender, subject, date (timezone), Polish characters, attachment names.
- Sanitising removes scripts, event handlers, styles.
- MockWebServer asserts that **only GET** requests reached the messages API.

## Acceptance

Tests green; zero non-GET requests from message code.

## Decisions

- **Paging:** Page-based full inbox scan (`page=1, 2, ...`, cap 50 pages / 5,000 messages) rather than `afterId` incremental reads, because change detection (T-008) needs the full active set to detect `deleted` messages.

## Remaining
