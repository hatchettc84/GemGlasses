import { Hono } from "hono";
import type { AppEnv } from "../env";
import { requireRole } from "../auth/admin";
import { ApiError, readJson } from "../lib/errors";
import { nowIso } from "../lib/ids";
import { mirrorStickies, runInBackground } from "../lib/plane-notes";

const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;
const MAX_TITLE = 80;
const MAX_TEXT = 2000;
const MAX_JOB_TITLE = 200;
const MAX_JOB_ID = 100;
const MAX_PER_TECH = 500;

const invalid = (msg: string) => new ApiError(400, "invalid_input", msg);

/** Phone-side routes: a technician writes only their own stickies. Never log title or text. */
export const deviceStickies = new Hono<AppEnv>();

deviceStickies.put("/stickies/:id", async (c) => {
  const d = c.get("device");
  const id = c.req.param("id");
  if (!ID_RE.test(id)) throw invalid("Invalid sticky id.");
  const raw = await readJson<unknown>(c);
  if (raw === null || typeof raw !== "object" || Array.isArray(raw)) throw invalid("Send a JSON object.");
  const b = raw as Record<string, unknown>;
  if (b.kind !== "note" && b.kind !== "draft") throw invalid("kind must be note or draft.");
  if (typeof b.title !== "string" || !b.title.trim() || b.title.length > MAX_TITLE) throw invalid("Invalid title.");
  if (typeof b.text !== "string" || !b.text.trim() || b.text.length > MAX_TEXT) throw invalid("Invalid text.");
  let jobTitle: string | null = null;
  if (b.jobTitle !== undefined && b.jobTitle !== null) {
    if (typeof b.jobTitle !== "string" || b.jobTitle.length > MAX_JOB_TITLE) throw invalid("Invalid jobTitle.");
    jobTitle = b.jobTitle.trim() || null;
  }
  let jobId: string | null = null;
  if (b.jobId !== undefined && b.jobId !== null) {
    if (typeof b.jobId !== "string" || b.jobId.length > MAX_JOB_ID) throw invalid("Invalid jobId.");
    jobId = b.jobId.trim() || null;
  }
  if (typeof b.createdAt !== "number" || !Number.isInteger(b.createdAt) || b.createdAt <= 0) throw invalid("Invalid createdAt.");
  const createdAt = new Date(b.createdAt);
  if (Number.isNaN(createdAt.getTime())) throw invalid("Invalid createdAt.");
  if (b.done !== undefined && typeof b.done !== "boolean") throw invalid("done must be true or false.");

  const db = c.env.DB;
  const existing = await db.prepare("SELECT 1 AS x FROM stickies WHERE tenant_id=? AND user_id=? AND id=? AND deleted=0")
    .bind(d.tenantId, d.userId, id).first();
  if (!existing) {
    const count = await db.prepare("SELECT COUNT(*) AS n FROM stickies WHERE tenant_id=? AND user_id=? AND deleted=0")
      .bind(d.tenantId, d.userId).first<{ n: number }>();
    if ((count?.n ?? 0) >= MAX_PER_TECH) throw new ApiError(409, "too many stickies", "Too many stickies.");
  }
  // Every write re-arms the Plane mirror (and revives a tombstone). A null jobId keeps a job the
  // mirror resolved from the same job title for an older phone's sticky.
  await db.prepare(
    `INSERT INTO stickies (tenant_id,user_id,id,device_id,kind,title,text,job_title,job_id,created_at,done,updated_at,plane_state,deleted)
     VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'pending',0)
     ON CONFLICT(tenant_id,user_id,id) DO UPDATE SET device_id=excluded.device_id, kind=excluded.kind,
       title=excluded.title, text=excluded.text,
       job_id=CASE WHEN excluded.job_id IS NULL AND stickies.job_title IS excluded.job_title THEN stickies.job_id ELSE excluded.job_id END,
       job_title=excluded.job_title, created_at=excluded.created_at,
       done=excluded.done, updated_at=excluded.updated_at, plane_state='pending', deleted=0`,
  ).bind(d.tenantId, d.userId, id, d.id, b.kind, b.title.trim(), b.text.trim(), jobTitle, jobId,
    createdAt.toISOString(), b.done === true ? 1 : 0, nowIso()).run();
  runInBackground(c, mirrorStickies(c.env, d.tenantId, d.userId, id));
  return c.json({ ok: true });
});

deviceStickies.delete("/stickies/:id", async (c) => {
  const d = c.get("device");
  const key = [d.tenantId, d.userId, c.req.param("id")] as const;
  // One transaction: a row with a Plane copy becomes a tombstone until Plane confirms; others go now.
  await c.env.DB.batch([
    c.env.DB.prepare(
      `UPDATE stickies SET deleted=1, plane_state='delete_pending', updated_at=?
        WHERE tenant_id=? AND user_id=? AND id=? AND plane_item_id IS NOT NULL`,
    ).bind(nowIso(), ...key),
    c.env.DB.prepare("DELETE FROM stickies WHERE tenant_id=? AND user_id=? AND id=? AND plane_item_id IS NULL").bind(...key),
  ]);
  runInBackground(c, mirrorStickies(c.env, d.tenantId, d.userId, key[2]));
  return c.body(null, 204);
});

export const adminStickies = new Hono<AppEnv>();

adminStickies.get("/stickies", requireRole("admin", "dispatcher", "reviewer"), async (c) => {
  const kind = c.req.query("kind");
  if (kind !== undefined && kind !== "note" && kind !== "draft") throw invalid("kind must be note or draft.");
  const tenant = c.get("user").tenantId;
  const sql = `SELECT s.id, s.kind, s.title, s.text, s.job_title, s.created_at, s.done, s.updated_at, s.user_id, u.name AS tech_name
       FROM stickies s JOIN users u ON u.id = s.user_id AND u.tenant_id = s.tenant_id
      WHERE s.tenant_id = ? AND s.deleted = 0${kind ? " AND s.kind = ?" : ""} ORDER BY s.created_at DESC LIMIT 500`;
  const stmt = c.env.DB.prepare(sql);
  const { results } = await (kind ? stmt.bind(tenant, kind) : stmt.bind(tenant)).all<{
    id: string; kind: string; title: string; text: string; job_title: string | null; created_at: string;
    done: number; updated_at: string; user_id: string; tech_name: string;
  }>();
  return c.json({
    stickies: results.map((r) => ({
      id: r.id, kind: r.kind, title: r.title, text: r.text, jobTitle: r.job_title, createdAt: r.created_at,
      done: r.done === 1, updatedAt: r.updated_at, technician: { id: r.user_id, name: r.tech_name },
    })),
  });
});
