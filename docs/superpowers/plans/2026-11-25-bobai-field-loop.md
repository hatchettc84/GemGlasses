# BobAI Field Loop Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A technician wearing Meta Ray-Ban glasses says "Hey Bob", Bob answers through the glasses, quotes manuals that the office uploaded and approved in the console, and knows the technician's jobs created in the console.

**Architecture:** The Worker gains documents (R2 originals, page text in D1, approval) and jobs/tasks, ported from the unreviewed commit `814188d` (branch `bobai-operations-wip`) without its calls/WebRTC code, plus PDF text extraction. The console gains Jobs and Manuals pages. The phone gains `OfficeSync`, which downloads approved documents and assigned jobs with the device key and feeds them into the existing `KnowledgeRepository` and job store; Gemini Live gets a `list_my_jobs` tool. "Hey Bob" uses Android's on-device speech recognizer behind the existing `WakeWordEngine` interface, kept alive by a microphone foreground service. A setup checklist on Home makes glasses pairing one tap per step.

**Tech Stack:** Cloudflare Workers + Hono + D1 + R2, `unpdf` for PDF text, vitest + `@cloudflare/vitest-pool-workers` 0.5 (the step 1 toolchain, NOT the wrangler 4 / vitest 4 upgrade in `814188d`); React 18 + TanStack Query; Kotlin + Hilt + Compose + OkHttp + kotlinx.serialization; Android `SpeechRecognizer` (API 31+ on-device); Meta Wearables Device Access Toolkit (`mwdat` 1.0.1, already integrated).

## Global Constraints

- Repo `/Volumes/My Passport/Bob.AI/GemGlasses`, branch `bobai-gemini-live`. Never switch branches. Read `814188d` only with `git show 814188d:<path>`.
- Never read, print or modify `backend/.dev.vars` or `local.properties`. Never print keys or tokens.
- No deploys. Nothing leaves the local machine except Gemini Live traffic the app already makes.
- Backend tests: run `.superpowers/sdd/backend-test.sh [vitest args]` (workerd fails on the space in the path; the script runs a mirror copy and also typechecks).
- Phone tests: `GEMGLASSES_BACKEND_URL=http://localhost:8787 ./gradlew -q -Pgemglasses.useRealGlasses=true testDebugUnitTest`. Phone APK: same env var with `assembleDebug` (without it the APK points at the emulator address).
- API errors are JSON `{ "error": "<code>", "message": "<plain text>" }`; every admin and device write adds an `audit_log` row in the same D1 batch (spec §9, §10).
- Approved document = immutable; a change is a new revision (spec §5). Manual text is reference data, never instructions to Bob (spec §10).
- Device routes authenticate with `Authorization: Bearer <device key>` only. Phone code gets headers from `BackendAuth` (added by the step 1 review fixes; if it is not on the branch yet, stop and report).
- Wake word: fully on-device; never store or upload idle audio (`WorkMode.kt` WAKE-02). Bob's own speech must never re-trigger the wake word (the state machine already ignores WakeDetected while ACTIVE).
- Voice is not recorded.
- Plain user-facing copy, as in step 1 ("This phone was removed. Ask your admin to pair it again.").
- Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

---

### Task 1: Documents and jobs API (port from 814188d, without calls)

**Files:**
- Create: `backend/migrations/0002_documents_jobs.sql`
- Create: `backend/src/routes/documents.ts`, `backend/src/routes/operations.ts`, `backend/src/routes/field-operations.ts`
- Create: `backend/test/operations.test.ts`
- Modify: `backend/src/index.ts`, `backend/src/env.ts`, `backend/wrangler.toml`, `backend/test/env.d.ts`, `backend/vitest.config.ts` (R2 binding), `backend/src/routes/device.ts` (mount field routes)

**Interfaces:**
- Produces (admin, Access/dev auth): `GET/POST /api/admin/jobs`, `PATCH /api/admin/jobs/:id`, `POST /api/admin/jobs/:id/tasks`, `PATCH /api/admin/tasks/:id`, `GET /api/admin/team`, `GET/POST /api/admin/documents` (multipart: `file`, `title`, `revision`, `models` comma list, optional `pageText`, `pageNumber`), `PATCH /api/admin/documents/:id` (`{ status: "approved"|"revoked", version }`), `GET /api/admin/documents/:id/file`, `GET /api/admin/audit`. (Confirm exact paths against `814188d:backend/src/routes/operations.ts` and `documents.ts`; those files are the source of truth.)
- Produces (device key): `GET /api/device/jobs` → `{ jobs: JobRow[], tasks: TaskRow[] }`; `PATCH /api/device/jobs/:id`, `PATCH /api/device/tasks/:id`; `GET /api/device/knowledge` → `{ tenantId, generatedAt, expiresAt, documents: SourceDocument[] }` where each document has `packId: "office-approved"`, `approvalStatus: "APPROVED"`, `isMock: false`, `pages: [{number, heading, body}]`.

