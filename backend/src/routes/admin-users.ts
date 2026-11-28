import { Hono } from "hono";
import { isRole, type AppEnv, type Role } from "../env";
import { requireRole } from "../auth/admin";
import { ApiError, readJson } from "../lib/errors";
import { actorFromUser, auditStmt, auditStmtIfExists } from "../lib/audit";
import { nowIso, ulid } from "../lib/ids";
import { sha256Hex, sixDigitCode } from "../lib/crypto";

interface UserRow {
  id: string;
  tenant_id: string;
  email: string;
  name: string;
  role: Role;
  active: number;
  senior: number;
  created_at: string;
  updated_at: string;
}

export function userJson(r: UserRow) {
  return {
    id: r.id,
    email: r.email,
    name: r.name,
    role: r.role,
    active: r.active === 1,
    senior: r.senior === 1,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PAIRING_TTL_MS = 10 * 60_000;

export const adminUsers = new Hono<AppEnv>();

adminUsers.get("/me", (c) => {
  const u = c.get("user");
  return c.json({ id: u.id, email: u.email, name: u.name, role: u.role });
});

adminUsers.get("/users", requireRole("admin"), async (c) => {
  const { results } = await c.env.DB.prepare("SELECT * FROM users WHERE tenant_id = ? ORDER BY name")
    .bind(c.get("user").tenantId).all<UserRow>();
  return c.json({ users: results.map(userJson) });
});

adminUsers.post("/users", requireRole("admin"), async (c) => {
  const actor = c.get("user");
  const body = await readJson<{ email?: string; name?: string; role?: string }>(c);
  const email = body.email?.trim().toLowerCase() ?? "";
  const name = body.name?.trim() ?? "";
  if (!EMAIL.test(email)) throw new ApiError(400, "invalid_email", "Enter a valid email address.");
  if (!name) throw new ApiError(400, "invalid_name", "Enter the person's name.");
  if (!isRole(body.role)) throw new ApiError(400, "invalid_role", "Pick a role: admin, reviewer, dispatcher or technician.");

  const taken = await c.env.DB.prepare("SELECT id FROM users WHERE tenant_id = ? AND email = ?")
    .bind(actor.tenantId, email).first();
  if (taken) throw new ApiError(409, "email_taken", "Someone with that email is already in BobAI.");

  const now = nowIso();
  const row: UserRow = { id: ulid(), tenant_id: actor.tenantId, email, name, role: body.role, active: 1, senior: 0, created_at: now, updated_at: now };
  await c.env.DB.batch([
    c.env.DB.prepare(
      "INSERT INTO users (id, tenant_id, email, name, role, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
    ).bind(row.id, row.tenant_id, row.email, row.name, row.role, now, now),
    auditStmt(c.env.DB, actorFromUser(actor), "user.created", "user", row.id, null, userJson(row)),
  ]);
  return c.json({ user: userJson(row) }, 201);
});

adminUsers.patch("/users/:id", requireRole("admin"), async (c) => {
  const actor = c.get("user");
  const id = c.req.param("id");
  const body = await readJson<{ role?: string; active?: unknown; senior?: unknown; updatedAt?: string }>(c);
  const row = await c.env.DB.prepare("SELECT * FROM users WHERE tenant_id = ? AND id = ?")
    .bind(actor.tenantId, id).first<UserRow>();
  if (!row) throw new ApiError(404, "not_found", "That person was not found.");
  if (body.updatedAt !== row.updated_at) {
    throw new ApiError(409, "stale", "Someone changed this person since you loaded the page. Reload and try again.");
  }
  if (body.role !== undefined && !isRole(body.role)) {
    throw new ApiError(400, "invalid_role", "Pick a role: admin, reviewer, dispatcher or technician.");
  }
  if (body.active !== undefined && typeof body.active !== "boolean") {
    throw new ApiError(400, "invalid_active", "Active must be true or false.");
  }
  if (body.senior !== undefined && typeof body.senior !== "boolean") {
    throw new ApiError(400, "invalid_senior", "Senior must be true or false.");
  }
  const nextRole = (body.role as Role | undefined) ?? row.role;
  if (body.senior !== undefined && nextRole !== "technician") {
    throw new ApiError(400, "not_a_technician", "Only technicians can be senior technicians.");
  }
  if (id === actor.id && ((body.role !== undefined && body.role !== "admin") || body.active === false)) {
    throw new ApiError(400, "cannot_change_self", "You can't remove your own admin access.");
  }

  const next: UserRow = {
    ...row,
    role: nextRole,
    active: body.active === undefined ? row.active : body.active ? 1 : 0,
    // Seniority only means something for technicians, so it clears when the role changes away.
    senior: nextRole !== "technician" ? 0 : body.senior === undefined ? row.senior : body.senior ? 1 : 0,
    updated_at: nowIso(),
  };
  // The UPDATE only applies if nobody changed the row since we read it; the audit insert
  // only runs if the UPDATE's new updated_at is on the row. Same batch = same transaction.
  const [update] = await c.env.DB.batch([
    c.env.DB.prepare(
      "UPDATE users SET role = ?, active = ?, senior = ?, updated_at = ? WHERE id = ? AND tenant_id = ? AND updated_at = ?",
    ).bind(next.role, next.active, next.senior, next.updated_at, id, actor.tenantId, row.updated_at),
    auditStmtIfExists(
      c.env.DB,
      "SELECT 1 FROM users WHERE id = ? AND tenant_id = ? AND updated_at = ?",
      [id, actor.tenantId, next.updated_at],
      actorFromUser(actor), "user.updated", "user", id, userJson(row), userJson(next),
    ),
  ]);
  if (update.meta.changes === 0) {
    throw new ApiError(409, "stale", "Someone changed this person since you loaded the page. Reload and try again.");
  }
  return c.json({ user: userJson(next) });
});

adminUsers.post("/users/:id/pairing-code", requireRole("admin"), async (c) => {
  const actor = c.get("user");
  const id = c.req.param("id");
  const row = await c.env.DB.prepare("SELECT * FROM users WHERE tenant_id = ? AND id = ?")
    .bind(actor.tenantId, id).first<UserRow>();
  if (!row) throw new ApiError(404, "not_found", "That person was not found.");
  if (row.active !== 1 || row.role !== "technician") {
    throw new ApiError(400, "not_a_technician", "Pairing codes are only for active technicians.");
  }

  const now = new Date();
  const nowS = now.toISOString();
  // Avoid handing out a code that is already live for someone else.
  let code = sixDigitCode();
  let hash = await sha256Hex(code);
  const clashes = async () =>
    (await c.env.DB.prepare(
      "SELECT id FROM pairing_codes WHERE tenant_id = ? AND code_hash = ? AND used_at IS NULL AND expires_at > ?",
    ).bind(actor.tenantId, hash, nowS).first()) !== null;
  for (let i = 0; i < 5 && (await clashes()); i++) {
    code = sixDigitCode();
    hash = await sha256Hex(code);
  }
  if (await clashes()) {
    throw new ApiError(503, "code_unavailable", "Couldn't make a unique pairing code. Try again.");
  }
  const expiresAt = new Date(now.getTime() + PAIRING_TTL_MS).toISOString();

  await c.env.DB.batch([
    c.env.DB.prepare(
      "UPDATE pairing_codes SET used_at = ?, updated_at = ? WHERE tenant_id = ? AND user_id = ? AND used_at IS NULL",
    ).bind(nowS, nowS, actor.tenantId, id),
    c.env.DB.prepare(
      `INSERT INTO pairing_codes (id, tenant_id, user_id, code_hash, expires_at, used_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, NULL, ?, ?)`,
    ).bind(ulid(), actor.tenantId, id, hash, expiresAt, nowS, nowS),
    auditStmt(c.env.DB, actorFromUser(actor), "pairing_code.issued", "user", id, null, { expiresAt }),
  ]);
  return c.json({ code, expiresAt }, 201);
});
