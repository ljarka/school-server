# ADR 0002 — SQLite with SQLDelight and versioned migrations

**Status:** accepted
**Date:** 2026-11-16

## Context

We store a snapshot of grades and messages, the change feed, local read state and sync runs.
One family, one server, small data. The API must keep serving the last data when the
e-journal is down. picfort-server learned (its ADR 0016) that "start empty on schema change"
is not acceptable once data has value; here read state and change history have value.

## Decision

- One SQLite file: `SCHOOL_DB_PATH` (default `/data/school.db` in the image, `./school.db` outside).
- **SQLDelight** (JVM `sqlite-driver`, xerial sqlite-jdbc) in `:server`: schema and queries in
  `.sq` files, typed Kotlin generated at build time; migrations in `.sqm` files (`1.sqm`, `2.sqm`…).
- Build verifies migrations: `verifyMigrations = true` with a committed schema `.db` per version
  (`src/main/sqldelight/databases/`).
- At start: read `PRAGMA user_version`; if lower than the current schema, copy the file to
  `school.v<old>.bak`, then migrate in one transaction. `PRAGMA foreign_keys=ON`,
  `journal_mode=WAL`.
- Missing or failing migration → error logged, API answers `503 STORAGE_UNAVAILABLE`; the file is
  never wiped.
- Tables (v1): `grade`, `message`, `change_event`, `sync_run`, `meta` (key/value, e.g. `auth_state`).
  Schema in [T-003](../tasks/T-003-sqlite-store.md).
- Writes go through one `Store` class; a sync writes rows and events in **one transaction**.
- History is kept forever in v1 (assumption in STATUS; revisit past ~100 MB).

## Consequences

- No ORM; SQL stays visible and typed.
- `MigrationTest` opens a v(N-1) file with sample rows and checks them after upgrade, for every N.
- Backup = copy one file while the container is stopped (README).