- [ ] **Step 1: Port the migration without the calls tables**

Write `backend/migrations/0002_documents_jobs.sql` = the `jobs`, `tasks` and `documents` `CREATE TABLE`/`CREATE INDEX` statements from `git show 814188d:backend/migrations/0002_operations.sql` (its first 22 lines), dropping `support_calls` and `call_signals`. Do not port `0003_pairing_uniqueness.sql` (pairing work, out of scope).

Check `pages_json` holds `[{ "number": 1, "heading": "...", "body": "..." }]` (the phone's `DocPage` shape) by reading how `814188d:backend/src/routes/documents.ts` builds it; if it builds a different shape, convert in `/knowledge` so the phone receives `{number, heading, body}`.

- [ ] **Step 2: Port the routes, removing every calls dependency**

```bash
git show 814188d:backend/src/routes/documents.ts > backend/src/routes/documents.ts
git show 814188d:backend/src/routes/operations.ts > backend/src/routes/operations.ts
git show 814188d:backend/src/routes/field-operations.ts > backend/src/routes/field-operations.ts
```
In `field-operations.ts` delete the `./calls` import and every `/calls` route (from `fieldOperations.get("/calls"` to the end of the file). Compare the imports each file uses (`readJson`, `auditStmt`, `auditStmtIfExists`, `actorFromUser`, `requireRole`, `nowIso`, `ulid`) against this branch's `backend/src/lib/*.ts` and `auth/*.ts`; where `814188d` changed a helper's signature, adapt the call sites to this branch's helpers rather than porting the helper change. Do not port `ALLOW_LEGACY_AUTH`, the `814188d` Origin middleware, or its PeoplePage rewrite (this branch's review-fix commits already added an Origin/content-type check).

Mount: in `backend/src/index.ts` add `app.route("/api/admin", operations); app.route("/api/admin", documents);`. Mount `fieldOperations` under the existing device router so it inherits device-key auth: in `backend/src/routes/device.ts` add `deviceApi.route("/", fieldOperations);` after its auth middleware (check how `/me` gets `c.get("device")` and follow the same middleware).

Add R2 to `wrangler.toml`:
```toml
[[r2_buckets]]
binding = "DOCUMENTS"
bucket_name = "bobai-documents"
```
`env.ts`: `DOCUMENTS: R2Bucket;`. Mirror it in `test/env.d.ts` and in `vitest.config.ts` miniflare options (`r2Buckets: ["DOCUMENTS"]`).

- [ ] **Step 3: Port the tests without the calls cases**

`git show 814188d:backend/test/operations.test.ts > backend/test/operations.test.ts`, delete every test that touches `/calls`, `support_calls`, TURN or signals. Adapt helper imports to this branch's `test/helpers.ts`. The remaining tests must cover at least: dispatcher creates and assigns a job; a technician's phone sees only its own jobs via `/api/device/jobs`; stale job PATCH returns 409; reviewer uploads a `.txt`, approves it, and `/api/device/knowledge` returns it in `SourceDocument` shape; a revoked document disappears from `/knowledge`; a technician (console role) cannot upload; an unpaired request to `/api/device/knowledge` is 401. Add any of these that the ported file lacks, written with the helpers the ported file uses for upload and pairing. One must assert the page shape exactly:
```ts
expect(body.documents[0].pages[0]).toEqual({ number: 1, heading: expect.any(String), body: expect.stringContaining("Turn the gas valve to OFF") });
```

- [ ] **Step 4: Run and fix until green**

Run: `.superpowers/sdd/backend-test.sh`
Expected: all existing tests plus the ported ones pass; `typecheck ok`.

- [ ] **Step 5: Apply the migration locally and commit**

