# School Server

A small self-hosted server that keeps an eye on your child's school e-journal (parent account) and gives you **grades and messages** through a simple REST API, together with a feed of **what changed** since you last looked.

- Checks the e-journal every 15 minutes (configurable)
- Remembers everything in a local SQLite database, so the API keeps working when the e-journal is down
- Tells you what's new: new or changed grades, new messages
- Tracks which messages you have read, on this server
- Read-only towards the e-journal: it never sends, deletes or changes anything there
- One Docker container, one `.env` file, one API key

> This is an unofficial, personal project. It is not affiliated with or endorsed by any e-journal provider. Use it only with your own account, and check that your provider's terms allow it.

## Install (Docker)

You need Docker with the Compose plugin.

```sh
mkdir school-server && cd school-server
wget -O docker-compose.yml https://github.com/ljarka/school-server/releases/latest/download/docker-compose.yml
wget -O .env https://github.com/ljarka/school-server/releases/latest/download/example.env
```

Open `.env` and fill in at least:

```ini
SCHOOL_JOURNAL_URL=https://family-portal.example
SCHOOL_JOURNAL_API_URL=https://api.example
SCHOOL_JOURNAL_MESSAGES_URL=https://messages.example
SCHOOL_LOGIN=you@example.com
SCHOOL_PASSWORD=your-parent-password
SCHOOL_API_KEY=a-long-random-string
```

Generate a key with e.g. `openssl rand -hex 32`. Your login and password go into `.env` as plain text, so make the file readable only by you:

```sh
chmod 600 .env
```

Never commit `.env` to git. Then start it:

```sh
docker compose up -d
docker compose logs -f   # watch the first sync
```

Check that it works:

```sh
curl http://localhost:5100/health
curl -H "X-School-Key: $SCHOOL_API_KEY" http://localhost:5100/api/v1/status
```

### Update

```sh
docker compose pull && docker compose up -d
```

Your data lives in `./data` (set `DATA_LOCATION` to move it) and survives updates.

## Configuration

Everything is set in `.env`.

| Variable | Default | What it does |
|---|---|---|
| `SCHOOL_JOURNAL_URL` | – | Address of the e-journal's family portal, where you log in as a parent (required, `https://`) |
| `SCHOOL_JOURNAL_API_URL` | – | Address of the e-journal's data API (required) |
| `SCHOOL_JOURNAL_MESSAGES_URL` | – | Address of the e-journal's messages service (required) |
| `SCHOOL_LOGIN` | – | Parent portal login, usually your e-mail (required) |
| `SCHOOL_CHILD` | first child | Which child to follow (id or login) if your parent account has several |
| `SCHOOL_PASSWORD` | – | Parent password (required, unless `SCHOOL_PASSWORD_FILE` is set) |
| `SCHOOL_PASSWORD_FILE` | – | Optional: path to a file holding the password, e.g. a Docker secret. Used instead of `SCHOOL_PASSWORD`. |
| `SCHOOL_API_KEY` | – | Key the API asks for in the `X-School-Key` header. If you leave it empty, anyone who can reach the server can read your data, so only do that on a trusted home network. |
| `SYNC_INTERVAL_MINUTES` | `15` | How often to check the e-journal |
| `TRUST_PROXY` | – | Set to `cloudflare` when the server is behind a Cloudflare Tunnel (see below) |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warning`, `error` |
| `SCHOOL_VERSION` | `latest` | Image tag to run (compose only) |
| `SCHOOL_PORT` | `5100` | Port on your machine (compose only). Pick a port no other service uses, e.g. not `5000` if Picfort server runs on the same machine. |
| `DATA_LOCATION` | `./data` | Folder for the database (compose only) |

Your password stays on your machine. It is never logged, not even at `debug` level, and never returned by the API.

Anyone who can run `docker inspect` on your machine can see environment variables, including `SCHOOL_PASSWORD`. If that bothers you, put the password in a file and point to it instead:

```yaml
# docker-compose.yml
services:
  school-server:
    environment:
      SCHOOL_PASSWORD_FILE: /run/secrets/school_password
    secrets:
      - school_password

secrets:
  school_password:
    file: ./school_password.txt   # chmod 600
```

## Reaching it from outside (Cloudflare Tunnel)

Don't open the port on your router. Use a Cloudflare Tunnel instead:

1. In `.env` set `TRUST_PROXY=cloudflare` and make sure `SCHOOL_API_KEY` is set. The server won't start behind Cloudflare without a key.
2. In your tunnel, add a public hostname, e.g. `school.example.com`, pointing to `http://localhost:5100`.
3. Call it with the key:

   ```sh
   curl -H "X-School-Key: $SCHOOL_API_KEY" https://school.example.com/api/v1/grades
   ```

