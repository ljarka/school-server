# School Server — AGENTS.md

Self-hosted **Kotlin + Ktor** server that logs in to a school e-journal with a **parent
account**, polls **grades and messages** every 15 minutes, detects changes and serves them
through a REST API protected by one API key. Read-only towards the e-journal. Later an optional
data source for the Picfort app's school screen.

This file is the index. Keep it short (< 150 lines); details live where the table says.

## Start here

1. Read this file, then [docs/STATUS.md](docs/STATUS.md) (what's next) and the parts of
   [docs/SPEC.md](docs/SPEC.md) (product spec) your task links, and [docs/api/](docs/api/README.md) for API work.
2. Take the first `ready` task from [docs/tasks/](docs/tasks/README.md). Read the ADRs it links.
3. Run `./scripts/check.sh` before you change anything; it must be green.
4. Finish with the Definition of Done below; leave the next task `ready`.

## Where knowledge lives

| Need | Read |
|---|---|
| What the product does, config | [docs/SPEC.md](docs/SPEC.md) |
| API contract (source of truth) and integration guide | [docs/api/openapi.yaml](docs/api/openapi.yaml), [docs/api/README.md](docs/api/README.md) |
| What to do next | [docs/STATUS.md](docs/STATUS.md), [docs/tasks/](docs/tasks/README.md) |
| Why things are the way they are | [docs/adrs/](docs/adrs/README.md) |
| How users install and call it | [README.md](README.md) (public, provider-neutral) |
| E-journal name, hosts, reference SDK and issues (**private, not in git**) | `private/journal-references.md` |
| Sister project (patterns, Kotlin style, ktlint rules) | https://github.com/ljarka/Picfort |

## Autonomy: decide, record, keep going

This is a non-critical family project. The owner wants the agent to **decide on its own and
implement as much as it can**, not to stop and ask.

- **Don't ask the owner for decisions.** When something is unclear, pick the simplest option that
  fits the SPEC, the ADRs and the hard rules below, and continue.
- **Always write the decision down** so it can be changed later:
  - small, local choice (a field name, a default, a library detail) → a line in the task file
    under `## Decisions` (what, why, alternatives considered);
  - anything that affects the API, storage, config, security or architecture → a new ADR in
    `docs/adrs/` with `Status: accepted (agent decision, YYYY-MM-DD)` + update `docs/adrs/README.md`;
  - API change → `docs/api/openapi.yaml` first ([ADR 0010](docs/adrs/0010-openapi-contract-first.md)).
- **Blocked by something only the owner has** (their real e-journal account for a live check,
  GitHub settings, secrets)? Do everything around it, mark that step `blocked: needs owner`
  in the task and STATUS, and move on to the next task.
- Finish tasks fully when possible; if not, leave a clear `## Remaining` list in the task.
- The hard rules below are the only things you must not change on your own. Changing one needs
  an ADR **and** the owner's explicit OK.

## Hard rules

1. **Never write to the e-journal.** Only the login form is ever posted. No sending, deleting
   or marking messages. Read state lives only in our DB ([ADR 0007](docs/adrs/0007-local-read-state.md)).
2. **The repository is public: the provider is never named in it.** Not in docs, code,
   comments, tests, fixtures, commit messages, branch names or PR texts. Say "e-journal",
   "parent portal", "JSON API", "messages service", "the reference SDK". Real names, hosts and
   links live only in the git-ignored `private/journal-references.md`. Hosts come from `.env`
   (no defaults in code). The only exception: protocol path strings in
   `journal/src/main/kotlin/**/ProtocolPaths.kt` ([ADR 0006](docs/adrs/0006-separate-server-provider-neutral.md)).
   `check-neutral-names.sh` enforces it in CI.
3. **Secrets never leak.** Password and API key only as `Secret`; never in logs (any level),
   responses, exceptions or test snapshots ([ADR 0008](docs/adrs/0008-credentials.md)).
4. **Don't get the account locked.** Rejected login → `auth_failed`, no more login attempts
   until restart ([ADR 0003](docs/adrs/0003-polling-and-change-detection.md)).
5. **Tests and CI never touch the real e-journal.** Replay fixtures with MockWebServer. A live
   run happens only when the owner asks, with the owner's own `.env`.
6. **Fixtures are synthetic or anonymised.** Until the owner's live run (T-013), build JSON
   fixtures from the reference SDK's OpenAPI schemas/examples and its tests, with invented
   Polish names and data. Later recordings must be anonymised (names, teachers, bodies,
   logins, ids, tokens) before committing. Real children's data never goes in git.
7. **Data survives updates.** Every schema change = new `.sqm` migration + updated schema
   snapshot + migration test ([ADR 0002](docs/adrs/0002-sqlite-storage.md)).
8. **Keep install trivial.** One container, one `.env`, `/data` volume, port 5100
   (picfort-server uses 5000) ([ADR 0005](docs/adrs/0005-docker-distribution.md)).
9. **Module boundaries.** `:model` depends on nothing; `:journal` only on `:model`; `:server`
   touches `:journal` only in `Main.kt` ([ADR 0001](docs/adrs/0001-kotlin-ktor-stack.md)).

## Module map

| Module / path | Scope | Status |
|---|---|---|
| `:model` | `Grade`, `Message`, `MessageHeader`, `ChangeEvent`, API DTOs (`@Serializable`), `JournalClient`, `AuthRejected`, `JournalUnavailable` | done |
| `:journal` | OkHttp + Jsoup implementation of `JournalClient`: login flow, grades and messages parsers; fixtures in `src/test/resources/fixtures/` | done |
| `:server` `config/` | `Config` from env, `Secret`, validation, `*_FILE` support | done |
| `:server` `api/` | Ktor routing under `/api/v1`, StatusPages error format, `Cache-Control: no-store`, `/api/v1/openapi.yaml`, Swagger UI at `/docs` | done |
| `:server` `auth/` | `X-School-Key` plugin, wrong-key limiter, client IP (`CF-Connecting-IP`) | done |
| `:server` `store/` | SQLDelight database, `.sq`/`.sqm`, backup-before-migrate, `Store` facade | done |
| `:server` `sync/` | `ChangeDetector`, `SyncService` (coroutine loop, `Mutex`, backoff, `auth_failed`) | done |
| `:server` `Main.kt` | Wiring: config → store → journal client → sync → Ktor (Netty) | done |
| `docker/`, `Dockerfile`, `docker-entrypoint.sh` | Install files and image ([ADR 0005](docs/adrs/0005-docker-distribution.md)) | done |
| `.github/workflows/` | `ci.yml`, `release.yml` ([ADR 0009](docs/adrs/0009-ci-github-actions.md)) | done |
| `scripts/` | `check.sh`, `check-neutral-names.sh`, `check-module-boundaries.sh` | done |

Update the Status column when a module lands.

## Conventions

- Kotlin 2.x, JDK 21, Gradle Kotlin DSL, versions only in `gradle/libs.versions.toml`.
- ktlint (Picfort's `.editorconfig`); `./gradlew ktlintFormat` fixes most issues.
- No DI framework; constructor injection, wired in `Main.kt`. Pass `Clock` (kotlinx-datetime)
  everywhere time matters.
- Errors as types: `sealed interface SyncResult`, exceptions only at the `JournalClient` edge.
- Times: stored and returned as ISO 8601 UTC (`2026-11-16T14:05:00Z`); e-journal dates parsed
  in `Europe/Warsaw`.
- API JSON camelCase; SQL snake_case. Errors: `{"error": {"code": "UPPER_SNAKE", "message": "..."}}`.
- Everything in the repo is English: code, comments, docs, commits.
- One env template only: `docker/example.env` (local runs: `cp docker/example.env .env`).
- Tests: JUnit 5 + kotlin.test, name by behaviour
  (`` `wrong key from one ip does not block other ips`() ``). API tests with Ktor
  `testApplication {}`; journal tests with MockWebServer + fixtures; DB tests on a temp file.

## Definition of Done (every task)

- [ ] `./scripts/check.sh` green (ktlint, tests, migrations, boundaries, neutral names).
- [ ] New behaviour has tests; endpoints covered by the API contract test.
- [ ] Touching logging/config/journal → `SecretLeakTest` still covers it.
- [ ] `README.md` updated if user-visible behaviour or config changed.
- [ ] New decision → new ADR; changed decision → old ADR marked superseded.
- [ ] Task `Status: done`; `docs/STATUS.md` updated with the commit hash; module Status above updated.

## Git workflow

- Work directly on `main` and push (no branch protection, no PRs required — owner decision 2026-11-16).
- Before every push: `./scripts/check.sh` green. Don't push a red build; if CI goes red after
  a push, fixing it is the next thing you do.
- One task = one or a few focused commits. Message: `T-XXX: <what changed>` (imperative,
  English, neutral — no provider names; the `commit-msg` hook checks it).
- Never commit `.env`, `private/`, `data/`, real fixtures or tokens.

## Environment

- JDK 21 (Temurin). `./gradlew build`.
- Run locally: `set -a; . ./.env; set +a; ./gradlew :server:run` → http://localhost:5100.
- Docker: `docker compose -f docker/docker-compose.yml up --build`.
- Never use the family's real `.env` for experiments; ask the owner first.
