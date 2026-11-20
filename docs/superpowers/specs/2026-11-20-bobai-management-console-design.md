# BobAI Management Console: Design

Date: 2026-11-20
Status: approved in conversation, awaiting written-spec review

## 1. Purpose

The BobAI phone app (Galaxy Z Fold7 + Meta Ray-Ban glasses) runs on mock
jobs, equipment and manuals stored in one local JSON file. Nothing syncs.
The management console is the office side: the reference point where staff
upload manuals, approve them, publish reference packs, create and assign jobs,
manage equipment, people and phones, and review what technicians did in the
field. The phone syncs with it.

## 2. Scope

In scope, built in this order, each working end to end (PC → phone) before
the next starts:

1. Manuals → reference packs
2. Jobs and work orders
3. Equipment, people and device registry
4. Session review and audit log

Out of scope for this spec: text recognition for scanned PDFs, billing,
customer portal, analytics, voice recording (voice conversations stay
unrecorded, as the phone promises today).

## 3. Architecture

```
PC browser ──► Cloudflare Access (Google / email one-time code / SSO later)
                     │
              console (Cloudflare Pages, React + Vite + TypeScript)
                     │  /api/admin/*   (Access identity → role check)
                     ▼
        bobai-api Worker (extends today's backend/)
          ├─ D1 database   all records below
          ├─ R2 storage    manual PDFs, page text, pack files, field photos
          └─ /token        Gemini Live ephemeral token (unchanged)
                     ▲  /api/device/*  (per-phone device key)
Fold app ────────────┘  pulls jobs + packs, pushes sessions/captures/observations
```

- One Worker, grown from `backend/`. Existing `/token` and `/places` keep
  working for the current app build during the transition.
- Two route groups with separate authentication:
  - `/api/admin/*`: requires a valid Cloudflare Access JWT
    (`Cf-Access-Jwt-Assertion`, verified against the team's public keys and
    audience). The email in it must match an active row in `users`; the
    role on that row decides what is allowed.
  - `/api/device/*`: requires `Authorization: Bearer <device key>`. Keys are
    random 32-byte values; only a SHA-256 hash is stored. Revoked devices are
    refused.
- The shared `TOKEN_APP_SECRET` remains only for the legacy `/token` and
  `/places` routes until the phone moves to device keys; then `/token` also
  accepts device keys and the shared secret is retired.
- Local development: `wrangler dev` with local D1 and R2; Access is replaced
  by a dev-only identity header that is rejected unless `ENVIRONMENT=dev`.
- Deployment to Cloudflare happens only with the owner's explicit go-ahead.

## 4. Roles

| Role | Console | Phone |
|---|---|---|
| admin | everything, incl. people, devices, settings | – |
| reviewer | upload/approve/revoke manuals, publish packs, review and flag sessions | – |
| dispatcher | create, edit, assign and cancel jobs; manage equipment | – |
| technician | read-only view of own jobs and sessions | yes |

A person has exactly one role. Every write is checked on the server; the
console hiding a button is never the only guard.

## 5. Data model (D1)

All tables carry `tenant_id` (single tenant at launch), `created_at`,
`updated_at` (ISO-8601 UTC). IDs are ULIDs.

- `users`: email (unique per tenant), name, role, active
- `devices`: user_id, label, key_hash, paired_at, last_seen_at, revoked_at
- `pairing_codes`: user_id, code_hash, expires_at (10 minutes), attempts,
  used_at
- `equipment_models`: manufacturer, model
- `equipment`: model_id, serial, site_name, site_address
- `documents`: title, revision, status (`draft` | `approved` | `revoked`),
  r2_key, sha256, page_count, uploaded_by, approved_by, approved_at
- `document_pages`: document_id, page_number, text
- `document_models`: document_id, model_id
- `packs`: model_id, version (increments per model), sha256, r2_key,
  published_by, published_at, expires_at, superseded_at
- `pack_documents`: pack_id, document_id, revision (exact revision included)
- `jobs`: customer, site_name, site_address, equipment_id, problem, status
  (`open` | `assigned` | `in_progress` | `completed` | `cancelled`),
  assigned_user_id, due_at, conflict (bool)
- `sessions`: job_id, user_id, device_id, started_at, ended_at,
  equipment_confirmed (bool), flagged_by, flag_note
- `captures`: session_id, r2_key, source (`glasses` | `phone`), taken_at
- `observations`: session_id, kind, value, note, citations (JSON: document,
  revision, page), recorded_at
- `audit_log`: actor (user or device), action, entity, entity_id, before
  (JSON), after (JSON), at

Rules:
- An approved document is immutable. A change means uploading a new revision.
- Revoking a document removes it from new packs and marks every pack that
  contains it as superseded; phones drop superseded packs at next sync.
- A pack contains only approved documents and is itself immutable.
- Field records uploaded from phones are append-only on the server.

