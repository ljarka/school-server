# T-004 — API key, wrong-key limiter, Cloudflare client IP

**Status:** done
**Depends on:** T-001
**ADRs:** 0004

## Summary

Protect everything under `/api/v1` with `X-School-Key`, copy picfort-server's wrong-key limiter,
and read the real client address behind a Cloudflare Tunnel.

## Scope

- `auth/ClientAddress.kt`: `fun clientAddress(call, trustProxy): String` — `CF-Connecting-IP`
  when `TrustProxy.CLOUDFLARE` and header present, else `call.request.origin.remoteAddress`.
- `auth/WrongKeyLimiter.kt`: per address, sliding window 15 min, max 10 wrong keys; injected
  `Clock`; prune entries lazily; cap map size (e.g. 10 000 addresses, then drop oldest).
- `auth/ApiKeyPlugin.kt`: route-scoped Ktor plugin installed on `route("/api/v1")`:
  1. address blocked → `429 TOO_MANY_WRONG_KEYS` (+ `Retry-After` seconds).
  2. key configured and header missing/wrong (`MessageDigest.isEqual`) → record wrong attempt,
     `401 UNAUTHORIZED`, `WWW-Authenticate: School-Key`.
  3. no key configured → pass.
- Global response header `Cache-Control: no-store` (DefaultHeaders or an interceptor).
- `api/Errors.kt`: `ApiError(code, message)` + StatusPages mapping; reused by all routes.
- Log wrong-key events at `WARN` with the address, **never** the supplied key.

## Tests (`ApiKeyPluginTest`, `WrongKeyLimiterTest`)

- No header → 401 with `WWW-Authenticate: School-Key`; wrong → 401; right → 200 on a dummy `/api/v1/ping` test route.
- 10 wrong from A → 11th request from A (even with the right key) → 429; B still works.
- After 15 min (fake clock) A works again.
- With `TRUST_PROXY=cloudflare`, two different `CF-Connecting-IP` values from the same socket are limited separately; without it they share a bucket.
- `/health` never needs a key.
- Responses carry `Cache-Control: no-store`.
- Captured logs never contain the wrong or right key.

## Acceptance

All tests green; behaviour matches SPEC §6.1 and ADR 0004.
