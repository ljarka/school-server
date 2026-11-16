# T-005 — Journal client: portal login, child selection, tokens

**Status:** done
**Depends on:** T-001
**ADRs:** 0011, 0003, 0006, 0008

## Summary

Implement authentication in `:journal` following the **reference SDK** (ADR 0011): log in to
the parent portal, read the linked child accounts, pick the configured child and obtain/refresh
its bearer token. Distinguish a rejected password from a network problem precisely, because a
rejected password must stop all retries (account lockout risk).

## Reference (read before coding)

All links are in the git-ignored `private/journal-references.md`:
- the reference SDK — its README "Public Interface", portal client, session and token refresh,
  and the messages-service client;
- its OpenAPI files for JSON API v3 and the messages service;
- the issue describing the portal flow.

Flow (verify against the SDK source; it is the authority):
1. `GET <portal><login page path>` (cookies, CSRF token if any).
2. `POST <portal><login action path>` with e-mail + password.
3. `GET <portal><linked child accounts path>` → children with access token, id, login, name.
4. Use the chosen child's token as `Authorization: Bearer` for JSON API v3.
5. Messages service: obtain auth exactly as the SDK's messages client does.

Path strings go into `ProtocolPaths.kt` only (ADR 0006/0011). Clean-room: don't copy SDK code.

## Scope (`:journal`)

- `JournalEndpoints(portal, api, messages)` from `SCHOOL_JOURNAL_URL`,
  `SCHOOL_JOURNAL_API_URL`, `SCHOOL_JOURNAL_MESSAGES_URL` (all required, no defaults).
  Update T-001 config, `docker/example.env`, README and SPEC §9 accordingly.
- `PortalSession`: OkHttp with in-memory `CookieJar`, timeouts 30 s, ~500 ms between requests,
  redirects followed only to the three configured hosts.
- `ChildSelector`: `SCHOOL_CHILD` = id or login; empty → first child + `WARN` when several;
  not found → `AuthRejected("child_not_found")` and the available child **ids** (no names) in the log.
- `TokenProvider`: caches the child's bearer; on `401` → refresh once; portal session expired →
  one fresh login; still failing → `JournalUnavailable("auth_refresh_failed")`.
- `HttpJournalClient.login()` outcomes:
  - success → child token present;
  - wrong credentials → `AuthRejected("rejected")`;
  - 2FA/captcha/consent page → `AuthRejected("interactive_step_required")`;
  - IO, timeout, 5xx, unexpected shape → `JournalUnavailable(code)`; codes never contain response bodies.
- Logging in `:journal`: DEBUG only, says "e-journal", no URLs, no bodies, no cookies, no tokens.
- Decide (record below) whether DTOs are generated from the SDK's OpenAPI files (e.g.
  openapi-generator `kotlin` + kotlinx.serialization, with neutral package/class names) or
  hand-written for the few endpoints used.

## Tests (MockWebServer serving portal, API and messages paths)

- Happy path: login → child accounts → token used on the next API call.
- Wrong password → `AuthRejected`; exactly **one** login POST made.
- 503 at any step → `JournalUnavailable`, never `AuthRejected`.
- Several children: selection by id, by login, default first + warning; unknown child → error.
- Expired token (401) → one refresh, retry succeeds; second 401 → `JournalUnavailable`.
- Redirect to a foreign host → `JournalUnavailable("unexpected_redirect")`.
- `SecretLeakTest`: with DEBUG logging, password and bearer token never appear.
- JSON fixtures in `journal/src/test/resources/fixtures/auth/`: **synthetic**, built from the
  reference SDK's OpenAPI schemas/examples and tests (fake tokens, invented data, no provider
  names). Replaced/extended with anonymised real responses in T-013.

## Acceptance

Tests green; `check-neutral-names.sh` green; no class outside `:journal` refers to provider DTOs.
Live check against the owner's account: `blocked: needs owner` → T-013.

## Decisions

- **DTOs:** Hand-written `@Serializable` DTOs (`ignoreUnknownKeys = true`) in `:journal` rather than OpenAPI codegen, keeping zero generated provider names and minimal build complexity.

## Remaining

- Live check against the owner's account: `blocked: needs owner` (deferred to T-013).
