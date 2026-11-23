# TIMEORA Server v5.7.0

Node HTTP API + web admin. Optional **MongoDB** for catalog, anonymous feature events, live rooms, and **opt-in device backup** (matches app `Sync.kt`).

## Quick start

```bash
cd timeora-server
cp .env.example .env
# set MONGODB_URI, ADMIN_USER, ADMIN_PASS
npm install
node server.js
```

- Admin UI: http://localhost:3000/admin
- Catalog (app): http://localhost:3000/api/catalog
- Health: http://localhost:3000/api/health

Without `MONGODB_URI` the server runs **in-memory** (dev only; data lost on restart).

## App alignment (v5.7)

| App needs | Endpoint |
|-----------|----------|
| Catalog | `GET /api/catalog` |
| Health / connect | `GET /api/health` |
| Feature open | `POST /api/event` `{ "feature": "id" }` |
| Rooms | `POST /api/rooms`, `POST /api/rooms/:code/ping`, leave, `GET` |
| Sync register | `POST /api/sync/register` |
| Sync rows / prefs / finish | `POST /api/sync/rows`, `/prefs`, `/finish` |
| Wipe backup | `DELETE /api/sync/data` |

Seed catalog: `seed-catalog.json` (Planner universal, Goals & deadlines, 12 features).

## What admin can manage

- Feature catalog (title, tagline, why, route, order, enabled, color, live)
- Home banners
- Announcement + daily tips
- Cloud ambient sounds (`snd-*` + https URL)
- Anonymous feature-open stats
- Opt-in sync **device list** + wipe (row **counts** only in UI)

## What stays app-only

- Quiet Home, Dump undo, Focus 1-tap, coach UI
- 25 Time-Saving tool implementations
- Local usage without user enabling backup

## MongoDB collections

| Collection | Purpose |
|------------|---------|
| `catalog` | `_id: main` — features, banners, tips, sounds |
| `events` | Anonymous `{ feature, at }` |
| `devices` | `{ deviceId, deviceKey, prefs, rowCounts, lastSync }` |
| `sync_rows` | `{ deviceId, table, k, d, syncId }` — full opt-in snapshots |
| `rooms` | Live focus rooms |

### User data note

Screen-time / planner / focus data is **not** uploaded unless the user turns on **Back up my data** in the app. Admin does not show full row payloads — only device id, last sync, and per-table counts. Wipe deletes that device’s backup.

## Deploy (Vercel / Railway / VPS)

Set env vars `MONGODB_URI`, `ADMIN_USER`, `ADMIN_PASS`.
Point app `Remote.DEFAULT_SERVER` at this host (HTTPS in release builds).

## Reset catalog to app seed

Admin UI → Features → **Reset to app seed (v5.7)** or:

`POST /api/admin/catalog/reset` (authenticated).
