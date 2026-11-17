# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added
- Initial School Server implementation: portal authentication, grades and messages sync,
  SQLite storage with migrations, REST API with OpenAPI/Swagger UI, and Docker packaging.
- School notice-board announcements in `/api/v1/messages`, marked with `type: announcement`,
  with `validFrom` / `validTo` and a `type` filter on `/messages` and `/messages/read-all`.
- Descriptive, text and point grades from the e-journal (`dg_`, `dtg_`, `tg_`, `pg_` ids)
  alongside standard constituent grades.
