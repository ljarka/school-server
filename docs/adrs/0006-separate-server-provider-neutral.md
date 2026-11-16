# ADR 0006 — Separate server; public repository never names the provider

**Status:** accepted (revised 2026-11-16: repository is **public**)
**Date:** 2026-11-16

## Context

Reading the e-journal with a parent's credentials through unofficial APIs may break the
provider's terms of service. The owner does not want Picfort to depend on it, and the
repository `ljarka/school-server` is public, so nothing committed may name the provider.

## Decision

### Separate server
- School Server is a **separate repository, process and container**. Picfort only talks to it
  over HTTP if the user configures its URL and key; Picfort works fully without it.
- All provider-specific code lives in `:journal` behind a neutral interface:

  ```kotlin
  // :model
  interface JournalClient {
      /** @throws AuthRejected wrong login/password; @throws JournalUnavailable network/5xx/layout */
      fun login()
      fun fetchGrades(): List<Grade>
      fun fetchMessageHeaders(): List<MessageHeader>
      fun fetchMessage(sourceId: String): Message
  }
  ```

  Blocking functions on purpose: the sync calls them from `Dispatchers.IO`.
- `:server` uses only that interface and `:model` types. Only `Main.kt` constructs the
  `:journal` implementation (ADR 0001).

### Neutral naming everywhere
- The provider's name, domains, product names and the reference projects' names/authors are
  **forbidden in every committed file**: code, comments, tests, fixtures, docs (incl. ADRs,
  tasks, SPEC), OpenAPI, Docker files, workflows, CHANGELOG — and in commit messages, branch
  names, PR titles/bodies and image labels.
- Use neutral terms: "e-journal", "family/parent portal", "JSON API v3", "messages service",
  "the reference SDK", "the original scraper library".
- Real names, hosts and links live in **`private/journal-references.md`**, which is git-ignored.
  Agents run locally and can read it; committed docs point to it as "the private references".
- **Single exception:** `journal/src/main/kotlin/**/ProtocolPaths.kt` may contain protocol path
  segments that unavoidably include the provider's name. Nothing else in that file but path
  constants.
- `scripts/check-neutral-names.sh` scans **all tracked files** (except that one file) for a
  base64-encoded word list and fails CI on a hit. A `commit-msg` hook (installed by
  `scripts/install-git-hooks.sh`) applies the same check to commit messages.

### E-journal addresses from configuration
- `SCHOOL_JOURNAL_URL` (portal), `SCHOOL_JOURNAL_API_URL`, `SCHOOL_JOURNAL_MESSAGES_URL`: all
  **required**, no defaults in code (ADR 0011). `docker/example.env` leaves them empty with
  neutral comments.
- `:journal` builds every URL from `JournalEndpoints` + `ProtocolPaths`; tests point all three at
  MockWebServer.
- Invalid URL (not `https://`, except `http://localhost`/`127.0.0.1` for tests) → config error at start.

## Consequences

- Users must look up their e-journal's addresses themselves (the owner has them in the private file).
- Swapping or adding another e-journal later means a second `JournalClient` implementation.
- Logs from `:journal` are debug-level and say "e-journal"; never echo URLs.
