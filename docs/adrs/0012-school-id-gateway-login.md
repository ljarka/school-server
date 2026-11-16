# ADR 0012 — School-issued numeric login via OAuth + Gateway API 2.0

**Status:** accepted (2026-11-17)
**Date:** 2026-11-17

## Context

ADR 0011 chose the family portal account (e-mail + password) as the primary login flow.
Some parent/student accounts only have a school-issued numeric login (without an e-mail
portal account), which cannot log in via the portal e-mail login.

## Decision

- Support both login modes in `:journal` automatically based on `SCHOOL_LOGIN`:
  - Contains `@` → family portal login + JSON API v3 bearer token (ADR 0011).
  - Does not contain `@` → OAuth + Gateway API 2.0 cookie session using `SCHOOL_JOURNAL_GATEWAY_URL`,
    plus activation of the messages service via `MESSAGES_ACTIVATION_PATH`.
- `SCHOOL_JOURNAL_GATEWAY_URL` is optional when `SCHOOL_LOGIN` is an e-mail address and required
  when `SCHOOL_LOGIN` is a school-issued login.
- All gateway path constants stay in `ProtocolPaths.kt` (ADR 0006).

## Consequences

- School-issued numeric logins work without requiring a separate portal e-mail account.
- Grades and messages still map to the exact same `:model` domain types and SQLite schema.