```bash
cd backend && npm run db:migrate:local && cd ..
git add backend && git commit -m "backend: documents (R2, approval) and jobs/tasks API for console and phones

Ported from 814188d without calls/WebRTC.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Extract PDF text on upload

**Files:**
- Modify: `backend/package.json` (add `"unpdf": "0.12.1"`), `backend/src/routes/documents.ts`
- Create: `backend/src/lib/pdf-text.ts`, `backend/test/fixtures/pdfs.ts` (base64 constants)
- Test: `backend/test/pdf-text.test.ts`

**Interfaces:**
- Consumes: Task 1's upload route.
- Produces: `extractPdfPages(bytes: Uint8Array): Promise<{ number: number; heading: string; body: string }[]>`; uploads of a text PDF need no pasted text.

- [ ] **Step 1: Write the failing test**

Generate two fixtures with a throwaway script in the scratchpad (not committed): `TWO_PAGES_PDF` (page 1 "Page one: torque 12 Nm", page 2 "Page two: reset the breaker") and `EMPTY_PDF` (one page, empty content stream). A hand-written minimal PDF 1.4 with `BT /F1 12 Tf 72 720 Td (text) Tj ET` content streams and a Helvetica Type1 font is enough. Store both as base64 strings in `backend/test/fixtures/pdfs.ts`.
```ts
import { describe, expect, it } from "vitest";
import { extractPdfPages } from "../src/lib/pdf-text";
import { EMPTY_PDF, TWO_PAGES_PDF } from "./fixtures/pdfs";

const bytes = (b64: string) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));

describe("extractPdfPages", () => {
  it("returns one entry per page with its text", async () => {
    const pages = await extractPdfPages(bytes(TWO_PAGES_PDF));
    expect(pages.map((p) => p.number)).toEqual([1, 2]);
    expect(pages[0].body).toContain("torque 12 Nm");
    expect(pages[1].body).toContain("reset the breaker");
  });
  it("returns no pages for a PDF without a text layer", async () => {
    expect(await extractPdfPages(bytes(EMPTY_PDF))).toEqual([]);
  });
});
```
Add a route test: uploading `TWO_PAGES_PDF` as `application/pdf` without `pageText` stores 2 pages; uploading `EMPTY_PDF` returns 422 `no_text_layer`.

- [ ] **Step 2: Run it and see it fail** (`.superpowers/sdd/backend-test.sh test/pdf-text.test.ts`; expected: cannot resolve `../src/lib/pdf-text`).

- [ ] **Step 3: Implement**

```ts
// backend/src/lib/pdf-text.ts
import { extractText, getDocumentProxy } from "unpdf";

export interface PageText { number: number; heading: string; body: string }

