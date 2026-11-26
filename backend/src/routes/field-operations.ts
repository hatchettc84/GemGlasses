import { Hono } from "hono";
import type { AppEnv } from "../env";
import { ApiError, readJson } from "../lib/errors";
import { guardedUpdate } from "../lib/audit";
import { nowIso } from "../lib/ids";
import { jobSelect } from "./operations";
import type { DocumentRow } from "./documents";
import { fetchPlaneJobs, planeConfig, PLANE_ID_PREFIX } from "../lib/plane";

const managedInPlane = () => new ApiError(409, "managed_in_plane", "This job is managed in Plane. Update it there.");
export const fieldOperations = new Hono<AppEnv>();
fieldOperations.get("/jobs", async (c) => {
  const d = c.get("device");
  const plane = planeConfig(c.env);
  if (plane) {
    const u = await c.env.DB.prepare("SELECT email, name FROM users WHERE id=? AND tenant_id=?")
      .bind(d.userId, d.tenantId).first<{ email: string; name: string }>();
    if (!u) return c.json({ jobs: [], tasks: [] });
    return c.json(await fetchPlaneJobs(plane, u.email, { id: d.userId, name: u.name, tenantId: d.tenantId }));
  }
  const [jobs, tasks] = await Promise.all([
    c.env.DB.prepare(
      `${jobSelect} WHERE j.tenant_id=? AND j.assigned_user_id=? ORDER BY j.updated_at DESC LIMIT 500`,
    )
      .bind(d.tenantId, d.userId)
      .all(),
    c.env.DB.prepare(
      "SELECT t.* FROM tasks t JOIN jobs j ON j.id=t.job_id WHERE t.tenant_id=? AND j.tenant_id=? AND j.assigned_user_id=? ORDER BY t.created_at LIMIT 5000",
    )
      .bind(d.tenantId, d.tenantId, d.userId)
      .all(),
  ]);
  return c.json({ jobs: jobs.results, tasks: tasks.results });
});
fieldOperations.patch("/tasks/:id", async (c) => {
  if (planeConfig(c.env) && c.req.param("id").startsWith(PLANE_ID_PREFIX)) throw managedInPlane();
  const d = c.get("device"),
    b = await readJson<Record<string, unknown>>(c),
    id = c.req.param("id");
  if (typeof b.completed !== "boolean" || !Number.isInteger(b.revision))
    throw new ApiError(400, "invalid_input", "Include completed and revision.");
  const applied = await guardedUpdate(
    c.env.DB,
    "tasks",
    "completed=?,revision=revision+1,updated_at=?",
    [b.completed ? 1 : 0, nowIso()],
    "id=? AND tenant_id=? AND revision=? AND job_id IN (SELECT id FROM jobs WHERE assigned_user_id=? AND tenant_id=?)",
    [id, d.tenantId, b.revision, d.userId, d.tenantId],
    { actor: { type: "device", id: d.id, tenantId: d.tenantId }, action: "task.updated", entity: "task", entityId: id, before: null, after: { completed: b.completed } },
  );
  if (!applied)
    throw new ApiError(
      409,
      "stale",
      "Task changed or is no longer assigned to you. Refresh and try again.",
    );
  return c.json({ ok: true });
});
fieldOperations.patch("/jobs/:id", async (c) => {
  if (planeConfig(c.env) && c.req.param("id").startsWith(PLANE_ID_PREFIX)) throw managedInPlane();
  const d = c.get("device"),
    b = await readJson<Record<string, unknown>>(c),
    id = c.req.param("id");
  if (
    !["open", "in_progress", "completed"].includes(String(b.status)) ||
    !Number.isInteger(b.revision)
  )
    throw new ApiError(
      400,
      "invalid_input",
      "Include a valid status and revision.",
    );
  const applied = await guardedUpdate(
    c.env.DB,
    "jobs",
    "status=?,revision=revision+1,updated_at=?",
    [b.status, nowIso()],
    "id=? AND tenant_id=? AND assigned_user_id=? AND revision=?",
    [id, d.tenantId, d.userId, b.revision],
    { actor: { type: "device", id: d.id, tenantId: d.tenantId }, action: "job.updated", entity: "job", entityId: id, before: null, after: { status: b.status } },
  );
  if (!applied)
    throw new ApiError(
      409,
      "stale",
      "Job changed or is no longer assigned to you.",
    );
  return c.json({ ok: true });
});
fieldOperations.get("/documents", async (c) => {
  const { results } = await c.env.DB.prepare(
    "SELECT id,title,revision,models_json,sha256,version FROM documents WHERE tenant_id=? AND status='approved' ORDER BY title",
  )
    .bind(c.get("device").tenantId)
    .all();
  return c.json({ documents: results });
});
fieldOperations.get("/documents/:id/file", async (c) => {
  const row = await c.env.DB.prepare(
    "SELECT * FROM documents WHERE tenant_id=? AND id=? AND status='approved'",
  )
    .bind(c.get("device").tenantId, c.req.param("id"))
    .first<DocumentRow>();
  if (!row)
    throw new ApiError(404, "not_found", "Approved document not found.");
  const obj = await c.env.DOCUMENTS.get(row.storage_key);
  if (!obj) throw new ApiError(404, "not_found", "File unavailable.");
  return new Response(obj.body, {
    headers: {
      "Content-Type":
        row.content_type === "text/plain"
          ? "text/plain; charset=utf-8"
          : row.content_type,
      "Content-Disposition": `attachment; filename="${row.filename}"`,
      "Cache-Control": "no-store",
      "X-Content-Type-Options": "nosniff",
    },
  });
});
// Authenticated, versioned source for the phone's offline knowledge adapter.
fieldOperations.get("/knowledge", async (c) => {
  const d = c.get("device");
  const { results } = await c.env.DB.prepare(
    "SELECT * FROM documents WHERE tenant_id=? AND status='approved' ORDER BY id",
  )
    .bind(d.tenantId)
    .all<DocumentRow>();
  return c.json({
    tenantId: d.tenantId,
    generatedAt: Date.now(),
    expiresAt: Date.now() + 24 * 60 * 60 * 1000,
    documents: results.map((r) => ({
      id: r.id,
      tenantId: r.tenant_id,
      title: r.title,
      family: r.title,
      revision: r.revision,
      applicableModels: JSON.parse(r.models_json),
      approvalStatus: "APPROVED",
      packId: "office-approved",
      pages: JSON.parse(r.pages_json),
      isMock: false,
    })),
  });
});