## 6. Console screens

1. Dashboard: open jobs, manuals awaiting approval, phones not seen in 7 days,
   packs expiring within 14 days.
2. Manuals: upload PDF (signed R2 upload), view extracted page text, set
   title, revision and covered models, approve or revoke.
3. Reference packs: per model; build from approved manuals, publish, see
   which phones have it.
4. Jobs: list with filters, create, edit, assign, cancel; status from sync.
5. Equipment: models and serial-numbered units at customer sites.
6. People & devices: add people by email, set role, deactivate; generate a
   pairing code for a technician; revoke a phone.
7. Session review: per-job timeline of start, equipment confirmation,
   captures, observations with Bob's citations, end; flag with a note.
8. Audit log: searchable, read-only.

Stack: React 18 + Vite + TypeScript, React Router, TanStack Query for API
calls, a small component set (no heavy UI framework), served by Pages.

## 7. Manual processing

- Upload goes directly to R2 through a short-lived signed URL; the Worker
  records the object and its SHA-256.
- Text is extracted per page with pdf.js in the Worker (or a Queue consumer
  if it exceeds Worker CPU limits). Pages become `document_pages` rows.
- A PDF with no text layer is rejected with: "This PDF has no text (it may
  be a scan). Upload a text PDF; scanned manuals are not supported yet."
- Pack file: JSON matching the phone's existing `KnowledgePack` /
  `SourceDocument` / `DocPage` shape, gzipped, stored in R2 with its SHA-256.

## 8. Phone sync

- Pairing: technician enters the 6-digit code in the phone's Settings; the
  phone calls `/api/device/pair`, receives its device key once, and stores it
  in Android Keystore-backed encrypted storage.
- Triggers: app open, job start, job end, every 15 minutes while online.
  The phone works offline as today; sync is never required to use cached jobs
  and packs.
- Download: `GET /api/device/sync` returns assigned jobs, the pack manifest
  (model, version, sha256, URL) for those jobs' equipment, and superseded
  pack IDs. The phone downloads packs and installs them only if the SHA-256
  matches (existing verified-install path).
- Upload: `POST /api/device/records` with sessions, observations and capture
  metadata; each record has a client-generated ULID so retries are
  idempotent. Photos upload to R2 through signed URLs.
- The phone's existing `SyncState` (`SAVED_LOCALLY`, `SYNC_PENDING`,
  `SYNCED`, `CONFLICT`, `SYNC_FAILED`) drives the per-record status.
- Conflicts: the office owns job details; the phone owns field results.
  If a job is cancelled or reassigned while a technician has an active
  session, the phone keeps the work, the server stores it, sets
  `jobs.conflict = true`, and the session appears flagged for review.

## 9. Errors

- Console: plain messages for upload failure, no text layer, not allowed
  (403), and stale edit (409 when `updated_at` changed since load).
- API: JSON `{ error: <code>, message: <plain text> }`; internal details go
  to logs only. The existing `/token` handler that returns raw error text is
  fixed as part of this work.
- Revoked phone: 401 `device_revoked`; the phone shows "This phone was
  removed. Ask your admin to pair it again."

## 10. Security

- No secrets in the repo; Worker secrets via `wrangler secret` and
  `.dev.vars` (gitignored).
- Device keys and pairing codes stored only as hashes; pairing codes expire
  after 10 minutes, are single-use, and allow 5 attempts.
- R2 objects are private; downloads use short-lived signed URLs.
- Every admin and device write is recorded in `audit_log`.
- Manual text is reference data for Bob, never instructions (existing rule).

## 11. Testing

- Worker: Vitest with `@cloudflare/vitest-pool-workers` (real D1/R2
  simulations): role checks per route, pairing and revocation, pack build
  and supersede on revoke, idempotent record upload, audit entries.
- Console: component tests for the manual upload/approve and job forms; one
  Playwright test of the main path (upload manual → approve → publish pack →
  create job → assign).
- Phone: unit tests for the sync client (pairing, pack checksum rejection,
  idempotent retry, conflict handling).
- End to end on the Fold: pair, sync, see the real job and pack, ask Bob a
  question answered from the newly uploaded manual, end the job, see the
  session in the console.

## 12. Build order

1. Foundation: D1 schema + migrations, Access and device auth, roles, audit
   log, error format, device pairing API and phone pairing screen (a minimal
   console page to add users and issue pairing codes, so the phone can sync
   from step 2 on).
2. Manuals → packs (API + console screens 2–3) and phone pack sync.
3. Jobs (API + console screen 4) and phone job sync.
4. Equipment and full people & devices management (screens 5–6).
5. Session review (screen 7), record upload, audit log screen (8),
   dashboard (1).

Each step ends with its tests passing and, where the phone is involved, a
check on the Fold.
