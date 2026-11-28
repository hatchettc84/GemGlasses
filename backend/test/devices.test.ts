import { env, fetchMock } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { api, auditActions, BOSS, seedUser } from "./helpers";
import { sha256Hex } from "../src/lib/crypto";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

async function issueCode(userId: string): Promise<string> {
  await api("/api/admin/me", { as: BOSS }); // bootstrap admin
  const res = await api(`/api/admin/users/${userId}/pairing-code`, { as: BOSS, method: "POST" });
  return (await res.json()).code;
}

const pair = (code: string, label = "SM-F966U") =>
  api("/api/device/pair", { method: "POST", body: JSON.stringify({ code, label }) });

describe("device pairing", () => {
  it("pairs with a valid code, stores only a hash, and authenticates the phone", async () => {
    const tech = await seedUser("tia@example.com", "technician", "Tia");
    const res = await pair(await issueCode(tech));
    expect(res.status).toBe(201);
    const body = await res.json();
    expect(body.user).toEqual({ name: "Tia", email: "tia@example.com" });
    expect(body.deviceKey).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const stored = await env.DB.prepare("SELECT key_hash FROM devices WHERE id = ?").bind(body.deviceId).first<{ key_hash: string }>();
    expect(stored?.key_hash).toBe(await sha256Hex(body.deviceKey));
    expect(await auditActions()).not.toContain("device.pair_failed");

    const me = await api("/api/device/me", { headers: { Authorization: `Bearer ${body.deviceKey}` } });
    expect(me.status).toBe(200);
    expect(await me.json()).toEqual({ deviceId: body.deviceId, user: { name: "Tia", email: "tia@example.com" }, senior: false });
    expect(await auditActions()).toContain("device.paired");
  });

  it("refuses a code twice", async () => {
    const code = await issueCode(await seedUser("t@example.com", "technician"));
    expect((await pair(code)).status).toBe(201);
    const again = await pair(code);
    expect(again.status).toBe(400);
    expect((await again.json()).error).toBe("invalid_code");
  });

  it("refuses expired codes", async () => {
    const code = await issueCode(await seedUser("t@example.com", "technician"));
    await env.DB.prepare("UPDATE pairing_codes SET expires_at = '2000-01-01T00:00:00.000Z'").run();
    expect((await (await pair(code)).json()).error).toBe("invalid_code");
  });

  it("logs wrong codes and stops after 20 in 10 minutes", async () => {
    const wrong = await pair("000000");
    expect(wrong.status).toBe(400);
    expect(await auditActions()).toEqual(["device.pair_failed"]);
    for (let i = 0; i < 19; i++) await pair("000000");
    const blocked = await pair("123456");
    expect(blocked.status).toBe(429);
    expect((await blocked.json()).error).toBe("too_many_attempts");
  });

  it("refused attempts leave no rows, so the lockout ends with the window", async () => {
    for (let i = 0; i < 20; i++) expect((await pair("000000")).status).toBe(400);
    for (let i = 0; i < 5; i++) expect((await pair("000000")).status).toBe(429);
    expect((await auditActions()).filter((a) => a === "device.pair_failed")).toHaveLength(20);
    const row = await env.DB.prepare("SELECT entity FROM audit_log WHERE action = 'device.pair_failed' LIMIT 1").first<{ entity: string }>();
    expect(row?.entity).toBe("pair_attempt");
  });

  it("treats a null or array JSON body as an empty one", async () => {
    for (const body of ["null", "[]", "7"]) {
      const res = await api("/api/device/pair", { method: "POST", body });
      expect(res.status).toBe(400);
      expect((await res.json()).error).toBe("invalid_code");
    }
    expect((await auditActions()).filter((a) => a === "device.pair_failed")).toHaveLength(3);
  });

  it("holds the failure limit under concurrency", async () => {
    const results = await Promise.all(Array.from({ length: 25 }, () => pair("000000")));
    const statuses = results.map((r) => r.status);
    expect(statuses.filter((s) => s === 400).length).toBeLessThanOrEqual(20);
    expect(statuses.filter((s) => s === 429).length).toBeGreaterThanOrEqual(5);
    expect(statuses.every((s) => s === 400 || s === 429)).toBe(true);
    // Refused attempts compare no code and delete their own row, so a burst may
    // leave fewer than 20 rows; every compared (400) guess stays recorded.
    const recorded = (await auditActions()).filter((a) => a === "device.pair_failed").length;
    expect(recorded).toBe(statuses.filter((s) => s === 400).length);
  });

  it("claims a code exactly once under concurrency", async () => {
    const code = await issueCode(await seedUser("t@example.com", "technician"));
    const results = await Promise.all([pair(code), pair(code)]);
    expect(results.map((r) => r.status).sort()).toEqual([201, 400]);
    const devices = await env.DB.prepare("SELECT COUNT(*) AS n FROM devices").first<{ n: number }>();
    expect(devices?.n).toBe(1);
    const actions = await auditActions();
    expect(actions.filter((a) => a === "device.paired")).toHaveLength(1);
    expect(actions.filter((a) => a === "device.pair_failed")).toHaveLength(1); // the loser only
  });

  it("answers non-string code or label without a 500", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const numeric = await api("/api/device/pair", { method: "POST", body: JSON.stringify({ code: 123456, label: "x" }) });
    expect(numeric.status).toBe(400);
    expect((await numeric.json()).error).toBe("invalid_code");
    const res = await api("/api/device/pair", { method: "POST", body: JSON.stringify({ code: await issueCode(tech), label: 42 }) });
    expect(res.status).toBe(201);
    const row = await env.DB.prepare("SELECT label FROM devices").first<{ label: string }>();
    expect(row?.label).toBe("Phone");
  });

  it("treats a phone whose user is no longer a technician as revoked", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const { deviceKey } = await (await pair(await issueCode(tech))).json();
    await env.DB.prepare("UPDATE users SET role = 'dispatcher' WHERE id = ?").bind(tech).run();
    const me = await api("/api/device/me", { headers: { Authorization: `Bearer ${deviceKey}` } });
    expect((await me.json()).error).toBe("device_revoked");
  });

  it("revokes idempotently with a single audit row", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const { deviceId } = await (await pair(await issueCode(tech))).json();
    const url = `/api/admin/devices/${deviceId}/revoke`;
    const first = await api(url, { as: BOSS, method: "POST" });
    const second = await api(url, { as: BOSS, method: "POST" });
    expect(first.status).toBe(200);
    expect(second.status).toBe(200);
    expect((await second.json()).device.revokedAt).toBe((await first.json()).device.revokedAt);
    const both = await Promise.all([api(url, { as: BOSS, method: "POST" }), api(url, { as: BOSS, method: "POST" })]);
    expect(both.map((r) => r.status)).toEqual([200, 200]);
    expect((await auditActions()).filter((a) => a === "device.revoked")).toHaveLength(1);
  });

  it("refuses unknown keys and revoked phones", async () => {
    const unknown = await api("/api/device/me", { headers: { Authorization: "Bearer nope" } });
    expect(unknown.status).toBe(401);
    expect(await unknown.json()).toEqual({
      error: "device_unknown",
      message: "This phone is not paired with BobAI. Ask your admin for a pairing code.",
    });

    const tech = await seedUser("t@example.com", "technician");
    const { deviceKey, deviceId } = await (await pair(await issueCode(tech))).json();
    const list = await (await api("/api/admin/devices", { as: BOSS })).json();
    expect(list.devices).toHaveLength(1);
    expect(list.devices[0]).toMatchObject({ id: deviceId, label: "SM-F966U", revokedAt: null });

    const revoke = await api(`/api/admin/devices/${deviceId}/revoke`, { as: BOSS, method: "POST" });
    expect(revoke.status).toBe(200);
    expect((await revoke.json()).device.revokedAt).not.toBeNull();

    const me = await api("/api/device/me", { headers: { Authorization: `Bearer ${deviceKey}` } });
    expect(me.status).toBe(401);
    expect(await me.json()).toEqual({ error: "device_revoked", message: "This phone was removed. Ask your admin to pair it again." });
    expect(await auditActions()).toContain("device.revoked");
  });

  it("treats a deactivated technician's phone as revoked", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const { deviceKey } = await (await pair(await issueCode(tech))).json();
    await env.DB.prepare("UPDATE users SET active = 0 WHERE id = ?").bind(tech).run();
    const me = await api("/api/device/me", { headers: { Authorization: `Bearer ${deviceKey}` } });
    expect((await me.json()).error).toBe("device_revoked");
  });

  it("only admins manage devices", async () => {
    await seedUser("disp@example.com", "dispatcher");
    expect((await api("/api/admin/devices", { as: "disp@example.com" })).status).toBe(403);
  });

  it("lets a paired phone mint a Gemini token", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const { deviceKey } = await (await pair(await issueCode(tech))).json();
    fetchMock.get("https://generativelanguage.googleapis.com")
      .intercept({ path: /\/v1beta\/auth_tokens/, method: "POST" })
      .reply(200, { name: "auth_tokens/xyz", expireTime: "2026-11-20T12:00:00Z" });
    const res = await api("/token", { method: "POST", headers: { Authorization: `Bearer ${deviceKey}` } });
    expect(res.status).toBe(200);
    expect((await res.json()).token).toBe("auth_tokens/xyz");
  });
});
