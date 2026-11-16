# ADR 0001 — Kotlin + Ktor

**Status:** accepted
**Date:** 2026-11-16

## Context

The e-journal reference implementations (private references) are TypeScript/JavaScript, so
any language means a port. The Picfort Android app is Kotlin, and the owner plans to move
picfort-server off Python/Flask to Kotlin + Ktor. School Server is the first server on that
stack (owner decision 2026-11-16) and should set the patterns picfort-server will reuse.

## Decision

- **Kotlin 2.x on JDK 21**, Gradle (Kotlin DSL) with a version catalog (`gradle/libs.versions.toml`).
- **Ktor server** with the **Netty** engine; plugins: ContentNegotiation (kotlinx.serialization
  JSON), StatusPages (error format), CallLogging (no bodies, no headers), DefaultHeaders.
- **OkHttp** with an in-memory `CookieJar` for the e-journal; **Jsoup** to parse HTML.
  No headless browser.
- **kotlinx.coroutines** for the sync scheduler (one `CoroutineScope` owned by the app,
  cancelled on shutdown); `Mutex` for "one sync at a time".
- **kotlinx.serialization** for all JSON; **kotlinx-datetime** for dates.
- Logging: SLF4J + Logback, JSON-free plain lines, level from `LOG_LEVEL`.
- No DI framework: constructor injection, wiring by hand in `Main.kt` (small app; same idea as
  Picfort's thin `:app`).
- Tests: **JUnit 5 + kotlin.test**, Ktor `testApplication {}` for the API, **OkHttp
  MockWebServer** to replay e-journal fixtures, a fake `Clock`. **ktlint** via the Gradle plugin
  (same rules as Picfort, `.editorconfig`).

### Gradle modules

| Module | Scope | May depend on |
|---|---|---|
| `:model` | Domain types (`Grade`, `Message`, `ChangeEvent`), API DTOs, `JournalClient` interface. Pure Kotlin, kotlinx.serialization only, so Picfort can reuse the DTOs later. | — |
| `:journal` | The e-journal implementation of `JournalClient` (OkHttp, Jsoup). Only module that knows the provider (ADR 0006). | `:model` |
| `:server` | Ktor app, config, auth, store, change detector, sync, `Main.kt`. Uses `:journal` **only in `Main.kt`** to construct the client. | `:model`, `:journal` |

`scripts/check-module-boundaries.sh` fails if any file in `:server` other than `Main.kt`
imports from the `journal` package.

## Consequences

- Image is bigger and uses more RAM than Python (~150–250 MB); acceptable on the family server.
- Build needs Gradle; the Dockerfile is multi-stage and built for `amd64` and `arm64` (ADR 0005).
- `:model` can later be published or copied into Picfort as the shared API contract.
