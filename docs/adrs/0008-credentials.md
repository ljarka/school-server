# ADR 0008 — Login and password in `.env`, optional `SCHOOL_PASSWORD_FILE`, never logged

**Status:** accepted
**Date:** 2026-11-16

## Context

The server needs the parent's e-journal login and password in plain form to log in.
Self-hosted on one machine: encrypting them would need a key stored next to them.

## Decision

- `SCHOOL_LOGIN` and `SCHOOL_PASSWORD` in `.env` (owner decision 2026-11-16).
- Optional `SCHOOL_PASSWORD_FILE` (Docker secret); wins over `SCHOOL_PASSWORD`, logs that it did
  (without the value). Trailing newline stripped.
- README tells users to `chmod 600 .env`.
- `.env` is in `.gitignore`; only `docker/example.env` with empty values is committed.
- Secrets are held in `class Secret(private val value: String)` whose `toString()` returns `"***"`
  and which exposes the value only through `reveal()`. `Config` is a data class with `Secret`
  fields, so printing config or an exception can't leak them.
- OkHttp has **no** `HttpLoggingInterceptor` at BODY/HEADERS level; Ktor `CallLogging` logs
  method, path, status, duration only (never headers, so not `X-School-Key`).
- Known limit: `docker inspect` shows env values; `SCHOOL_PASSWORD_FILE` avoids it.

## Consequences

- `SecretLeakTest` runs a login failure and a full sync against MockWebServer with
  `LOG_LEVEL=DEBUG`, captures Logback output and asserts the password and API key never appear.