With `TRUST_PROXY=cloudflare` the server sees each caller's real address. After 10 wrong keys from one address in 15 minutes, that address gets `429 Too Many Requests` until the 15 minutes are up.

## API

Full reference: open **`http://localhost:5100/docs`** (interactive, click "Authorize" and paste
your key) or read [`docs/api/openapi.yaml`](docs/api/openapi.yaml). Building an app on top of it?
Start with the [integration guide](docs/api/README.md).

Every endpoint except `/health`, `/docs` and `/api/v1/openapi.yaml` needs the `X-School-Key: <key>` header. A missing or wrong key gets `401`. Responses are JSON and times are in UTC (ISO 8601).

| Method | Path | Description |
|---|---|---|
| GET | `/health` | Is the server up (no key needed) |
| GET | `/api/v1/status` | Last sync, its result, and `lastEventId` |
| GET | `/api/v1/grades` | Grades. Filters: `subject`, `semester`, `since` |
| GET | `/api/v1/grades/subjects` | Subjects with their grades and average |
| GET | `/api/v1/messages` | Messages. Filters: `unread=true`, `since`. Paging: `limit`, `cursor` |
| GET | `/api/v1/messages/{id}` | One message with its full text |
| PUT | `/api/v1/messages/{id}/read` | Mark as read (on this server only) |
| DELETE | `/api/v1/messages/{id}/read` | Mark as unread |
| POST | `/api/v1/messages/read-all` | Mark all as read |
| GET | `/api/v1/changes` | What changed. `after=<event id>` or `since=<time>`, `type=grade\|message`, `limit` |
| POST | `/api/v1/sync` | Check the e-journal now (at most once per 5 minutes) |

### Following changes

`/api/v1/changes` is a feed with a cursor. Keep the last `id` you saw and ask for everything after it:

```sh
curl -H "X-School-Key: $SCHOOL_API_KEY" "http://localhost:5100/api/v1/changes?after=120"
```

```json
{
  "items": [
    {
      "id": 121,
      "entityType": "grade",
      "entityId": "g_8812",
      "changeType": "created",
      "detectedAt": "2026-11-16T14:05:00Z",
      "entity": { "subject": "Mathematics", "value": "5", "category": "Test" }
    }
  ],
  "nextCursor": 121
}
```

`changeType` is `created`, `updated` (with a `diff` of the changed fields) or `deleted`.

The first sync reports **every** existing grade and message as `created`. If you only want changes from now on, read `lastEventId` from `/api/v1/status` and start from there.

### Errors

```json
{ "error": { "code": "UNAUTHORIZED", "message": "Missing or wrong X-School-Key" } }
```

| Status | When |
|---|---|
| `400` | Bad query parameter |
| `401` | Missing or wrong API key |
| `404` | No such grade or message |
| `429` | Too many wrong keys, or `/sync` called too often |
| `503` | No data yet and the e-journal can't be reached |

## Running without Docker

Requires JDK 21.

```sh
cp docker/example.env .env  # fill it in
set -a; . ./.env; set +a
./gradlew :server:run
```

Without Docker the database goes to `./school.db` (set `SCHOOL_DB_PATH` to change it).

## Development

Kotlin + Ktor, built with Gradle. Modules: `:model` (data types and API contract),
`:journal` (e-journal adapter), `:server` (HTTP API, storage, sync).

```sh
./scripts/check.sh          # what CI runs: ktlint, tests, migrations, module boundaries, naming
./gradlew test              # tests only
./gradlew ktlintFormat      # fix formatting
```

Tests never contact a real e-journal; they replay recorded, anonymised pages.

Design notes are in [`docs/`](docs/): the specification, architecture decisions (`docs/adrs`) and tasks (`docs/tasks`).

## License

[MIT](LICENSE)

## Troubleshooting

- **`/status` shows `auth_failed`.** The e-journal rejected the login. The server stops trying so your account doesn't get locked. Fix `SCHOOL_LOGIN` / `SCHOOL_PASSWORD`, then run `docker compose up -d` again.
- **`port is already allocated`.** Another service uses that port. Set a different `SCHOOL_PORT` in `.env` and point your Cloudflare Tunnel to it.
- **Backups.** Everything is in `./data/school.db`. Copy that file while the container is stopped.
- **Sync errors after an e-journal update.** The e-journal pages may have changed. Your saved data is still served. Check for a newer image with `docker compose pull`.
- **Permission errors on `./data`.** The container runs as user `1000` and fixes ownership of `/data` on start. If you run it with `--user`, make sure that user can write to the folder.
