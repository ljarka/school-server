# ADR 0004 — One API key in `X-School-Key`, wrong-key limiter, Cloudflare client IP

**Status:** accepted
**Date:** 2026-11-16

## Context

The server is reachable from the internet through a Cloudflare Tunnel on the same machine as
picfort-server. picfort-server protects itself with `PICFORT_API_KEY` in `X-Picfort-Key`, a
`401` with `WWW-Authenticate: Picfort-Key`, and a limit of 10 wrong keys per address per 15 min.
The owner wants one shared key, no users.

## Decision

- Key from `SCHOOL_API_KEY`. Header `X-School-Key`. Compared in constant time
  (`MessageDigest.isEqual` on UTF-8 bytes). Implemented as a Ktor route-scoped plugin on `/api/v1`.
- Missing/wrong key → `401 UNAUTHORIZED` with `WWW-Authenticate: School-Key`.
- Public (no key): `/health`, `/docs` (Swagger UI) and `/api/v1/openapi.yaml` (ADR 0010; they
  contain no data). Everything else under `/api/v1` needs the key.
- Empty key → no auth, warning logged at every start. **Refuse to start** if `TRUST_PROXY=cloudflare`
  and the key is empty.
- Limiter: 10 wrong keys from one client address within 15 min → `429 TOO_MANY_WRONG_KEYS` for
  that address (even with the right key) until the window passes. In-memory, per process.
- Client address: with `TRUST_PROXY=cloudflare` take `CF-Connecting-IP` (falls back to the socket
  address if missing); otherwise only the socket address. Without this, all tunnel traffic
  shares one address and one attacker would lock out everyone.
- Every response: `Cache-Control: no-store`.

## Consequences

- Rotating the key = edit `.env`, restart; all clients need the new key.
- `CF-Connecting-IP` can be spoofed if the port is reachable directly; README says not to open it.
