# T-012 — CI: checks, neutral names, module boundaries, GHCR release

**Status:** ready
**Depends on:** T-002 (image); can start right after T-001 with the non-Docker steps
**ADRs:** 0009, 0006, 0001, 0005

## Summary

Make every hard rule in AGENTS.md a machine check, run it on every push/PR, and publish the
multi-arch image plus install files on a version tag.

## Scope

- `scripts/check-neutral-names.sh`: case-insensitive grep for the words listed in
  `private/journal-references.md` (stored **base64-encoded inside the script** so the script
  itself doesn't trip the check) over **all tracked files** (`git ls-files`), excluding only
  `journal/src/main/kotlin/**/ProtocolPaths.kt` (ADR 0006). Prints file:line, exits 1 on a hit.
  With `--message <file>` it checks a commit message instead.
- `scripts/install-git-hooks.sh`: installs a `commit-msg` hook running the check on the message.
- CI step `npx @redocly/cli lint docs/api/openapi.yaml` (ADR 0010).
- `scripts/check-module-boundaries.sh`: fail if `model/` imports `school.journal` or
  `school.server`; if `journal/` imports `school.server`; if any `server/src` file other than
  `Main.kt` imports `school.journal`.
- `scripts/check.sh`: `ktlintCheck`, `test`, `verifySqlDelightMigration`, both scripts; stops at first failure.
- `.github/workflows/ci.yml` (push + PR): steps from ADR 0009 table incl. gitleaks and Docker
  build + smoke (`/health` 200, `/api/v1/status` without key 401 with `SCHOOL_API_KEY=test`).
  Uses `workflow_call` too, so release can reuse it.
- `.github/workflows/release.yml` (tag `v*`): reuse CI, QEMU + buildx, push
  `ghcr.io/ljarka/school-server:{version,latest}` for amd64+arm64, create release with
  `docker/docker-compose.yml` and `docker/example.env`.
- `.github/dependabot.yml`: gradle, github-actions, docker; weekly; grouped.
- `CHANGELOG.md` (Keep a Changelog), release body taken from it.

## Tests

- Add a temporary forbidden word to `README.md` locally → script fails; revert.
- Add `import school.journal...` to a server file → boundaries script fails; revert.
- Push a branch → CI green; tag `v0.0.1-test` on a fork or with `workflow_dispatch` dry-run → image and release created.

## Acceptance

CI green on `main`; hooks installed by `scripts/install-git-hooks.sh` (also a `pre-push` hook
running `./scripts/check.sh`).
