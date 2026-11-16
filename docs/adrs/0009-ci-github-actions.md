# ADR 0009 — CI and releases on GitHub Actions

**Status:** accepted
**Date:** 2026-11-16

## Context

Agents write most of the code. The hard rules in AGENTS.md (no provider name in public files,
module boundaries, no secrets in git, migrations verified) must be checked by a machine on
every change, not remembered. Releases must produce the image and the two install files.

## Decision

### `ci.yml` — on every push and pull request
Runs on `ubuntu-latest`, JDK 21 (`actions/setup-java`, Temurin), `gradle/actions/setup-gradle` cache.

| Step | Command | Fails when |
|---|---|---|
| Lint | `./gradlew ktlintCheck` | style violations |
| Tests | `./gradlew test` | any unit/API/migration test fails |
| Migrations | `./gradlew verifySqlDelightMigration` | a schema change has no migration |
| Boundaries | `./scripts/check-module-boundaries.sh` | `:server` imports `:journal` outside `Main.kt` |
| Neutral names | `./scripts/check-neutral-names.sh` | provider name in public files (ADR 0006) |
| Secrets | `gitleaks/gitleaks-action` | a credential-looking string is committed |
| Docker | `docker buildx build --platform linux/amd64 .` (no push) | the image doesn't build |
| Smoke | run the built image with a fake env, wait for `/health` = 200, `/api/v1/status` without key = 401 | container doesn't start or auth is off |

Test reports (`build/reports/tests`) uploaded as an artifact on failure.

### `release.yml` — on tag `v*`
1. Run the whole `ci.yml` job (reusable workflow).
2. `docker/setup-qemu-action` + `docker/setup-buildx-action`; build `linux/amd64,linux/arm64`;
   push `ghcr.io/ljarka/school-server:<version>` and `:latest` with `GITHUB_TOKEN`
   (`packages: write`). OCI labels are provider-neutral.
3. Create the GitHub release with `docker/docker-compose.yml` and `docker/example.env` as assets
   and the changelog section for that version.

### Dependency updates
Dependabot for `gradle`, `github-actions` and `docker`, weekly, grouped minor/patch.

### Local equivalent
`./scripts/check.sh` runs lint, tests, migrations, boundaries and neutral names in the same
order, so an agent can run CI before committing.

## Consequences

- No branch protection: agents push directly to `main` (owner decision 2026-11-16). CI is the
  safety net after the push, and `./scripts/check.sh` + the `commit-msg` hook before it; a red
  `main` is fixed first.
- No CI job ever talks to the real e-journal (AGENTS.md rule 5).
