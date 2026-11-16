# ADR 0005 — One Docker image on GHCR, compose + example.env as release assets

**Status:** accepted
**Date:** 2026-11-16

## Context

The owner wants installation as easy as picfort-server: download two files, fill `.env`,
`docker compose up -d`. Both servers run on the same machine (architecture not fixed:
x86 PC/NAS or Raspberry Pi).

## Decision

- Image `ghcr.io/ljarka/school-server:<version>` and `:latest`, built by GitHub Actions on a
  `v*` tag for **`linux/amd64` and `linux/arm64`** (buildx). `docker/docker-compose.yml` and
  `docker/example.env` are attached to the release.
- Multi-stage Dockerfile:
  1. `gradle:8-jdk21` → `./gradlew :server:installDist` (Gradle cache mounted).
  2. `eclipse-temurin:21-jre` (Ubuntu-based; has `setpriv`) runtime with only `server/build/install/server`.
- Runtime as in picfort-server: env defaults pointing to `/data`, `VOLUME /data`, non-root user
  `school` uid/gid 1000, `docker-entrypoint.sh` fixes ownership of `/data` then drops root with
  `setpriv`, `HEALTHCHECK` with `curl -fsS http://127.0.0.1:$PORT/health`, `TZ=Europe/Warsaw`.
- JVM flags: `-XX:MaxRAMPercentage=50 -XX:+UseSerialGC` (small heap, low idle RAM), overridable
  with `JAVA_OPTS`.
- Default port **5100** (picfort-server uses 5000). Compose project `name: school`,
  `container_name: school_server`, `restart: always`, `env_file: .env`.
- Compose-only variables: `SCHOOL_VERSION`, `SCHOOL_PORT`, `DATA_LOCATION`.
- Missing required config → clear message on stderr, exit code 2. Port in use → clear message, exit 1.

## Consequences

- The image must stay provider-neutral (labels, description) — see ADR 0006.
- Public repository and public GHCR package: no `docker login` needed to pull.
- Image ~250 MB; idle RAM target < 200 MB.
