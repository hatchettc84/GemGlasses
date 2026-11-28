# GemGlasses backend

A single Cloudflare Worker that keeps the Google API key off the phone.

| Endpoint | Purpose |
|----------|---------|
| `POST /token` | Mints a short-lived **ephemeral token** for the Gemini Live API. The app opens the Live WebSocket with this token, never the API key. |
| `POST /places` | Proxies a `generateContent` call with **Maps grounding**, returning a short summary + cited places. |

Both endpoints accept a paired phone's device key (`Authorization: Bearer <key>`)
or, for app builds that are not paired yet, the `X-App-Secret` header matching
`TOKEN_APP_SECRET`. A removed (revoked) phone gets 401 `device_revoked`.

## Setup

```bash
npm install

# Secrets (production)
npx wrangler secret put GEMINI_API_KEY
npx wrangler secret put TOKEN_APP_SECRET

# Local dev — create .dev.vars (gitignored):
#   GEMINI_API_KEY="..."
#   TOKEN_APP_SECRET="dev-secret"
npm run dev        # http://localhost:8787

npm run deploy
```

Point the Android app at the Worker via `GEMGLASSES_BACKEND_URL` in `local.properties`
(use `http://10.0.2.2:8787` from the emulator for local dev).

## Request shapes

```http
POST /token
Authorization: Bearer <device key>     (paired phone)
  or
X-App-Secret: <secret>                 (legacy, not yet paired)
→ { "token": "...", "expiresAt": "2026-..." }

POST /places
Authorization: Bearer <device key>     (or X-App-Secret, as above)
{ "query": "café aberto agora", "latitude": 53.34, "longitude": -6.26 }
→ { "summary": "...", "places": [ { "title": "...", "uri": "https://maps..." } ] }
```

## Routes

The Worker serves three route groups, each with its own sign-in:

| Group | Sign-in | Routes |
|-------|---------|--------|
| Legacy | Device key, or `X-App-Secret` | `POST /token`, `POST /places` |
| Office (`/api/admin/*`) | Cloudflare Access JWT (`Cf-Access-Jwt-Assertion`); locally the `X-Dev-User-Email` header, accepted only when `ENVIRONMENT="dev"` | `GET /me`, `GET /users`, `POST /users`, `PATCH /users/:id`, `POST /users/:id/pairing-code`, `GET /devices`, `POST /devices/:id/revoke` |
| Phone (`/api/device/*`) | Device key (`Authorization: Bearer <key>`), except `/pair` | `POST /pair`, `GET /me` |

Errors are JSON: `{ "error": "<code>", "message": "<plain text>" }`. Every
office and phone write is recorded in `audit_log`.

## Local development

1. Add the two dev-only lines to the gitignored `.dev.vars` (keep your existing
   Gemini key and app secret there):
   ```bash
   grep -q '^ENVIRONMENT=' .dev.vars || printf '\nENVIRONMENT="dev"\n' >> .dev.vars
   grep -q '^BOOTSTRAP_ADMIN_EMAIL=' .dev.vars || printf 'BOOTSTRAP_ADMIN_EMAIL="you@example.com"\n' >> .dev.vars
   ```
   The bootstrap admin email becomes an admin the first time it signs in, but
   only while the database has no admin yet.
2. Apply the schema and start the API:
   ```bash
   npm run db:migrate:local && npx wrangler dev --port 8787 --ip 127.0.0.1
   ```
3. For a phone on USB, forward the port: `adb reverse tcp:8787 tcp:8787`.
4. Tests and type checks: `npm test` and `npm run typecheck`.

**Paths with spaces:** the Workers test runner (workerd) fails when the repo
path contains a space (for example `/Volumes/My Passport/...`). Run the tests
from a copy of `backend/` at a path without spaces, without copying `.dev.vars`.

## Before the first deploy

Nothing has been deployed yet. Deploy only with the owner's approval.

1. Create the database and put its id in `wrangler.toml` (replace the
   placeholder `database_id`):
   `npx wrangler d1 create bobai`
2. Apply the schema remotely: `npx wrangler d1 migrations apply bobai --remote`
3. Put a Cloudflare Access application in front of the console and
   `/api/admin/*`, then set `ACCESS_TEAM_DOMAIN`, `ACCESS_AUD` and
   `BOOTSTRAP_ADMIN_EMAIL` for the Worker. Do not put `/api/device/*` behind
   Access; phones sign in with device keys.
4. Set secrets with `npx wrangler secret put GEMINI_API_KEY` and
   `npx wrangler secret put TOKEN_APP_SECRET`.
5. Never set `ENVIRONMENT` in production. It turns on the dev sign-in header
   (which is also ignored when `ACCESS_AUD` is set or the host is not loopback).

## Known limits before production

Removing a phone stops its device-key access only. `TOKEN_APP_SECRET` ships in
every app build, so any build still holding the shared secret can keep calling
`/token` and `/places` until that secret is retired. Once every phone is
paired, unset `TOKEN_APP_SECRET` (`npx wrangler secret delete TOKEN_APP_SECRET`)
and remove the legacy `X-App-Secret` path from `legacyAuth` and the app.

## Plane (optional job source)

When configured, `GET /api/device/jobs` serves the technician's assigned work
items from a self-hosted Plane instance (read-only) instead of D1. Technicians
are matched to Plane members by email. Jobs and sub-items carry ids starting
with `plane-`; device `PATCH` on those ids returns 409 `managed_in_plane`.
If Plane cannot be reached the endpoint returns 502 `plane_unavailable`.

Plane mode is on only when all three of these are set; otherwise D1 is used:

| Variable | Notes |
| --- | --- |
| `PLANE_BASE_URL` | e.g. `http://127.0.0.1:8095` |
| `PLANE_API_KEY` | secret; sent as `X-API-Key`, never logged or returned |
| `PLANE_WORKSPACE_SLUG` | workspace slug from the Plane URL |
| `PLANE_PROJECT_IDS` | optional, comma-separated; empty = all projects |

Create the key in Plane under Profile settings, Personal access tokens. Then the
owner adds these lines to `backend/.dev.vars` themselves (do not commit them):

```
PLANE_BASE_URL=http://127.0.0.1:8095
PLANE_API_KEY=<token from Plane>
PLANE_WORKSPACE_SLUG=<slug>
```

In production use `npx wrangler secret put PLANE_API_KEY` and set the others as vars.
`GET /api/admin/integrations/plane` reports `{ enabled, workspaceSlug, webUrl }`
for the console and does not call Plane.
