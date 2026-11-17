# Architecture Decision Records

One file per decision: `NNNN-<slug>.md`. Status: `proposed`, `accepted`, `superseded by NNNN`.
A changed decision gets a new ADR; the old one is marked superseded, never rewritten.

| ADR | Decision | Status |
|-----|----------|--------|
| [0001](0001-kotlin-ktor-stack.md) | Kotlin + Ktor, Gradle modules `:model` / `:journal` / `:server` | accepted |
| [0002](0002-sqlite-storage.md) | SQLite with SQLDelight and versioned migrations | accepted |
| [0003](0003-polling-and-change-detection.md) | Poll every 15 min, detect changes by content hash; first sync = all `created` | accepted |
| [0004](0004-api-key-auth.md) | One API key in `X-School-Key`, wrong-key limiter, Cloudflare client IP | accepted |
| [0005](0005-docker-distribution.md) | One Docker image on GHCR, compose + example.env as release assets, port 5100 | accepted |
| [0006](0006-separate-server-provider-neutral.md) | Separate server; public repo never names the provider (names only in git-ignored `private/`); all e-journal addresses from `.env` | accepted |
| [0007](0007-local-read-state.md) | Message read state lives only on this server | accepted |
| [0008](0008-credentials.md) | Login and password in `.env`, optional `SCHOOL_PASSWORD_FILE`, never logged | accepted |
| [0009](0009-ci-github-actions.md) | CI and releases on GitHub Actions (lint, tests, boundaries, neutral names, gitleaks, multi-arch image) | accepted |
| [0010](0010-openapi-contract-first.md) | Contract-first API: `docs/api/openapi.yaml`, served at `/api/v1/openapi.yaml` + Swagger UI `/docs`, responses validated in tests | accepted |
| [0011](0011-journal-data-source.md) | Parent portal account + JSON API v3 + new messages service; no HTML scraping | accepted |
| [0012](0012-school-id-gateway-login.md) | School-issued numeric login via OAuth + Gateway API 2.0 | accepted |
| [0013](0013-multi-child-api.md) | Multi-child sync, `GET /api/v1/children`, and `?child=` filter | accepted |
| [0014](0014-sync-never-deletes-on-partial-data.md) | Partial data (denied grades, cut inbox) never produces deletions; rejected credentials always stop the sync | accepted (agent decision) |

Agent-made decisions are welcome (AGENTS.md "Autonomy"): mark them
`accepted (agent decision, YYYY-MM-DD)`; the owner may supersede them later.

## Template

```markdown
# ADR NNNN — Title

**Status:** accepted
**Date:** YYYY-MM-DD

## Context
## Decision
## Consequences
```
