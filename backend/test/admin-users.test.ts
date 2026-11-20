import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { identityEmail } from "../src/auth/admin";
import { api, auditActions, BOSS, seedUser } from "./helpers";

const json = (body: unknown) => JSON.stringify(body);

describe("admin identity", () => {
  it("refuses requests with no identity", async () => {
    const res = await api("/api/admin/me");
    expect(res.status).toBe(401);
    expect((await res.json()).error).toBe("unauthenticated");
  });

  it("refuses people who were not added", async () => {
    const res = await api("/api/admin/me", { as: "stranger@example.com" });
    expect(res.status).toBe(403);
    expect((await res.json()).error).toBe("not_a_user");
  });

  it("creates the bootstrap admin on first sign-in, once", async () => {
    const res = await api("/api/admin/me", { as: BOSS });
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ email: BOSS, role: "admin" });
    await api("/api/admin/me", { as: BOSS });
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first<{ n: number }>();
    expect(n?.n).toBe(1);
    expect(await auditActions()).toEqual(["user.bootstrapped"]);
  });

  it("does not bootstrap once an admin exists", async () => {
    await seedUser("first@example.com", "admin");
    const res = await api("/api/admin/me", { as: BOSS });
    expect(res.status).toBe(403);
  });

  it("ignores the dev header outside dev", async () => {
    const req = new Request("https://x/", { headers: { "X-Dev-User-Email": BOSS } });
    expect(await identityEmail(req, { ...env, ENVIRONMENT: undefined })).toBeNull();
    expect(await identityEmail(req, { ...env, ENVIRONMENT: "production" })).toBeNull();
    expect(await identityEmail(req, env)).toBe(BOSS);
  });

  it("refuses deactivated people", async () => {
    const id = await seedUser("gone@example.com", "admin");
    await env.DB.prepare("UPDATE users SET active = 0 WHERE id = ?").bind(id).run();
    expect((await api("/api/admin/me", { as: "gone@example.com" })).status).toBe(403);
  });
});

describe("people API", () => {
  it("only admins can list people", async () => {
    await seedUser("rev@example.com", "reviewer");
    const res = await api("/api/admin/users", { as: "rev@example.com" });
    expect(res.status).toBe(403);
    expect((await res.json()).error).toBe("forbidden");
  });

  it("adds a person, lowercases the email, and audits it", async () => {
    const res = await api("/api/admin/users", {
      as: BOSS, method: "POST", body: json({ email: " Tech@Example.com ", name: "Tia Tech", role: "technician" }),
    });
    expect(res.status).toBe(201);
    const { user } = await res.json();
    expect(user).toMatchObject({ email: "tech@example.com", name: "Tia Tech", role: "technician", active: true });
    const list = await (await api("/api/admin/users", { as: BOSS })).json();
    expect(list.users.map((u: { email: string }) => u.email).sort()).toEqual([BOSS, "tech@example.com"]);
    expect(await auditActions()).toEqual(["user.bootstrapped", "user.created"]);
  });

  it("validates new people", async () => {
    const post = (body: unknown) => api("/api/admin/users", { as: BOSS, method: "POST", body: json(body) });
    expect((await (await post({ email: "bad", name: "X", role: "admin" })).json()).error).toBe("invalid_email");
    expect((await (await post({ email: "a@b.co", name: " ", role: "admin" })).json()).error).toBe("invalid_name");
    expect((await (await post({ email: "a@b.co", name: "A", role: "owner" })).json()).error).toBe("invalid_role");
    await post({ email: "a@b.co", name: "A", role: "admin" });
    const dup = await post({ email: "A@B.CO", name: "A", role: "admin" });
    expect(dup.status).toBe(409);
    expect((await dup.json()).error).toBe("email_taken");
  });

  it("changes a role, rejects stale edits, and protects the caller's own admin access", async () => {
    const me = await (await api("/api/admin/me", { as: BOSS })).json();
    const created = await (await api("/api/admin/users", {
      as: BOSS, method: "POST", body: json({ email: "d@example.com", name: "Dee", role: "dispatcher" }),
    })).json();
    const patch = (id: string, body: unknown) => api(`/api/admin/users/${id}`, { as: BOSS, method: "PATCH", body: json(body) });

    const ok = await patch(created.user.id, { role: "reviewer", updatedAt: created.user.updatedAt });
    expect(ok.status).toBe(200);
    expect((await ok.json()).user.role).toBe("reviewer");

    const stale = await patch(created.user.id, { active: false, updatedAt: created.user.updatedAt });
    expect(stale.status).toBe(409);
    expect((await stale.json()).error).toBe("stale");

    const meRow = await env.DB.prepare("SELECT updated_at FROM users WHERE id = ?").bind(me.id).first<{ updated_at: string }>();
    const self = await patch(me.id, { role: "technician", updatedAt: meRow?.updated_at });
    expect(self.status).toBe(400);
    expect((await self.json()).error).toBe("cannot_change_self");

    expect((await patch("NOPE", { updatedAt: "x" })).status).toBe(404);
  });

  it("issues pairing codes only for active technicians and voids older codes", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const disp = await seedUser("d@example.com", "dispatcher");
    const issue = (id: string) => api(`/api/admin/users/${id}/pairing-code`, { as: BOSS, method: "POST" });

    const notTech = await issue(disp);
    expect(notTech.status).toBe(400);
    expect((await notTech.json()).error).toBe("not_a_technician");

    const first = await issue(tech);
    expect(first.status).toBe(201);
    const body = await first.json();
    expect(body.code).toMatch(/^\d{6}$/);
    expect(Date.parse(body.expiresAt) - Date.now()).toBeGreaterThan(9 * 60_000);

    await issue(tech);
    const open = await env.DB.prepare("SELECT COUNT(*) AS n FROM pairing_codes WHERE user_id = ? AND used_at IS NULL").bind(tech).first<{ n: number }>();
    expect(open?.n).toBe(1);
    const stored = await env.DB.prepare("SELECT code_hash FROM pairing_codes").all<{ code_hash: string }>();
    expect(stored.results.every((r) => r.code_hash !== body.code)).toBe(true);
    expect((await auditActions()).filter((a) => a === "pairing_code.issued")).toHaveLength(2);
  });
});
