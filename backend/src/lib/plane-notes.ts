import type { Context } from "hono";
import type { AppEnv, Env } from "../env";
import { fetchPlaneJobs, planeConfig, planeList, planeSend, PLANE_ID_PREFIX, type JobRow, type PlaneConfig, type PlaneResult } from "./plane";

/**
 * Mirrors technicians' stickies into Plane, in the background after the phone already got its answer:
 *  - sticky on a Plane job      -> a comment on that work item;
 *  - otherwise (or job missing) -> a work item in the project with identifier NOTES ("Field Notes").
 * Rows stay plane_state='pending' / 'delete_pending' until Plane confirms, and are retried later.
 * Never logs titles, text or the key: only sticky ids, Plane paths (ids), statuses and durations.
 */

export const NOTES_IDENTIFIER = "NOTES";
export const NOTES_NAME = "Field Notes";
const EXTERNAL_SOURCE = "bobai";
/** A mirror run gives up its claim after this long (Workers' waitUntil ends well before). */
const LEASE_MS = 90_000;
const RETRY_LIMIT = 10;
const MAX_PASSES = 3;

interface StickyRow {
  tenant_id: string;
  user_id: string;
  id: string;
  kind: "note" | "draft";
  title: string;
  text: string;
  job_id: string | null;
  job_title: string | null;
  created_at: string;
  done: number;
  updated_at: string;
  plane_project_id: string | null;
  plane_item_id: string | null;
  plane_comment_id: string | null;
  plane_state: "pending" | "synced" | "delete_pending";
  deleted: number;
}

interface Ids {
  project: string | null;
  item: string | null;
  comment: string | null;
}

class MirrorError extends Error {}

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => v !== null && typeof v === "object" && !Array.isArray(v);
const str = (v: unknown): string => (typeof v === "string" ? v : "");
const ok = (r: PlaneResult) => r.status >= 200 && r.status < 300;
const enc = encodeURIComponent;

export function escapeHtml(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;").replace(/'/g, "&#39;");
}

/** The HTML body of a sticky's Plane comment / work item. All user text is escaped. */
export function stickyHtml(row: Pick<StickyRow, "kind" | "title" | "text" | "created_at" | "done">, techName: string): string {
  const who = escapeHtml(techName);
  const head = row.kind === "draft" ? `✉️ Draft from ${who}` : `📝 Note from ${who}`;
  const paragraphs = row.text
    .replace(/\r\n?/g, "\n")
    .split(/\n[ \t]*\n/)
    .map((p) => p.trim())
    .filter(Boolean)
    .map((p) => `<p>${p.split("\n").map(escapeHtml).join("<br>")}</p>`);
  return [
    `<p><strong>${head}</strong></p>`,
    `<p><strong>${escapeHtml(row.title)}</strong></p>`,
    ...paragraphs,
    `<p><em>Saved ${escapeHtml(row.created_at)} from BobAI</em></p>`,
    ...(row.done === 1 ? ["<p>✅ Done</p>"] : []),
  ].join("");
}

/** Runs work after the response (waitUntil); without an ExecutionContext (direct app.fetch) it just runs. */
export function runInBackground(c: Context<AppEnv>, work: Promise<unknown>): void {
  const safe = work.catch((e) => console.error(`plane mirror failed: ${e instanceof Error ? e.name : "unknown"}`));
  try {
    c.executionCtx.waitUntil(safe);
  } catch {
    // No ExecutionContext: the promise is already running.
  }
}

/** Memoised Plane lookups for one mirror run (one technician). */
class Run {
  private tech?: Promise<{ name: string; email: string } | null>;
  private jobs?: Promise<JobRow[]>;
  private notes?: Promise<string>;
  private itemProjects = new Map<string, Promise<string | null>>();

  constructor(readonly env: Env, readonly cfg: PlaneConfig, readonly tenantId: string, readonly userId: string) {}

