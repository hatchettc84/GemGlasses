import type { MiddlewareHandler } from "hono";
import type { AppEnv, DeviceCtx } from "../env";
import { ApiError } from "../lib/errors";
import { sha256Hex } from "../lib/crypto";
import { nowIso } from "../lib/ids";

type DeviceCheck = { ok: true; device: DeviceCtx } | { ok: false; code: "device_unknown" | "device_revoked" };

export async function deviceFromBearer(req: Request, db: D1Database): Promise<DeviceCheck> {
  const header = req.headers.get("Authorization") ?? "";
  const key = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!key) return { ok: false, code: "device_unknown" };
  const row = await db.prepare(
    `SELECT d.id, d.tenant_id, d.user_id, d.revoked_at, u.active, u.role
       FROM devices d JOIN users u ON u.id = d.user_id
      WHERE d.key_hash = ?`,
  ).bind(await sha256Hex(key)).first<{ id: string; tenant_id: string; user_id: string; revoked_at: string | null; active: number; role: string }>();
  if (!row) return { ok: false, code: "device_unknown" };
  if (row.revoked_at || row.active !== 1 || row.role !== "technician") return { ok: false, code: "device_revoked" };
  await db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").bind(nowIso(), row.id).run();
  return { ok: true, device: { id: row.id, tenantId: row.tenant_id, userId: row.user_id } };
}

export const deviceAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const check = await deviceFromBearer(c.req.raw, c.env.DB);
  if (!check.ok) {
    throw check.code === "device_revoked"
      ? new ApiError(401, "device_revoked", "This phone was removed. Ask your admin to pair it again.")
      : new ApiError(401, "device_unknown", "This phone is not paired with BobAI. Ask your admin for a pairing code.");
  }
  c.set("device", check.device);
  await next();
};
