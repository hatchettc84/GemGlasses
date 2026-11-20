import { env, fetchMock } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { api, auditActions, BOSS, seedUser } from "./helpers";

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
    expect(stored?.key_hash).not.toBe(body.deviceKey);

    const me = await api("/api/device/me", { headers: { Authorization: `Bearer ${body.deviceKey}` } });
    expect(me.status).toBe(200);
    expect(await me.json()).toEqual({ deviceId: body.deviceId, user: { name: "Tia", email: "tia@example.com" } });
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

  it("refuses unknown keys and revoked phones", async () => {
    const unknown = await api("/api/device/me", { headers: { Authorization: "Bearer nope" } });
    expect(unknown.status).toBe(401);
    expect((await unknown.json()).error).toBe("device_unknown");

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
