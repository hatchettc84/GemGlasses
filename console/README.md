# BobAI Console

Office web app for managing BobAI: people, phones, and (in later steps)
manuals, reference packs, jobs, equipment and session review.

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
