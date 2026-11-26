# Plane Jobs Source Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax.

**Goal:** When the Worker is configured for Plane, a technician's jobs (what the phone syncs and what Bob reads with `list_my_jobs`) come from work items assigned to them in the self-hosted Plane instance, instead of the D1 `jobs` table.

**Architecture:** A server-only, read-only Plane adapter in the Worker (`backend/src/lib/plane.ts`) calls Plane's public REST API (`/api/v1/...`, header `X-API-Key`). `GET /api/device/jobs` keeps its exact response shape (`{ jobs, tasks }`, same row fields as today) so the Android app needs no change. The Plane member is matched to the BobAI user by email (case-insensitive). The office creates and assigns jobs in Plane; manuals stay in the BobAI console.

**Tech Stack:** Cloudflare Worker (Hono, D1), vitest 2 + @cloudflare/vitest-pool-workers 0.5, React console (vite, vitest).

## Global Constraints

- Plane instance: Community v1.5.0, local at `http://127.0.0.1:8095` (loopback only).
- Plane API base: `${PLANE_BASE_URL}/api/v1/`. Auth header: `X-API-Key: ${PLANE_API_KEY}`. Never log, return, or audit the key; never put it in a URL.
- Endpoints used (verified present in v1.5.0 source):
  - `GET workspaces/{slug}/members/` → array of members (each has `id`, `email`, `display_name`, `first_name`, `last_name`; tolerate either a bare array or `{ results: [...] }`)
  - `GET workspaces/{slug}/projects/` → paginated `{ results, next_cursor, next_page_results }` (tolerate bare array)
  - `GET workspaces/{slug}/projects/{project_id}/states/` → paginated, each state has `id`, `name`, `group` (`backlog|unstarted|started|completed|cancelled|triage`)
  - `GET workspaces/{slug}/projects/{project_id}/work-items/?per_page=100&cursor=...` → paginated; each item has `id`, `name`, `description_stripped`, `state` (state id), `assignees` (array of user ids), `target_date` (YYYY-MM-DD or null), `parent` (id or null), `sequence_id`, `updated_at`, `created_at`.
  - Pagination: follow `next_cursor` while `next_page_results` is true; hard cap 20 pages per project.
- New Env vars (all optional): `PLANE_BASE_URL`, `PLANE_API_KEY` (secret), `PLANE_WORKSPACE_SLUG`, `PLANE_PROJECT_IDS` (comma-separated; empty = all projects in workspace). Plane mode is ON only when `PLANE_BASE_URL`, `PLANE_API_KEY` and `PLANE_WORKSPACE_SLUG` are all set; otherwise D1 behavior is unchanged (all existing tests must keep passing).
- Mapping (Plane → existing jobs row shape):
  - `id` = `"plane-" + item.id`; `tenant_id` = device tenant; `title` = `item.name`; `site` = project name; `description` = `description_stripped` (trimmed, max 2000 chars, "" if null); `assigned_user_id` = device user id; `assigned_name` = BobAI user name; `due_at` = `target_date` or null; `revision` = 1; `created_by` = "plane"; `created_at`/`updated_at` from the item.
  - `status`: state group `started` → `in_progress`; `completed` → `completed`; `backlog|unstarted|triage` → `open`; `cancelled` → exclude the item.
  - Top-level items (`parent` null) assigned to the matched member become jobs. Items whose `parent` is one of those jobs become `tasks` rows: `id` = `"plane-" + item.id`, `job_id` = `"plane-" + parent`, `title` = name, `completed` = 1 if state group is `completed` else 0 (cancelled sub-items excluded), `revision` = 1, timestamps from item. Sub-items do not need to be assigned.
  - Jobs ordered by `updated_at` desc, max 500; tasks max 5000.
- No Plane member with the device user's email → `{ jobs: [], tasks: [] }` (200).
- Plane unreachable, non-2xx, or malformed JSON → 502 `{ error: "plane_unavailable", message: "Jobs are managed in Plane, which could not be reached. Try again shortly." }`. Log one `console.error` line with status only (no key, no body).
- In Plane mode, device `PATCH /api/device/jobs/:id` and `PATCH /api/device/tasks/:id` for ids starting `plane-` → 409 `{ error: "managed_in_plane", message: "This job is managed in Plane. Update it there." }`.
- Admin: `GET /api/admin/integrations/plane` (any signed-in office role) → `{ enabled: boolean, workspaceSlug: string|null, webUrl: string|null }` where `webUrl` = `${PLANE_BASE_URL}/${slug}/` when enabled. Does not call Plane.
- Errors stay JSON `{error, message}`. Backend tests run only via `.superpowers/sdd/backend-test.sh` (the repo path has a space). Never read, print or modify `backend/.dev.vars`.

---

### Task 1: Worker Plane adapter and device jobs from Plane

**Files:**
- Create: `backend/src/lib/plane.ts`
- Modify: `backend/src/env.ts` (add the four optional vars with doc comments)
- Modify: `backend/src/routes/field-operations.ts` (GET /jobs branches on Plane mode; PATCH guards)
- Create: `backend/src/routes/integrations.ts` and mount it under `/api/admin` in `backend/src/index.ts` next to the other admin routes (behind the existing `csrfGuard, adminAuth`)
- Test: `backend/test/plane.test.ts` (adapter + routes; stub `globalThis.fetch` with `vi.spyOn` for Plane URLs only; follow `backend/test/helpers.ts` patterns)
- Modify: `backend/README.md` (Plane section: env vars, how to create the API key in Plane → Profile settings → Personal access tokens, the `.dev.vars` lines the owner adds themselves)

**Interfaces produced:**
- `export function planeConfig(env: Env): PlaneConfig | null`
- `export async function fetchPlaneJobs(cfg: PlaneConfig, email: string, user: { id: string; name: string; tenantId: string }): Promise<{ jobs: JobRow[]; tasks: TaskRow[] }>` throwing `ApiError(502, "plane_unavailable", ...)` on upstream failure.

- [ ] Write failing tests covering: D1 mode unchanged when Plane vars absent; mapping of every field above; state-group mapping incl. cancelled excluded; sub-items → tasks; pagination follows `next_cursor` and stops at cap; `PLANE_PROJECT_IDS` filter; email match case-insensitive; no member → empty; upstream 500 / network error / bad JSON → 502 `plane_unavailable` and the API key not present in the response body; `X-API-Key` header sent and key never in the URL; PATCH plane ids → 409 `managed_in_plane`; integrations endpoint enabled/disabled shapes.
- [ ] Run `.superpowers/sdd/backend-test.sh` and see the new tests fail.
- [ ] Implement.
- [ ] Run the full backend suite + typecheck via the script; all green.
- [ ] Commit: `backend: read technician jobs from self-hosted Plane when configured`

### Task 2: Console shows Plane mode on the Jobs page

**Files:**
- Modify: the console `JobsPage` component, its API helper, and its test (under `console/src/`).

- [ ] Write failing test: when `GET /api/admin/integrations/plane` returns `{ enabled: true, webUrl }`, the Jobs page shows the notice "Jobs are managed in Plane." with a link "Open Plane" to `webUrl` (new tab, `rel="noopener noreferrer"`) and hides the create-job form; when disabled or the call fails, the page behaves exactly as today.
- [ ] Implement; run `npm test` in `console/` (all green) and the project's typecheck if it has one.
- [ ] Commit: `console: point dispatchers to Plane when jobs come from Plane`