  techUser() {
    this.tech ??= this.env.DB.prepare("SELECT name, email FROM users WHERE id=? AND tenant_id=?")
      .bind(this.userId, this.tenantId).first<{ name: string; email: string }>();
    return this.tech;
  }

  /** Old stickies only carry a job title: find the technician's one Plane job with that title (or "title — site"). */
  async legacyJobId(jobTitle: string): Promise<string | null> {
    const u = await this.techUser();
    if (!u) return null;
    this.jobs ??= fetchPlaneJobs(this.cfg, u.email, { id: this.userId, name: u.name, tenantId: this.tenantId }).then((r) => r.jobs);
    const norm = (s: string) => s.trim().replace(/\s+/g, " ").toLowerCase();
    const want = norm(jobTitle);
    const ids = new Set(
      (await this.jobs)
        .filter((j) => norm(j.title) === want || (j.site && norm(`${j.title} — ${j.site}`) === want))
        .map((j) => j.id),
    );
    return ids.size === 1 ? [...ids][0] : null;
  }

  /** The project holding work item `item`, searched across the configured (or visible) projects. */
  itemProject(item: string): Promise<string | null> {
    let p = this.itemProjects.get(item);
    if (!p) {
      p = (async () => {
        const candidates = this.cfg.projectIds.length
          ? this.cfg.projectIds
          : (await planeList(this.cfg, "projects/")).map((x) => str(x.id)).filter(Boolean);
        const found = await Promise.all(candidates.map(async (pid) => {
          const r = await planeSend(this.cfg, "GET", `projects/${enc(pid)}/work-items/${enc(item)}/`);
          if (ok(r)) return pid;
          if (r.status === 404) return null;
          throw new MirrorError(`work item lookup status ${r.status}`);
        }));
        return found.find((x) => x) ?? null;
      })();
      this.itemProjects.set(item, p);
    }
    return p;
  }

  /** The NOTES project's id, creating "Field Notes" once if it does not exist. */
  notesProject(): Promise<string> {
    this.notes ??= (async () => {
      const find = async () =>
        str((await planeList(this.cfg, "projects/")).find((p) => str(p.identifier).toUpperCase() === NOTES_IDENTIFIER)?.id);
      const existing = await find();
      if (existing) return existing;
      const r = await planeSend(this.cfg, "POST", "projects/", { name: NOTES_NAME, identifier: NOTES_IDENTIFIER });
      if (ok(r) && isObj(r.data) && str(r.data.id)) return str(r.data.id);
      if (r.status === 409) {
        const again = await find();
        if (again) return again;
      }
      throw new MirrorError(`notes project create status ${r.status}`);
    })();
    this.notes.catch(() => { this.notes = undefined; });
    return this.notes;
  }
}

/**
 * Mirrors sticky `firstId` (if given), then up to 10 of the technician's other unsynced rows, oldest first.
 * No-op when Plane mode is off. Stops at the first failure so a frozen Plane does not eat the whole run.
 */
export async function mirrorStickies(env: Env, tenantId: string, userId: string, firstId?: string): Promise<void> {
  const cfg = planeConfig(env);
  if (!cfg) return;
  const run = new Run(env, cfg, tenantId, userId);
  if (firstId && !(await syncOne(run, firstId))) return;
  const { results } = await env.DB.prepare(
    `SELECT id FROM stickies WHERE tenant_id=? AND user_id=? AND plane_state IN ('pending','delete_pending') AND id<>?
      ORDER BY updated_at LIMIT ${RETRY_LIMIT}`,
  ).bind(tenantId, userId, firstId ?? "").all<{ id: string }>();
  for (const r of results) if (!(await syncOne(run, r.id))) return;
}

