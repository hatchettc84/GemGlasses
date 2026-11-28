# BobAI Admin

Small admin page for what the BobAI office app has no place for: people and
roles, phone pairing and removal, and manuals (upload, review, approve, revoke).
Jobs and technicians' notes/drafts live in the BobAI office app (self-hosted
Plane, rebranded; see `../plane-branding/`), whose sidebar links here as
"Phones & manuals". `VITE_BOBAI_APP_URL` sets the "Open BobAI" link
(default `http://127.0.0.1:8095`).

## Local development

1. Start the API: `cd ../backend && npm run db:migrate:local && npx wrangler dev --port 8787 --ip 127.0.0.1`
   (`backend/.dev.vars` must contain `ENVIRONMENT="dev"` and `BOOTSTRAP_ADMIN_EMAIL`).
2. Start the console: `DEV_USER_EMAIL="you@example.com" npm run dev` and open http://localhost:5173.
   The dev server adds `X-Dev-User-Email` to API calls; the API accepts it only when `ENVIRONMENT` is `dev`.

## Tests

`npm test`

## Production

Served by Cloudflare Pages behind a Cloudflare Access application. Access
signs people in; the API checks each person's role. Deploy only when the
owner approves.
