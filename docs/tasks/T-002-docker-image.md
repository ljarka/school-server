# T-002 — Docker image, compose, example.env

**Status:** done
**Depends on:** T-001
**ADRs:** 0005, 0006, 0008

## Summary

Package the server the way picfort-server is packaged: one image, `/data` volume, non-root,
healthcheck, and the two files users download (`docker/docker-compose.yml`, `docker/example.env`).

## Scope

- `Dockerfile` (multi-stage):
  - build: `gradle:8-jdk21`, `./gradlew :server:installDist --no-daemon`.
  - runtime: `eclipse-temurin:21-jre`; copy `server/build/install/server` to `/app`.
  - `ENV PORT=5100 SCHOOL_DB_PATH=/data/school.db TZ=Europe/Warsaw JAVA_OPTS="-XX:MaxRAMPercentage=50 -XX:+UseSerialGC"`.
  - user/group `school` 1000:1000, `mkdir /data && chown`, `VOLUME /data`, `EXPOSE 5100`.
  - `HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD curl -fsS http://127.0.0.1:${PORT}/health || exit 1`
    (install `curl` in runtime stage).
  - `ENTRYPOINT ["docker-entrypoint.sh"]`, `CMD ["/app/bin/server"]`.
  - OCI labels: title "School Server", description "Grades and messages from a school e-journal, with a change feed" (neutral).
- `docker-entrypoint.sh`: same logic as picfort-server's (chown only files not owned by `school`,
  then `exec setpriv --reuid=school --regid=school --init-groups --inh-caps=-all "$@"`; if not root, `exec "$@"`).
- `docker/docker-compose.yml` — as in SPEC §9.1 (`name: school`, `container_name: school_server`,
  image `ghcr.io/ljarka/school-server:${SCHOOL_VERSION:-latest}`, `${SCHOOL_PORT:-5100}:5100`,
  `${DATA_LOCATION:-./data}:/data`, `env_file: .env`, `restart: always`). Header comment with the
  3-step install (copy style of picfort's compose).
- `docker/example.env` — every variable from T-001, `SCHOOL_JOURNAL_URL=` empty, comments neutral,
  reminder `chmod 600 .env`.
- `.dockerignore`: `.git`, `build`, `.gradle`, `docs`, `.env`, `data`, vault folders.
- Port-in-use: catch the bind exception in `Main.kt`, print "Port 5100 is already in use. Set
  SCHOOL_PORT (Docker) or PORT to a free port." and exit 1.

## Tests

- CI smoke (wired in T-012); locally: `docker build .` then
  `docker run --rm -e SCHOOL_JOURNAL_URL=http://localhost:9 -e SCHOOL_LOGIN=x -e SCHOOL_PASSWORD=y -p 5100:5100 <img>`
  → `/health` 200, container user is uid 1000, `/data/school.db` created.

## Acceptance

- `docker compose -f docker/docker-compose.yml up --build` works with a filled `.env`.
- Image builds for `linux/arm64` too (`docker buildx build --platform linux/arm64 .`).
- `check-neutral-names.sh` (T-012) passes on `docker/` and `Dockerfile`.
