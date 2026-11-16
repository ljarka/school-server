# T-003 — SQLite store with SQLDelight and migrations

**Status:** ready
**Depends on:** T-001
**ADRs:** 0002, 0003, 0007

## Summary

Add the database: schema v1, typed queries, migration machinery with backup-before-migrate, and
a `Store` facade the sync and API use.

## Scope

- SQLDelight plugin in `:server`, database `SchoolDatabase`, package `school.server.store.db`,
  `verifyMigrations = true`, schema output dir `src/main/sqldelight/databases`.
- `Schema.sq` (v1):

```sql
CREATE TABLE grade (
  source_id     TEXT PRIMARY KEY,
  subject       TEXT NOT NULL,
  value         TEXT NOT NULL,         -- "5", "4+", "np", "+"
  numeric_value REAL,
  weight        INTEGER,
  category      TEXT NOT NULL,
  semester      INTEGER NOT NULL,      -- 1 | 2
  kind          TEXT NOT NULL,         -- regular | proposed_semester | semester | proposed_final | final
  counts_to_average INTEGER NOT NULL,  -- 0/1
  teacher       TEXT NOT NULL,
  comment       TEXT,
  issued_on     TEXT NOT NULL,         -- YYYY-MM-DD
  content_hash  TEXT NOT NULL,
  first_seen_at TEXT NOT NULL,
  updated_at    TEXT NOT NULL,
  deleted_at    TEXT
);
CREATE TABLE message (
  source_id     TEXT PRIMARY KEY,
  sender        TEXT NOT NULL,
  subject       TEXT NOT NULL,
  body_text     TEXT,                  -- NULL until fetched
  body_html     TEXT,                  -- sanitised
  sent_at       TEXT NOT NULL,         -- UTC
  attachments   TEXT NOT NULL,         -- JSON [{"name": "..."}]
  content_hash  TEXT NOT NULL,
  read_at       TEXT,                  -- ADR 0007, local only
  first_seen_at TEXT NOT NULL,
  updated_at    TEXT NOT NULL,
  deleted_at    TEXT
);
CREATE TABLE change_event (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  entity_type   TEXT NOT NULL,         -- grade | message
  entity_id     TEXT NOT NULL,
  change_type   TEXT NOT NULL,         -- created | updated | deleted
  diff          TEXT,                  -- JSON {"field": [old, new]}
  detected_at   TEXT NOT NULL,
  sync_run_id   INTEGER NOT NULL REFERENCES sync_run(id)
);
CREATE INDEX change_event_type ON change_event(entity_type, id);
CREATE TABLE sync_run (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  started_at TEXT NOT NULL, finished_at TEXT,
  status TEXT NOT NULL,                -- running | ok | error | auth_failed
  error_code TEXT, items_fetched INTEGER NOT NULL DEFAULT 0, changes_detected INTEGER NOT NULL DEFAULT 0
);
CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
```

  Plus named queries needed by T-008, T-010, T-011 (list grades with filters, list messages
  with cursor, events after id, last sync run, mark read…).
- `store/DatabaseFactory.kt`: open file, `PRAGMA foreign_keys=ON`, `journal_mode=WAL`; if
  `user_version` < current: copy to `school.v<old>.bak`, `Schema.migrate(...)` in a transaction,
  set `user_version`. Failure → `StorageUnavailable` state (API returns 503, T-010).
- `store/Store.kt`: facade with domain types from `:model`; `fun <T> transaction(block: Store.() -> T)`.

## Tests

- `StoreTest`: insert/read grade and message round-trip; filters; soft delete hidden by default.
- `MigrationTest`: framework test with a fake v0→v1 to prove backup file is created and rows survive;
  a test that fails if `Schema.version` > number of `.sqm` files + 1.
- Fresh DB file is created in a missing directory.

## Acceptance

- `./gradlew :server:verifySqlDelightMigration test` green.
- Corrupt/failed migration leaves the original file and the `.bak` untouched.
