# ADR 0010 — Contract-first API: OpenAPI is the source of truth

**Status:** accepted
**Date:** 2026-11-16

## Context

The owner wants clear API documentation so Picfort (and anything else) can integrate easily.
Hand-written docs drift from code; generated-from-code docs tend to be thin.

## Decision

- [`docs/api/openapi.yaml`](../api/openapi.yaml) (OpenAPI 3.1) is the **contract**. An API change
  starts by editing it, then the code. Integration guide: [`docs/api/README.md`](../api/README.md).
- The file is copied into `:server` resources at build time and served:
  - `GET /api/v1/openapi.yaml` (no key; contains no data),
  - `GET /docs` — Swagger UI (Ktor `swagger` plugin) pointing at that file; "Authorize" accepts the key.
- `ApiContractTest` validates **every** response produced in API tests against the spec
  (Atlassian `swagger-request-validator-core`, or equivalent OpenAPI 3.1 validator): unknown
  fields, missing required fields, wrong types or undocumented status codes fail the build.
- CI lints the spec (`redocly lint docs/api/openapi.yaml` via `npx`) — added to `ci.yml`.
- DTOs in `:model` mirror the schemas 1:1 (same names). Picfort reuses `:model` or generates a client.
- Versioning: `/api/v1` is additive only; breaking change → `/api/v2`. Clients must ignore unknown
  fields/enum values (stated in the spec).
- The spec and Swagger UI are provider-neutral (checked by `check-neutral-names.sh`).

## Consequences

- One extra file to keep in step — enforced by the contract test, not by memory.
- Future write features (sending messages) are added to the spec first, behind a new ADR that
  lifts AGENTS.md rule 1 for that endpoint only.
