# ADR 0011 — E-journal data source: parent portal account + JSON APIs, no HTML scraping

**Status:** accepted
**Date:** 2026-11-16
**Decided by:** owner ("use the better method where possible"; the school uses the newer,
separate messages service), evidence gathered on 2026-11-16.

Real names, hosts and links for everything below: git-ignored `private/journal-references.md`
(the repository is public, ADR 0006).

## Context

The e-journal can be reached in three ways:

| Way | Login | Data | Messages |
|---|---|---|---|
| Legacy web pages (original scraper library) | school-issued login | scraped HTML | old in-page inbox only |
| Gateway JSON API v2 (unmerged PR of the scraper library) | school-issued login → cookies | JSON | not the new messages service |
| **Family portal (parent account)** | portal e-mail + password | **JSON API v3** with a per-child bearer token | **new, separate messages service** (JSON) |

Evidence (links in the private references):
- An issue in the scraper library reports that HTML selectors return empty data on current
  accounts, and documents the portal flow: portal login → "linked child accounts" endpoint →
  JSON API v3, one access token per child.
- Another issue documents the new messages service and its JSON endpoints
  (`<messages>/api/inbox/messages/<id>`).
- The **reference SDK** (TypeScript, MIT, maintained, CI, last push 2026-11-16) implements the
  portal flow, JSON API v3 and the new messages service, and publishes OpenAPI files for them.
- The owner's school uses the new messages service, which requires portal auth.

## Decision

- **Primary reference: the reference SDK** (auth flow, endpoints, models, token refresh) and
  its OpenAPI files. The original scraper library is historical only.
- **Clean-room port:** read the SDK and its OpenAPI files to learn the protocol, then write our
  own Kotlin. Don't copy source code (so no third-party notice naming the provider is needed in
  this public repo).
- **Login:** parent portal account (e-mail + password) in `SCHOOL_LOGIN` / `SCHOOL_PASSWORD`.
  Flow: portal login → linked child accounts → pick the child → bearer token for that child.
- **Grades:** JSON API v3 (grades + the lookups needed to resolve ids: subjects, categories,
  teachers, comments).
- **Messages:** the new messages service's JSON API, with the child's portal auth as the SDK
  does it. No legacy in-page inbox.
- **No HTML scraping** in v1. Gateway API v2 is not used (it can't reach the new messages
  service); it may be added later as another backend for school-issued logins.
- **Children:** `SCHOOL_CHILD` (optional) selects the child by id or login; empty → first child,
  `WARN` if there are several. One child per server in v1.
- **Token lifecycle:** on `401` from the API or the messages service, refresh the child token
  once; if the portal session is gone, log in again once; then fail the run. Portal login
  rejected → `AuthRejected` → `auth_failed` (ADR 0003).
- **2FA / captcha / consent screens** → `AuthRejected("interactive_step_required")` with a clear
  log message; not automated.
- **Addresses:** all three hosts come from `.env` and have **no defaults in code** (public repo):
  `SCHOOL_JOURNAL_URL` (portal), `SCHOOL_JOURNAL_API_URL`, `SCHOOL_JOURNAL_MESSAGES_URL` — all
  required. Values are in the private references; README tells users to use the addresses of
  their own e-journal. Replaces `SCHOOL_JOURNAL_AUTH_URL`.
- **Protocol paths** (e.g. login path segments that contain the provider's name) live in one
  file, `journal/src/main/kotlin/**/ProtocolPaths.kt`, which is the only file excluded from the
  neutral-names check (ADR 0006).
- **Models:** generate or hand-write Kotlin DTOs in `:journal` from the SDK's OpenAPI files
  (agent's choice, recorded in T-005). Generated code must pass the neutral-names check
  (rename packages/classes). Provider DTOs never leave `:journal`.

## Consequences

- JSON instead of HTML: far less breakage on UI changes; fixtures are JSON files.
- Unofficial API: can still change; the reference SDK's releases are the early-warning signal.
- Several children are possible later by running one sync per child (v2).
- Supersedes the HTML-based plans previously in T-006/T-007.
