# T-001 — Project skeleton, config, `/health`

**Status:** done
**Depends on:** —
**ADRs:** 0001, 0005, 0006, 0008

## Summary

Create the Gradle multi-module project, the config loader and a Ktor server that answers
`GET /health`. After this task `./gradlew :server:run` starts on port 5100 and `./scripts/check.sh` exists.

## Scope

- `settings.gradle.kts` with `:model`, `:journal`, `:server`; `gradle/libs.versions.toml`
  (Kotlin 2.x, Ktor 3.x, kotlinx.serialization, kotlinx.coroutines, kotlinx-datetime, OkHttp,
  Jsoup, SQLDelight, Logback, JUnit 5, MockWebServer, ktlint Gradle plugin). Gradle wrapper.
- `.editorconfig` copied from Picfort (ktlint rules).
- `:model`: empty package `school.model` with a placeholder `JournalClient` interface (signatures from ADR 0006).
- `:server`:
  - `config/Secret.kt` — `class Secret(private val value: String) { fun reveal() = value; override fun toString() = "***" }`.
  - `config/Config.kt` — `data class Config(journalUrl, journalAuthUrl?, login, password: Secret,
    apiKey: Secret?, syncIntervalMinutes, trustProxy: TrustProxy, dbPath, port, logLevel)`.
  - `config/ConfigLoader.kt` — `fun load(env: Map<String, String>, readFile: (String) -> String): Config`.
  - `Main.kt` — load config (on `ConfigError` print message to stderr, `exitProcess(2)`), start
    Netty on `PORT`, install ContentNegotiation, DefaultHeaders, CallLogging (method, path, status, ms only).
  - `api/HealthRoutes.kt` — `GET /health` → `200 {"status":"ok"}`, no auth.
- `scripts/check.sh` — runs `./gradlew ktlintCheck test` (later tasks append steps).
- `docker/example.env` with every variable empty and neutral comments (the only env template;
  T-002 adds the Docker-specific ones).

## Behaviour (config)

| Variable | Rule |
|---|---|
| `SCHOOL_JOURNAL_URL` | required; `https://…`, or `http://localhost`/`http://127.0.0.1` |
| `SCHOOL_JOURNAL_API_URL` | required, same URL rule (ADR 0011) |
| `SCHOOL_JOURNAL_MESSAGES_URL` | required, same URL rule (ADR 0011) |
| `SCHOOL_CHILD` | optional child id or login (ADR 0011) |
| `SCHOOL_LOGIN` | required, non-blank |
| `SCHOOL_PASSWORD_FILE` | optional; if set, file content (trailing newline stripped) is the password and wins; log `INFO "Password read from SCHOOL_PASSWORD_FILE"` |
| `SCHOOL_PASSWORD` | required unless `SCHOOL_PASSWORD_FILE` |
| `SCHOOL_API_KEY` | optional; blank → `null` + `WARN` "no API key: anyone who can reach this server can read the data" |
| `TRUST_PROXY` | empty or `cloudflare`; `cloudflare` + no API key → `ConfigError` |
| `SYNC_INTERVAL_MINUTES` | int, default 15, min 5 |
| `SCHOOL_DB_PATH` | default `school.db` |
| `PORT` | default 5100 |
| `LOG_LEVEL` | `DEBUG/INFO/WARN/ERROR` (case-insensitive), default `INFO` |

`ConfigError` lists **all** problems at once, one per line, and never contains secret values.

## Tests

- `ConfigLoaderTest`: defaults; each required var missing; bad URL; interval < 5; `_FILE` wins;
  cloudflare without key fails; several errors reported together; `Config.toString()` has no password/key.
- `HealthRoutesTest` (`testApplication`): `/health` → 200 JSON.

## Acceptance

- `./gradlew build` and `./scripts/check.sh` green on a clean checkout.
- `./gradlew :server:run` with a minimal env answers `curl localhost:5100/health`.
- Missing `SCHOOL_LOGIN` → readable message, exit code 2.