/** Claims the row (conditional UPDATE), mirrors it, releases it. Returns false when Plane failed. */
async function syncOne(run: Run, id: string): Promise<boolean> {
  const db = run.env.DB;
  const key = [run.tenantId, run.userId, id] as const;
  const now = Date.now();
  const claim = await db.prepare(
    `UPDATE stickies SET plane_claimed_at=? WHERE tenant_id=? AND user_id=? AND id=?
       AND plane_state IN ('pending','delete_pending') AND (plane_claimed_at IS NULL OR plane_claimed_at < ?)`,
  ).bind(new Date(now).toISOString(), ...key, new Date(now - LEASE_MS).toISOString()).run();
  if (claim.meta.changes !== 1) return true; // already synced, gone, or another mirror has it
  const started = Date.now();
  try {
    // A row edited or deleted while we worked stays pending; go round again (bounded).
    for (let pass = 0; pass < MAX_PASSES; pass++) {
      const row = await db.prepare("SELECT * FROM stickies WHERE tenant_id=? AND user_id=? AND id=?").bind(...key).first<StickyRow>();
      if (!row || row.plane_state === "synced") break;
      if (row.deleted === 1 || row.plane_state === "delete_pending") await syncDelete(run, row);
      else await syncUpsert(run, row);
    }
    console.log(`plane mirror sticky ${id} done in ${Date.now() - started} ms`);
    return true;
  } catch (e) {
    const why = e instanceof MirrorError ? e.message : e instanceof Error ? e.name : "unknown";
    console.error(`plane mirror sticky ${id} failed after ${Date.now() - started} ms: ${why}`);
    return false;
  } finally {
    await db.prepare("UPDATE stickies SET plane_claimed_at=NULL WHERE tenant_id=? AND user_id=? AND id=?").bind(...key).run();
  }
}

const commentPath = (ids: { project: string; item: string; comment: string }) =>
  `projects/${enc(ids.project)}/work-items/${enc(ids.item)}/comments/${enc(ids.comment)}/`;
const itemPath = (project: string, item: string) => `projects/${enc(project)}/work-items/${enc(item)}/`;

/** Deletes the Plane copy described by ids (404 = already gone). */
async function removeInPlane(cfg: PlaneConfig, ids: Ids): Promise<void> {
  if (!ids.project || !ids.item) return;
  const r = ids.comment
    ? await planeSend(cfg, "DELETE", commentPath({ project: ids.project, item: ids.item, comment: ids.comment }))
    : await planeSend(cfg, "DELETE", itemPath(ids.project, ids.item));
  if (!ok(r) && r.status !== 404) throw new MirrorError(`delete status ${r.status}`);
}

async function syncDelete(run: Run, row: StickyRow): Promise<void> {
  await removeInPlane(run.cfg, { project: row.plane_project_id, item: row.plane_item_id, comment: row.plane_comment_id });
  // Only if nobody revived or re-edited it meanwhile.
  await run.env.DB.prepare("DELETE FROM stickies WHERE tenant_id=? AND user_id=? AND id=? AND deleted=1 AND updated_at=?")
    .bind(row.tenant_id, row.user_id, row.id, row.updated_at).run();
}

async function setIds(run: Run, row: StickyRow, ids: Ids): Promise<boolean> {
  const r = await run.env.DB.prepare(
    "UPDATE stickies SET plane_project_id=?, plane_item_id=?, plane_comment_id=? WHERE tenant_id=? AND user_id=? AND id=?",
  ).bind(ids.project, ids.item, ids.comment, row.tenant_id, row.user_id, row.id).run();
  return r.meta.changes === 1;
}

/** Records what we just created; if the row vanished meanwhile, removes the new Plane copy again. */
async function storeCreated(run: Run, row: StickyRow, ids: Ids): Promise<void> {
  if (!(await setIds(run, row, ids))) await removeInPlane(run.cfg, ids);
}

async function markSynced(run: Run, row: StickyRow): Promise<void> {
  await run.env.DB.prepare(
    `UPDATE stickies SET plane_state='synced' WHERE tenant_id=? AND user_id=? AND id=?
       AND updated_at=? AND deleted=0 AND plane_state='pending'`,
  ).bind(row.tenant_id, row.user_id, row.id, row.updated_at).run();
}

