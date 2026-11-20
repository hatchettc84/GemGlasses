import { Hono } from "hono";
import { TENANT_ID, type AppEnv } from "../env";
import { deviceAuth } from "../auth/device";
import { ApiError, readJson } from "../lib/errors";
import { auditStmt, SYSTEM_ACTOR } from "../lib/audit";
import { nowIso, ulid } from "../lib/ids";
import { randomKey, sha256Hex } from "../lib/crypto";

const FAILURE_WINDOW_MS = 10 * 60_000;
const MAX_FAILURES = 20;

const invalidCode = () =>
  new ApiError(400, "invalid_code", "That code is wrong or expired. Ask your admin for a new one.");

export const deviceApi = new Hono<AppEnv>();

// Registered before the auth middleware: pairing is how a phone gets a key.
deviceApi.post("/pair", async (c) => {
  const db = c.env.DB;
  const body = await readJson<{ code?: string; label?: string }>(c);
  const code = (body.code ?? "").replace(/\s/g, "");
  const label = (body.label ?? "").trim().slice(0, 80) || "Phone";

  const since = new Date(Date.now() - FAILURE_WINDOW_MS).toISOString();
  const failures = await db.prepare(
    "SELECT COUNT(*) AS n FROM audit_log WHERE tenant_id = ? AND action = 'device.pair_failed' AND at > ?",
  ).bind(TENANT_ID, since).first<{ n: number }>();
  if ((failures?.n ?? 0) >= MAX_FAILURES) {
    throw new ApiError(429, "too_many_attempts", "Too many wrong codes. Wait 10 minutes and try again.");
  }

  const now = nowIso();
  const match = /^\d{6}$/.test(code)
    ? await db.prepare(
        `SELECT p.id, p.user_id, u.name, u.email
           FROM pairing_codes p JOIN users u ON u.id = p.user_id
          WHERE p.tenant_id = ? AND p.code_hash = ? AND p.used_at IS NULL AND p.expires_at > ?
            AND u.active = 1 AND u.role = 'technician'`,
      ).bind(TENANT_ID, await sha256Hex(code), now).first<{ id: string; user_id: string; name: string; email: string }>()
    : null;
  if (!match) {
    await auditStmt(db, SYSTEM_ACTOR, "device.pair_failed", "pairing_code", null, null, null).run();
    throw invalidCode();
  }

  const claimed = await db.prepare("UPDATE pairing_codes SET used_at = ?, updated_at = ? WHERE id = ? AND used_at IS NULL")
    .bind(now, now, match.id).run();
  if (claimed.meta.changes !== 1) throw invalidCode();

  const deviceKey = randomKey();
  const deviceId = ulid();
  await db.batch([
    db.prepare(
      `INSERT INTO devices (id, tenant_id, user_id, label, key_hash, paired_at, last_seen_at, revoked_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)`,
    ).bind(deviceId, TENANT_ID, match.user_id, label, await sha256Hex(deviceKey), now, now, now, now),
    auditStmt(db, { type: "device", id: deviceId, tenantId: TENANT_ID }, "device.paired", "device", deviceId, null, {
      label,
      userId: match.user_id,
    }),
  ]);
  return c.json({ deviceKey, deviceId, user: { name: match.name, email: match.email } }, 201);
});

deviceApi.use("*", deviceAuth);

deviceApi.get("/me", async (c) => {
  const device = c.get("device");
  const user = await c.env.DB.prepare("SELECT name, email FROM users WHERE id = ?")
    .bind(device.userId).first<{ name: string; email: string }>();
  return c.json({ deviceId: device.id, user });
});
