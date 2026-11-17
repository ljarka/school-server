# School Server API — integration guide

The full, machine-readable contract is [`openapi.yaml`](openapi.yaml) (OpenAPI 3.1). The running
server also serves it at `GET /api/v1/openapi.yaml` and an interactive browser at `GET /docs`.
Generate a client from it (any language) or use the Kotlin DTOs from the `:model` module.

## 1. Connect

| | |
|---|---|
| Base URL | `http://<host>:5100` locally, or your Cloudflare hostname |
| Auth | header `X-School-Key: <SCHOOL_API_KEY>` on every `/api/v1/*` call |
| Format | JSON, UTF-8, camelCase fields |
| Times | ISO 8601 UTC (`2026-11-16T14:05:00Z`); `issuedOn` is a plain date |
| Ids | strings: grades `g_…`, messages `m_…` |
| Caching | none (`Cache-Control: no-store`) |

```sh
curl -H "X-School-Key: $KEY" https://school.example.com/api/v1/status
```

## 2. The usual integration (e.g. a "School" screen)

1. **List children** (when there are several): `GET /api/v1/children` → pass `?child=<id_or_login>`
   on `/grades`, `/grades/subjects`, `/messages`, `/messages/{id}`, `/messages/read-all`, and
   `/changes` (omit `child` to read all children).
2. **On first setup**: `GET /api/v1/status` → save `lastEventId` as your cursor if you only
   want news from now on; or keep cursor `0` to receive everything once.
3. **Show current data**: `GET /api/v1/grades/subjects` and `GET /api/v1/messages?unread=true`.
4. **Poll for news** every few minutes (the server itself refreshes every 15 min):
   `GET /api/v1/changes?after=<cursor>&limit=100`; process `items`; save `nextCursor`;
   repeat while `hasMore` is `true`.
5. **Open a message**: `GET /api/v1/messages/{id}`, then `PUT /api/v1/messages/{id}/read`.
6. **Health badge**: if `/api/v1/status` has `auth: "auth_failed"` or `lastSuccessfulSyncAt`
   older than an hour, show "school data not up to date".

```text
cursor = load() ?: 0
loop:
  page = GET /api/v1/changes?after=cursor
  for e in page.items:
      when e.entityType, e.changeType -> update local cache / notify
  cursor = page.nextCursor; save(cursor)
  if !page.hasMore: sleep 5 min
```

## 3. Change events

| `changeType` | `entity` | `diff` |
|---|---|---|
| `created` | the new grade / message (message without body) | `null` |
| `updated` | current state | `{"field": [old, new]}` |
| `deleted` | last known state, `deletedAt` set | `null` |

Marking a message read never creates an event.

## 4. Errors

Always `{"error": {"code": "...", "message": "..."}}`. Switch on `code`, show `message` only in logs.

| HTTP | `code` | What to do |
|---|---|---|
| 400 | `BAD_REQUEST` | Fix the parameter |
| 401 | `UNAUTHORIZED` | Ask the user for the key again (header `WWW-Authenticate: School-Key` distinguishes it from other 401s) |
| 404 | `NOT_FOUND` | Remove the item locally |
| 404 | `CHILD_NOT_FOUND` | The `child` filter is unknown; reload `/api/v1/children` |
| 409 | `SYNC_IN_PROGRESS` / `AUTH_FAILED` | Wait / tell the user the server's e-journal login is wrong |
| 429 | `TOO_MANY_WRONG_KEYS` / `SYNC_TOO_SOON` | Wait `Retry-After` seconds |
| 503 | `NO_DATA_YET` / `STORAGE_UNAVAILABLE` | Retry later |

## 5. Compatibility promise

- `/api/v1` only ever **adds** fields, endpoints and enum values. Ignore what you don't know
  (`Json { ignoreUnknownKeys = true }` in kotlinx.serialization).
- Removing or renaming anything means `/api/v2`, and `/api/v1` stays for at least one release.
- Every change to the API is in `CHANGELOG.md` under "API".

## 6. Kotlin example (Ktor client + `:model` DTOs)

```kotlin
val client = HttpClient(OkHttp) {
    install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    defaultRequest {
        url("https://school.example.com/")
        header("X-School-Key", key)
    }
}
val page: ChangePage = client.get("api/v1/changes") { parameter("after", cursor) }.body()
```

## 7. Planned (not in v1)

Sending messages, announcements, attendance, homework, calendar, timetable, push notifications
(webhook/SSE). They will be added under `/api/v1` as new endpoints without breaking existing ones.