/** POST with external_id; a 409 carrying an id means an earlier attempt already created it — adopt and PATCH. */
async function createOrAdopt(cfg: PlaneConfig, path: string, body: Obj, patchPath: (id: string) => string): Promise<string | null> {
  const r = await planeSend(cfg, "POST", path, body);
  if (ok(r) && isObj(r.data) && str(r.data.id)) return str(r.data.id);
  if (r.status === 409 && isObj(r.data) && str(r.data.id)) {
    const id = str(r.data.id);
    const { external_id: _x, external_source: _s, ...fields } = body;
    const p = await planeSend(cfg, "PATCH", patchPath(id), fields);
    if (!ok(p)) throw new MirrorError(`adopt patch status ${p.status}`);
    return id;
  }
  if (r.status === 404) return null;
  throw new MirrorError(`create status ${r.status}`);
}

async function syncUpsert(run: Run, row: StickyRow): Promise<void> {
  const { cfg, env } = run;
  const tech = await run.techUser();
  const html = stickyHtml(row, tech?.name ?? "a technician");
  const externalId = `sticky:${row.tenant_id}:${row.user_id}:${row.id}`;
  let ids: Ids = { project: row.plane_project_id, item: row.plane_item_id, comment: row.plane_comment_id };
  const clear = async () => {
    await setIds(run, row, { project: null, item: null, comment: null });
    ids = { project: null, item: null, comment: null };
  };

  let jobId = row.job_id;
  if (!jobId && row.job_title && !ids.item) {
    jobId = await run.legacyJobId(row.job_title);
    if (jobId) {
      await env.DB.prepare("UPDATE stickies SET job_id=? WHERE tenant_id=? AND user_id=? AND id=? AND job_id IS NULL")
        .bind(jobId, row.tenant_id, row.user_id, row.id).run();
    }
  }
  const wantItem = jobId?.startsWith(PLANE_ID_PREFIX) ? jobId.slice(PLANE_ID_PREFIX.length) : null;

  if (wantItem) {
    if (ids.comment && ids.project && ids.item === wantItem) {
      const r = await planeSend(cfg, "PATCH", commentPath({ project: ids.project, item: ids.item, comment: ids.comment }), { comment_html: html });
      if (ok(r)) return markSynced(run, row);
      if (r.status !== 404) throw new MirrorError(`comment patch status ${r.status}`);
      await clear();
    }
    const project = await run.itemProject(wantItem);
    if (project) {
      if (ids.item) {
        await removeInPlane(cfg, ids); // it lived somewhere else (other job, or Field Notes)
        await clear();
      }
      const commentId = await createOrAdopt(
        cfg,
        `projects/${enc(project)}/work-items/${enc(wantItem)}/comments/`,
        { comment_html: html, external_id: externalId, external_source: EXTERNAL_SOURCE },
        (id) => commentPath({ project, item: wantItem, comment: id }),
      );
      if (commentId) {
        await storeCreated(run, row, { project, item: wantItem, comment: commentId });
        return markSynced(run, row);
      }
      // 404: the work item vanished — fall back to Field Notes.
    }
  }

  if (ids.comment) {
    await removeInPlane(cfg, ids);
    await clear();
  }
  const fields = { name: row.title, description_html: html };
  if (ids.item && ids.project) {
    const r = await planeSend(cfg, "PATCH", itemPath(ids.project, ids.item), fields);
    if (ok(r)) return markSynced(run, row);
    if (r.status !== 404) throw new MirrorError(`work item patch status ${r.status}`);
    await clear();
  }
  const project = await run.notesProject();
  const itemId = await createOrAdopt(
    cfg,
    `projects/${enc(project)}/work-items/`,
    { ...fields, external_id: externalId, external_source: EXTERNAL_SOURCE },
    (id) => itemPath(project, id),
  );
  if (!itemId) throw new MirrorError("notes project not found");
  await storeCreated(run, row, { project, item: itemId, comment: null });
  return markSynced(run, row);
}
