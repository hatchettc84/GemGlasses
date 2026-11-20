import { Hono } from "hono";
import type { AppEnv } from "../env";
import { requireRole } from "../auth/admin";
import { ApiError } from "../lib/errors";
import { actorFromUser, auditStmtIfExists } from "../lib/audit";
import { nowIso } from "../lib/ids";

interface DeviceRow {
  id: string;
  user_id: string;
  label: string;
  paired_at: string;
  last_seen_at: string | null;
  revoked_at: string | null;
  user_name: string;
  user_email: string;
}

const SELECT_DEVICES = `
  SELECT d.id, d.user_id, d.label, d.paired_at, d.last_seen_at, d.revoked_at,
         u.name AS user_name, u.email AS user_email
    FROM devices d JOIN users u ON u.id = d.user_id
   WHERE d.tenant_id = ?`;

function deviceJson(r: DeviceRow) {
  return {
    id: r.id,
    label: r.label,
    pairedAt: r.paired_at,
    lastSeenAt: r.last_seen_at,
    revokedAt: r.revoked_at,
    user: { id: r.user_id, name: r.user_name, email: r.user_email },
  };
}

export const adminDevices = new Hono<AppEnv>();

adminDevices.get("/devices", requireRole("admin"), async (c) => {
  const { results } = await c.env.DB.prepare(`${SELECT_DEVICES} ORDER BY d.paired_at DESC`)
    .bind(c.get("user").tenantId).all<DeviceRow>();
  return c.json({ devices: results.map(deviceJson) });
});

adminDevices.post("/devices/:id/revoke", requireRole("admin"), async (c) => {
  const actor = c.get("user");
  const id = c.req.param("id");
  const row = await c.env.DB.prepare(`${SELECT_DEVICES} AND d.id = ?`).bind(actor.tenantId, id).first<DeviceRow>();
  if (!row) throw new ApiError(404, "not_found", "That phone was not found.");
  if (row.revoked_at) return c.json({ device: deviceJson(row) });

  const now = nowIso();
  const next: DeviceRow = { ...row, revoked_at: now };
  const db = c.env.DB;
  const [updated] = await db.batch([
    db.prepare("UPDATE devices SET revoked_at = ?, updated_at = ? WHERE id = ? AND tenant_id = ? AND revoked_at IS NULL")
      .bind(now, now, id, actor.tenantId),
    auditStmtIfExists(
      db, "SELECT 1 WHERE changes() = 1", [], // true only if the UPDATE above changed a row
      actorFromUser(actor), "device.revoked", "device", id, deviceJson(row), deviceJson(next),
    ),
  ]);
  if (updated.meta.changes !== 1) {
    // Someone else revoked it first: report the current state, audit nothing.
    const current = await db.prepare(`${SELECT_DEVICES} AND d.id = ?`).bind(actor.tenantId, id).first<DeviceRow>();
    return c.json({ device: deviceJson(current ?? next) });
  }
  return c.json({ device: deviceJson(next) });
});
