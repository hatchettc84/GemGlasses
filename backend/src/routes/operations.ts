import { Hono } from "hono";
import type { AppEnv } from "../env";
import { requireRole } from "../auth/admin";
import { ApiError, readJson } from "../lib/errors";
import { actorFromUser, auditStmt, guardedUpdate } from "../lib/audit";
import { nowIso, ulid } from "../lib/ids";

export const operations = new Hono<AppEnv>();
export function text(value: unknown, field: string, max = 200): string {
  if (typeof value !== "string" || !value.trim() || value.length > max)
    throw new ApiError(
      400,
      "invalid_input",
      `Enter a valid ${field} (up to ${max} characters).`,
    );
  return value.trim();
}
export async function assignee(
  db: D1Database,
  tenant: string,
  id: unknown,
): Promise<string | null> {
  if (id === null || id === undefined || id === "") return null;
  if (
    typeof id !== "string" ||
    !(await db
      .prepare(
        "SELECT id FROM users WHERE tenant_id = ? AND id = ? AND active = 1 AND role = 'technician'",
      )
      .bind(tenant, id)
      .first())
  )
    throw new ApiError(400, "invalid_assignee", "Choose an active technician.");
  return id;
}
export const jobSelect = `SELECT j.*, u.name AS assigned_name FROM jobs j LEFT JOIN users u ON u.id = j.assigned_user_id`;
operations.get("/team", requireRole("admin", "dispatcher"), async (c) => {
  const { results } = await c.env.DB.prepare(
    "SELECT id,name,email FROM users WHERE tenant_id = ? AND active = 1 AND role = 'technician' ORDER BY name",
  )
    .bind(c.get("user").tenantId)
    .all();
  return c.json({ technicians: results });
});
operations.get(
  "/jobs",
  requireRole("admin", "dispatcher", "reviewer"),
  async (c) => {
    const tenant = c.get("user").tenantId;
    const [jobs, tasks] = await Promise.all([
      c.env.DB.prepare(
        `${jobSelect} WHERE j.tenant_id = ? ORDER BY j.updated_at DESC LIMIT 500`,
      )
        .bind(tenant)
        .all(),
      c.env.DB.prepare(
        "SELECT * FROM tasks WHERE tenant_id = ? ORDER BY created_at LIMIT 5000",
      )
        .bind(tenant)
        .all(),
    ]);
    return c.json({ jobs: jobs.results, tasks: tasks.results });
  },
);
operations.post("/jobs", requireRole("admin", "dispatcher"), async (c) => {
  const u = c.get("user"),
    b = await readJson<Record<string, unknown>>(c),
    title = text(b.title, "job title"),
    site = text(b.site, "site");
  const description =
    b.description == null || b.description === ""
      ? ""
      : text(b.description, "description", 5000);
  const assigned = await assignee(c.env.DB, u.tenantId, b.assignedUserId);
  const due = b.dueAt ? new Date(text(b.dueAt, "due date")) : null;
  if (due && Number.isNaN(due.getTime()))
    throw new ApiError(400, "invalid_date", "Enter a valid due date.");
  const id = ulid(),
    now = nowIso();
  await c.env.DB.batch([
    c.env.DB.prepare(
      "INSERT INTO jobs (id,tenant_id,title,site,description,assigned_user_id,due_at,created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
    ).bind(
      id,
      u.tenantId,
      title,
      site,
      description,
      assigned,
      due?.toISOString() ?? null,
      u.id,
      now,
      now,
    ),
    auditStmt(c.env.DB, actorFromUser(u), "job.created", "job", id, null, {
      title,
      assigned,
    }),
  ]);
  return c.json(
    {
      job: await c.env.DB.prepare(`${jobSelect} WHERE j.id=?`).bind(id).first(),
    },
    201,
  );
});
operations.patch("/jobs/:id", requireRole("admin", "dispatcher"), async (c) => {
  const u = c.get("user"),
    b = await readJson<Record<string, unknown>>(c),
    id = c.req.param("id");
  const row = await c.env.DB.prepare(
    "SELECT * FROM jobs WHERE tenant_id=? AND id=?",
  )
    .bind(u.tenantId, id)
    .first<{
      assigned_user_id: string | null;
      status: string;
      revision: number;
    }>();
  if (!row) throw new ApiError(404, "not_found", "Job not found.");
  if (b.revision !== row.revision)
    throw new ApiError(
      409,
      "stale",
      "This job changed. Refresh before saving.",
    );
  const assigned =
    b.assignedUserId === undefined
      ? row.assigned_user_id
      : await assignee(c.env.DB, u.tenantId, b.assignedUserId);
  const status = b.status ?? row.status;
  if (!["open", "in_progress", "completed"].includes(String(status)))
    throw new ApiError(400, "invalid_status", "Choose a valid job status.");
  const applied = await guardedUpdate(
    c.env.DB,
    "jobs",
    "assigned_user_id=?,status=?,revision=revision+1,updated_at=?",
    [assigned, status, nowIso()],
    "tenant_id=? AND id=? AND revision=?",
    [u.tenantId, id, b.revision],
    { actor: actorFromUser(u), action: "job.updated", entity: "job", entityId: id, before: row, after: { assigned, status } },
  );
  if (!applied)
    throw new ApiError(
      409,
      "stale",
      "This job changed. Refresh before saving.",
    );
  return c.json({
    job: await c.env.DB.prepare(`${jobSelect} WHERE j.id=?`).bind(id).first(),
  });
});
operations.post(
  "/jobs/:id/tasks",
  requireRole("admin", "dispatcher"),
  async (c) => {
    const u = c.get("user"),
      b = await readJson<Record<string, unknown>>(c),
      job = c.req.param("id"),
      title = text(b.title, "task title", 500);
    if (
      !(await c.env.DB.prepare("SELECT id FROM jobs WHERE tenant_id=? AND id=?")
        .bind(u.tenantId, job)
        .first())
    )
      throw new ApiError(404, "not_found", "Job not found.");
    const id = ulid(),
      now = nowIso();
    await c.env.DB.batch([
      c.env.DB.prepare(
        "INSERT INTO tasks (id,tenant_id,job_id,title,created_at,updated_at) VALUES (?,?,?,?,?,?)",
      ).bind(id, u.tenantId, job, title, now, now),
      auditStmt(c.env.DB, actorFromUser(u), "task.created", "task", id, null, {
        job,
        title,
      }),
    ]);
    return c.json({ id }, 201);
  },
);
operations.patch(
  "/tasks/:id",
  requireRole("admin", "dispatcher"),
  async (c) => {
    const u = c.get("user"),
      b = await readJson<Record<string, unknown>>(c),
      id = c.req.param("id");
    if (typeof b.completed !== "boolean" || !Number.isInteger(b.revision))
      throw new ApiError(
        400,
        "invalid_input",
        "Include completed and revision.",
      );
    const applied = await guardedUpdate(
      c.env.DB,
      "tasks",
      "completed=?,revision=revision+1,updated_at=?",
      [b.completed ? 1 : 0, nowIso()],
      "tenant_id=? AND id=? AND revision=?",
      [u.tenantId, id, b.revision],
      { actor: actorFromUser(u), action: "task.updated", entity: "task", entityId: id, before: null, after: { completed: b.completed } },
    );
    if (!applied)
      throw new ApiError(
        409,
        "stale",
        "Task changed or is unavailable. Refresh and try again.",
      );
    return c.json({ ok: true });
  },
);
operations.get("/audit", requireRole("admin", "reviewer"), async (c) => {
  const { results } = await c.env.DB.prepare(
    "SELECT id,actor_type,actor_id,action,entity,entity_id,at FROM audit_log WHERE tenant_id=? ORDER BY at DESC LIMIT 200",
  )
    .bind(c.get("user").tenantId)
    .all();
  return c.json({ events: results });
});