export async function extractPdfPages(bytes: Uint8Array): Promise<PageText[]> {
  const pdf = await getDocumentProxy(bytes);
  const { text } = await extractText(pdf, { mergePages: false });
  return (text as string[])
    .map((body, i) => ({ number: i + 1, body: body.replace(/\s+/g, " ").trim() }))
    .filter((p) => p.body.length > 0)
    .map((p) => ({ ...p, heading: p.body.slice(0, 60) }));
}
```
In the upload route: when the file is a PDF and no `pageText` was sent, set pages from `extractPdfPages`; if that returns `[]`, reject with 422 `{ error: "no_text_layer", message: "This PDF has no text (it may be a scan). Upload a text PDF; scanned manuals are not supported yet." }` (spec §7 wording). Keep the `.txt` and pasted-text paths.

- [ ] **Step 4: Run all backend tests** — expected green, `typecheck ok`. If `unpdf` fails to load in the workers pool, report the error instead of silently switching libraries.

- [ ] **Step 5: Commit** — `backend: extract page text from uploaded PDFs`.

---

### Task 3: Console Jobs and Manuals pages

**Files:**
- Create: `console/src/pages/JobsPage.tsx`, `console/src/pages/ManualsPage.tsx`, `console/src/pages/JobsPage.test.tsx`, `console/src/pages/ManualsPage.test.tsx`
- Modify: `console/src/App.tsx` (nav + routes), `console/src/api/hooks.ts`, `console/src/api/types.ts`, `console/src/api/client.ts` (multipart upload)

**Interfaces:**
- Consumes: Task 1 admin routes.
- Produces: nav links "People", "Jobs", "Manuals".

- [ ] **Step 1: Write failing tests** in the style of `console/src/pages/PeoplePage.test.tsx` (same `mockApi`/`renderPage` helpers, copied locally):
  - Jobs: lists jobs; creating a job with title "Replace igniter", site "Riverside Clinic", assigned to "Tia" POSTs the body the Task 1 route expects (read it from `operations.ts`); adding a task POSTs to `/jobs/<id>/tasks`; a 409 shows the server message.
  - Manuals: lists documents with status; uploading sends `FormData` with `file`, `title`, `revision`, `models`; "Approve" PATCHes `{ status: "approved", version }`; a 422 `no_text_layer` shows its message; "Revoke" asks on the page (Yes, revoke / Cancel) before PATCHing.

- [ ] **Step 2: Run** `cd console && npm test` — new tests fail.

- [ ] **Step 3: Implement** using `git show 814188d:console/src/pages/OperationsPages.tsx` as the reference for fields and API calls, but: no calls/RTC code, use this branch's `apiFetch`/hooks pattern and existing CSS classes (`card`, `row`, `field`, `muted`, `button.danger`, `button.secondary`, `.confirm`), and the in-page confirmation pattern from `PeoplePage.tsx` (no `window.confirm`). For upload, add to `client.ts`:
```ts
export async function apiUpload<T>(path: string, form: FormData): Promise<T> {
  return apiFetch<T>(path, { method: "POST", body: form });
}
```
and make `apiFetch` leave `Content-Type` unset when `body instanceof FormData` (the browser sets the multipart boundary). Confirm with a backend test or the dev server that a same-origin multipart POST passes the review fixes' Origin/content-type rule.

- [ ] **Step 4: Run** `npm test && npm run build` — green.

- [ ] **Step 5: Commit** — `console: Jobs and Manuals pages`.

---

### Task 4: Phone downloads approved manuals and assigned jobs (OfficeSync)

**Files:**
- Create: `app/src/main/java/com/lpecom/gemglasses/bobai/office/OfficeApi.kt`, `.../bobai/office/OfficeSync.kt`
- Modify: `.../bobai/data/BobAiController.kt`, `.../bobai/domain/Models.kt` (Job fields), `.../bobai/domain/KnowledgeRepository.kt`, `.../bobai/domain/EquipmentResolver.kt`, `.../bobai/ui/BobAiViewModel.kt`, the Hilt module that provides `BobAiController`
- Test: `app/src/test/java/com/lpecom/gemglasses/bobai/OfficeSyncTest.kt`, `BobAiControllerOfficeTest.kt`

**Interfaces:**
- Consumes: `BackendAuth` (review fixes) for headers; `/api/device/knowledge`, `/api/device/jobs`.
- Produces:
```kotlin
@Serializable data class OfficeKnowledge(val tenantId: String, val generatedAt: Long, val expiresAt: Long, val documents: List<SourceDocument>)
@Serializable data class OfficeJobRow(val id: String, val title: String, val site: String, val description: String = "", val status: String, val dueAt: String? = null, val revision: Int)
@Serializable data class OfficeTaskRow(val id: String, val jobId: String, val title: String, val completed: Int, val revision: Int)
@Serializable data class OfficeWork(val jobs: List<OfficeJobRow>, val tasks: List<OfficeTaskRow>)
interface OfficeApi { suspend fun knowledge(): OfficeKnowledge; suspend fun work(): OfficeWork }
sealed interface SyncOutcome { data object Synced : SyncOutcome; data object NotPaired : SyncOutcome; data object Removed : SyncOutcome; data class Failed(val message: String) : SyncOutcome }
class OfficeSync(api: OfficeApi, dir: File, controller: BobAiController, isPaired: () -> Boolean) {
    suspend fun syncNow(): SyncOutcome
    fun loadCached()                              // on launch, before any network
}
// BobAiController additions:
fun applyOfficeKnowledge(k: OfficeKnowledge)     // documents + "office-approved" pack + known models + tenant
fun applyOfficeWork(w: OfficeWork)                // upserts office jobs (officeId set), keeps local sessions
```
Match `@SerialName`s to the real JSON column names returned by `jobSelect` in `814188d:backend/src/routes/operations.ts` and `SELECT t.*` (snake_case such as `job_id`, `due_at` unless aliased); use `Json { ignoreUnknownKeys = true }`.

- [ ] **Step 1: Write failing tests (pure JVM)**

`BobAiControllerOfficeTest` — write each body concretely with the existing test helpers/fakes in `app/src/test` (in-memory `JobSessionStore`, fixed clock):
1. `office documents become searchable for a confirmed model`: apply knowledge with one doc for model `ZX-9`, page 1 body "Turn the gas valve to OFF before opening the panel."; `startOrResume()`; `confirmEquipment("ZX-9", "SN1")`; `knowledge.answer("how do I open the panel", equipment)` returns the evidence subtype citing that document, revision and page 1.
2. `office models become known`: `confirmEquipment("ZX-9", …)` is rejected before apply and confirmed after.
3. `office jobs replace previous office jobs and keep local sessions`: apply work twice with different job lists → only the second list's office jobs remain; a local job with a session survives.
4. `expired office knowledge is not readable`: `expiresAt` in the past → no_evidence.

`OfficeSyncTest`:
1. `caches knowledge and reloads it without network`: fake api; `syncNow()` = Synced; new `OfficeSync` (same dir) with an api that throws; `loadCached()` → controller has the doc.
2. `not paired never calls the api`: `isPaired = { false }` → `NotPaired`, fake api call count 0.
3. `removed phone stops syncing`: api throws `DeviceRemovedException` → `Removed`, cache files unchanged.
4. `a failed sync keeps the last good data`: good sync, then api throws `IOException` → `Failed(...)` and controller still has the doc.

- [ ] **Step 2: Run phone tests** — new tests fail.

- [ ] **Step 3: Implement**

`KnowledgeRepository`: first parameter becomes `documents: () -> List<SourceDocument>`; update internal uses and existing call sites/tests.

`EquipmentResolver`: take `knownModels: () -> Map<String, String>`; update uses.

`BobAiController`:
- `@Volatile private var officeDocs: List<SourceDocument> = emptyList()`, `officeModels: Map<String, String>`, `officeTenant: String?`.
- `val resolver = EquipmentResolver { MockContent.knownModels + officeModels }`; `val knowledge = KnowledgeRepository({ MockContent.documents + officeDocs }, { _state.value.packs }, now)`.
- `applyOfficeKnowledge(k)`: set `officeDocs = k.documents.map { it.copy(packId = "office-approved", isMock = false) }`, `officeModels = docs.flatMap { d -> d.applicableModels.map { normalizeModel(it) to d.title } }.toMap()` (use the resolver's normalisation), `officeTenant = k.tenantId`; upsert `KnowledgePack(id = "office-approved", tenantId = k.tenantId, name = "Office manuals", version = k.generatedAt.toString(), sizeBytes = 0, documentIds = officeDocs.map { it.id }, state = PackState.COMPLETE, downloadedAt = now(), lastVerifiedAt = now(), expiresAt = k.expiresAt)` into `StoreSnapshot.packs` via `mutate`.
- Wherever confirmed `Equipment` gets `tenantId = MockContent.TENANT`, use `officeTenant ?: MockContent.TENANT` when the model is an office model (mock models keep the mock tenant), so office documents pass the tenant filter.
- `Job` gains defaulted fields `title: String = ""`, `description: String = ""`, `dueAt: String? = null`, `officeId: String? = null`, `tasks: List<JobTask> = emptyList()`, with `@Serializable data class JobTask(val id: String, val title: String, val done: Boolean)`. Old `store.json` files must still load (all defaulted).
- `applyOfficeWork(w)`: drop jobs with `officeId != null` that are not in `w.jobs` unless a non-ended session references them; upsert the rest as `Job(id = "office-" + row.id, officeId = row.id, tenantId = officeTenant ?: MockContent.TENANT, site = row.site, title = row.title, description = row.description, dueAt = row.dueAt, status = open→OPEN / in_progress→IN_PROGRESS / completed→COMPLETED, isMock = false, syncState = SyncState.SYNCED, tasks = w.tasks.filter { it.jobId == row.id }.map { JobTask(it.id, it.title, it.completed == 1) })`, keeping `createdAt` of an existing job.

`HttpOfficeApi : OfficeApi`: OkHttp GET `BuildConfig.BACKEND_URL + "/api/device/" + path` with headers from `BackendAuth`; a 401 goes through `BackendAuth`'s removed-phone handling (throws `DeviceRemovedException`); other non-2xx → `IOException("Office sync failed (HTTP <code>)")`. Never send the app secret: `OfficeSync` returns `NotPaired` without a request when not paired.

`OfficeSync`: on success write `knowledge.json` and `work.json` atomically (temp file + `renameTo`) in `<dir>` = `context.filesDir/bobai/office/`; `loadCached()` reads and applies them (a corrupt file is ignored and deleted).

Wiring: provide `OfficeApi`/`OfficeSync` via Hilt next to `BobAiController`. In `BobAiViewModel.init`: `officeSync.loadCached()`, then `viewModelScope.launch { while (isActive) { refreshOffice(); delay(15.minutes) } }`; call `refreshOffice()` right after a successful pairing too. Expose `officeStatus: StateFlow<String>`: "Office: synced <time> · <n> manuals · <m> jobs", "Office: not paired", "Office: this phone was removed", "Office: sync failed — <message>".

- [ ] **Step 4: Run phone tests** — all green.

- [ ] **Step 5: Commit** — `app: download approved manuals and assigned jobs from the office`.

---

### Task 5: Bob knows the technician's jobs

**Files:**
- Modify: `.../bobai/live/BobLiveTools.kt`, `.../bobai/live/BobVoiceAssistant.kt` (`answerTools`, `start`), `.../bobai/ui/Screens.kt` (Jobs tab shows office title/description/tasks)
- Test: `app/src/test/java/com/lpecom/gemglasses/bobai/BobLiveToolsJobsTest.kt`

**Interfaces:**
- Consumes: `Job.title/description/dueAt/tasks/officeId`, `JobTask` from Task 4.
- Produces: `const val LIST_JOBS = "list_my_jobs"`; `BobLiveTools.jobsJson(jobs: List<Job>): JsonObject`; `systemInstruction(equipment: Equipment?, jobs: List<Job> = emptyList())`.

- [ ] **Step 1: Failing tests**

```kotlin
class BobLiveToolsJobsTest {
    private val job = Job(id = "office-J1", tenantId = "t", site = "Riverside Clinic", title = "Replace igniter",
        description = "Unit 2 fails to light", createdAt = 0, updatedAt = 0, officeId = "J1",
        tasks = listOf(JobTask("T1", "Check gas pressure", false)))

    @Test fun `declares list_my_jobs`() = assertTrue(
        BobLiveTools.declarations.flatMap { it.functionDeclarations.orEmpty() }.any { it.name == BobLiveTools.LIST_JOBS })

    @Test fun `jobs json carries title site status and tasks`() {
        val o = BobLiveTools.jobsJson(listOf(job))
        assertEquals("ok", o["status"]!!.jsonPrimitive.content)
        val j = o["jobs"]!!.jsonArray.single().jsonObject
        assertEquals("Replace igniter", j["title"]!!.jsonPrimitive.content)
        assertEquals("Riverside Clinic", j["site"]!!.jsonPrimitive.content)
        assertEquals("Check gas pressure", j["tasks"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content)
    }

    @Test fun `local jobs are not office jobs`() =
        assertEquals("no_jobs", BobLiveTools.jobsJson(listOf(job.copy(officeId = null)))["status"]!!.jsonPrimitive.content)

    @Test fun `system instruction tells Bob to use the jobs tool and names the job`() {
        val s = BobLiveTools.systemInstruction(null, listOf(job))
        assertTrue(s.contains(BobLiveTools.LIST_JOBS))
        assertTrue(s.contains("Replace igniter"))
    }
}
```
(Adjust `functionDeclarations` access to the actual `Tool` class in the gemini package.)

- [ ] **Step 2: Run** — fails.

- [ ] **Step 3: Implement**
- Declaration: name `list_my_jobs`, description "List the technician's assigned jobs from the office: title, site, status, due date and tasks. Call it when they ask about their jobs, what's next, or where they are going.", parameters `{"type":"object","properties":{}}`.
- `jobsJson`: office jobs only (`officeId != null`); `status` `ok` / `no_jobs`; `jobs` = `[{title, site, status, due, description, tasks:[{title, done}]}]`.
- `systemInstruction`: add rule "7. For questions about jobs call list_my_jobs and name the job by its title and site; never invent jobs." and a line "Assigned jobs: <title — site>; …" (first 5 office jobs) or "Assigned jobs: none from the office yet."
- `BobVoiceAssistant.start`: pass `controller.state.value.jobs`; `answerTools`: `BobLiveTools.LIST_JOBS -> BobLiveTools.jobsJson(controller.state.value.jobs)`.
- Jobs tab: office jobs show title, site, due and the task list (read-only).

- [ ] **Step 4: Run phone tests** — green.
- [ ] **Step 5: Commit** — `app: Bob lists and cites the technician's office jobs`.

---

### Task 6: "Hey Bob" wake word

**Files:**
- Create: `.../bobai/workmode/WakePhrase.kt`, `.../bobai/workmode/SpeechWakeWordEngine.kt`, `.../bobai/workmode/WakeListenService.kt`
- Modify: `.../bobai/workmode/WorkModeController.kt`, `.../bobai/ui/BobAiViewModel.kt`, `.../bobai/ui/Screens.kt` (Work Mode card), `.../bobai/live/BobVoiceAssistant.kt` (`sendAppNotice`), Hilt module, `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/lpecom/gemglasses/bobai/WakePhraseTest.kt`, `WakeHandoffTest.kt`

**Interfaces:**
- Consumes: `WakeWordEngine`, `EngineStart`, `WorkEvent.WakeDetected/Acknowledged/SessionEnded`, `BobVoiceAssistant.start()/state`, `BobAiController.startOrResume()`.
- Produces: `object WakePhrase { fun matches(text: String): Boolean }`; `class SpeechWakeWordEngine(context: Context) : WakeWordEngine`; `WorkModeController(context, engine: WakeWordEngine)`; `WorkModeController.acknowledge()`; `BobVoiceAssistant.sendAppNotice(text: String)`.

- [ ] **Step 1: Failing tests**

```kotlin
class WakePhraseTest {
    @Test fun `matches common ways of saying hey bob`() {
        listOf("hey bob", "Hey Bob.", "hey bob what's my next job", "hi bob", "okay bob", "ok bob", "hey bob,").forEach {
            assertTrue(it, WakePhrase.matches(it))
        }
    }
    @Test fun `ignores other speech`() {
        listOf("", "bobcat", "hey robert", "the bob", "kebab", "hey", "bobby brown", "they bought").forEach {
            assertFalse(it, WakePhrase.matches(it))
        }
    }
}
```
`WakeHandoffTest`: extract the wake hand-off into a pure class so it runs on the JVM:
```kotlin
class WakeHandoff(private val engine: WakeWordEngine, private val dispatch: (WorkEvent) -> Unit) {
    fun arm(route: MicRoute): EngineStart = engine.start(route) { engine.stop(); dispatch(WorkEvent.WakeDetected) }
}
```
Tests (with a `FakeEngine` that records start/stop and exposes `fire()`): firing the wake stops the engine and dispatches `WakeDetected`; running the real `WorkModeMachine.reduce` over `Enable → EngineReady → WakeDetected → Acknowledged` ends in `ACTIVE` with trigger `WAKE_WORD`; `WakeDetected` while `ACTIVE` leaves the phase unchanged; `SessionEnded` then re-arm calls `engine.start` again.

- [ ] **Step 2: Run** — fails.

- [ ] **Step 3: Implement**

```kotlin
object WakePhrase {
    private val pattern = Regex("""\b(?:hey|hi|okay|ok)\s+bob\b""", RegexOption.IGNORE_CASE)
    fun matches(text: String): Boolean = pattern.containsMatchIn(text)
}
```
`SpeechWakeWordEngine` (all recognizer calls on the main thread via `Handler(Looper.getMainLooper())`):
- `start(route, onWake)`: if `Build.VERSION.SDK_INT < 31 || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)` → `Failed("This phone has no on-device speech recognizer, so \"Hey Bob\" can't listen. Use Start assistance.")`. Else create `SpeechRecognizer.createOnDeviceSpeechRecognizer(context)`, set the listener, `listen()`, return `Started`.
- `listen()`: `Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)` with `EXTRA_LANGUAGE_MODEL = LANGUAGE_MODEL_FREE_FORM`, `EXTRA_PARTIAL_RESULTS = true`, `EXTRA_PREFER_OFFLINE = true`, `EXTRA_LANGUAGE = "en-US"`.
- `onPartialResults` / `onResults`: if any candidate in `RESULTS_RECOGNITION` matches `WakePhrase` → fire `onWake()` once (the hand-off stops the engine). Otherwise after `onResults`/`onError` restart `listen()` in 250 ms; after 5 errors within 30 s wait 5 s before restarting. Never store audio or transcripts; log only `"wake detected"` and error codes.
- `stop()`: remove pending restarts, `cancel()` + `destroy()` the recognizer, clear any communication device set below.
- Glasses mic: before the first `listen()`, if `route.kind == RouteKind.BLUETOOTH_HEADSET` and SDK ≥ 31, `AudioManager.setCommunicationDevice(<the TYPE_BLUETOOTH_SCO or TYPE_BLE_HEADSET input from availableCommunicationDevices>)`. The Work Mode card already shows the route.

`WakeListenService`: `foregroundServiceType="microphone"` service started from `WorkModeController.enable()` (a user tap, so a foreground-service start is allowed) and stopped by `disable()`; ongoing low-priority notification "BobAI is listening for \"Hey Bob\". No audio is saved." with a "Turn off" action that calls `disable()`. Manifest: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS` permissions and `<service android:name=".bobai.workmode.WakeListenService" android:foregroundServiceType="microphone" android:exported="false" />` (adjust the name to the real package path). On Android 13+ request `POST_NOTIFICATIONS` together with `RECORD_AUDIO` from the Work Mode card.

`WorkModeController`: constructor takes `engine: WakeWordEngine` (Hilt provides `SpeechWakeWordEngine`); `arm()` uses `WakeHandoff`; add `fun acknowledge() = dispatch(WorkEvent.Acknowledged)`.

`BobVoiceAssistant.sendAppNotice(text)`: once the Live session is connected, send a client text turn `"[App notice] $text"` (use the existing session/keeper client-content method; if there is none, add one that sends `clientContent` with `turnComplete = true`). Queue it if called before the session is ready.

`BobAiViewModel`: collect `workMode.state`; on `WAKE_DETECTED` → `workMode.acknowledge(); controller.startOrResume(); voice.start(); voice.sendAppNotice("The technician just said \"Hey Bob\". Greet them in a few words and ask how you can help.")`. Collect `voice.state`: when it goes from active to `OFF` while `workMode.state.value.isActive` → `workMode.endAssistance()` (re-arms).

Work Mode card copy: "Say \"Hey Bob\" to start. Listening happens on this phone only; no audio is saved or uploaded."

- [ ] **Step 4: Run phone tests** — green.
- [ ] **Step 5: Commit** — `app: "Hey Bob" wake word with the on-device speech recognizer`.

---

### Task 7: Glasses setup checklist

**Files:**
- Create: `.../bobai/ui/SetupChecklist.kt`
- Modify: `.../bobai/ui/Screens.kt` (Home), `.../bobai/ui/BobAiViewModel.kt`
- Test: `app/src/test/java/com/lpecom/gemglasses/bobai/SetupChecklistTest.kt`

**Interfaces:**
- Consumes: `vm.glasses`, `vm.registerGlasses()`, pairing state, `officeStatus` (Task 4), `workMode` (Task 6).
- Produces: `data class SetupStep(val id: String, val label: String, val done: Boolean, val action: String?)`; `fun setupSteps(glassesConnected: Boolean, micGranted: Boolean, phonePaired: Boolean, officeSynced: Boolean, heyBobOn: Boolean): List<SetupStep>`.

- [ ] **Step 1: Failing test**

```kotlin
class SetupChecklistTest {
    @Test fun `nothing done lists every step in order with its action`() {
        val s = setupSteps(false, false, false, false, false)
        assertEquals(listOf("glasses", "mic", "pair", "sync", "heybob"), s.map { it.id })
        assertEquals(listOf("Register with Meta AI", "Allow", "Enter code", "Sync now", "Turn on"), s.map { it.action })
        assertTrue(s.none { it.done })
    }
    @Test fun `all done has no actions`() =
        assertTrue(setupSteps(true, true, true, true, true).all { it.done && it.action == null })
}
```
Labels: "Connect your glasses", "Allow the microphone", "Pair with the office", "Download manuals and jobs", "Turn on \"Hey Bob\"".

- [ ] **Step 2: Run** — fails.
- [ ] **Step 3: Implement** the pure function and a `SetupCard` at the top of Home, shown until every step is done, then replaced by a chip "Ready — say \"Hey Bob\"". Actions: `vm.registerGlasses()`; the `RECORD_AUDIO` (+ `POST_NOTIFICATIONS`) launcher; navigate to Settings (Office console); `vm.refreshOffice()`; `vm.workMode.enable()`.
- [ ] **Step 4: Run phone tests** — green.
- [ ] **Step 5: Commit** — `app: one-card setup for glasses, microphone, pairing, sync and Hey Bob`.

---

### Task 8: End to end on the Fold

- [ ] `cd backend && npm install && npm run db:migrate:local`; restart `wrangler dev --port 8787 --ip 127.0.0.1` and the console dev server.
- [ ] In the console: upload a manual for model `ABC-123` (the mock label's model) as `.txt` and as a generated text PDF, approve both; create job "Replace igniter" at "Riverside Clinic" with task "Check gas pressure", assigned to "Fold Test Tech".
- [ ] Build with `GEMGLASSES_BACKEND_URL=http://localhost:8787`, install on RFCY81PZCTH, `adb reverse tcp:8787 tcp:8787`, Fold on Wi-Fi.
- [ ] Drive the app (scratchpad `fold.py`): setup card shows office synced with 2 manuals and 1 job; Jobs tab shows the job; server log shows `GET /api/device/knowledge 200` and `GET /api/device/jobs 200`.
- [ ] "Ask sources" with the manual question → answer cites the uploaded document, revision and page.
- [ ] Voice: start voice, confirm `POST /token 200` and that `list_my_jobs` / `search_approved_manuals` appear in logcat tool lines when asked (play the question with `say` from the Mac next to the phone, or ask the owner).
- [ ] Wake word: enable Work Mode, play `say "Hey Bob"` from the Mac speaker next to the phone; expect logcat "wake detected", voice starts, Bob greets. If the speaker can't trigger it, ask the owner to say it once.
- [ ] Revoke the manual in the console, sync, ask again → "no approved source".
- [ ] Record results in `.superpowers/sdd/progress.md`.
