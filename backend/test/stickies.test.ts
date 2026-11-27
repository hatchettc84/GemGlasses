import { env } from "cloudflare:test";
import { describe, it, expect } from "vitest";
import { api, BOSS, seedUser } from "./helpers";

async function device(email: string, name?: string) {
  const userId = await seedUser(email, "technician", name);
  const code = (await (await api(`/api/admin/users/${userId}/pairing-code`, { as: BOSS, method: "POST" })).json()).code;
  const paired = await (await api("/api/device/pair", { method: "POST", body: JSON.stringify({ code, label: email }) })).json();
  return { userId, headers: { Authorization: `Bearer ${paired.deviceKey}` } };
}
const put = (id: string, headers: Record<string, string>, body: unknown) =>
  api(`/api/device/stickies/${id}`, { headers, method: "PUT", body: JSON.stringify(body) });
const good = { kind: "note", title: "Order spare igniter", text: "Order the spare igniter.", jobTitle: "Replace igniter", createdAt: 1795658320970, done: false };

describe("stickies", () => {
  it("rejects unpaired and invalid requests", async () => {
    expect((await api("/api/device/stickies/a1", { method: "PUT", body: JSON.stringify(good) })).status).toBe(401);
    const t = await device("st-bad@example.com");
    expect((await put("a1", t.headers, { ...good, kind: "memo" })).status).toBe(400);
    expect((await put("a1", t.headers, { ...good, title: "x".repeat(81) })).status).toBe(400);
    expect((await put("a1", t.headers, { ...good, text: "x".repeat(2001) })).status).toBe(400);
    expect((await put("a1", t.headers, { ...good, text: " " })).status).toBe(400);
    expect((await put("a1", t.headers, { ...good, jobTitle: "x".repeat(201) })).status).toBe(400);
    expect((await put("a1", t.headers, { ...good, createdAt: 0 })).status).toBe(400);
    expect((await put("a1", t.headers, { ...good, createdAt: 1.5 })).status).toBe(400);
    expect((await put("a1", t.headers, { ...good, done: "yes" })).status).toBe(400);
    expect((await put("bad.id", t.headers, good)).status).toBe(400);
    expect((await put("a".repeat(65), t.headers, good)).status).toBe(400);
  });

  it("creates, replaces and deletes idempotently; console lists newest first with filter", async () => {
    const t = await device("st-ok@example.com", "Ada Tech");
    expect((await put("n1", t.headers, good)).status).toBe(200);
    expect((await put("d1", t.headers, { ...good, kind: "draft", title: "Email", text: "Line1\nLine2", jobTitle: null, createdAt: 1795658330970 })).status).toBe(200);
    expect((await put("n1", t.headers, { ...good, text: "Changed", done: true })).status).toBe(200);

    const all = (await (await api("/api/admin/stickies", { as: BOSS })).json()).stickies;
    expect(all.map((s: { id: string }) => s.id)).toEqual(["d1", "n1"]);
    expect(all[0]).toMatchObject({ kind: "draft", jobTitle: null, done: false, text: "Line1\nLine2", technician: { id: t.userId, name: "Ada Tech" } });
    expect(all[1]).toMatchObject({ text: "Changed", done: true, jobTitle: "Replace igniter", createdAt: new Date(1795658320970).toISOString() });
    const drafts = (await (await api("/api/admin/stickies?kind=draft", { as: BOSS })).json()).stickies;
    expect(drafts.map((s: { id: string }) => s.id)).toEqual(["d1"]);
    expect((await api("/api/admin/stickies?kind=zzz", { as: BOSS })).status).toBe(400);

    const del = await api("/api/device/stickies/n1", { headers: t.headers, method: "DELETE" });
    expect(del.status).toBe(204);
    expect((await api("/api/device/stickies/n1", { headers: t.headers, method: "DELETE" })).status).toBe(204);
    expect((await (await api("/api/admin/stickies?kind=note", { as: BOSS })).json()).stickies.filter((s: { technician: { id: string } }) => s.technician.id === t.userId)).toHaveLength(0);
  });

  it("scopes writes to the technician and reads to roles", async () => {
    const a = await device("st-a@example.com");
    const b = await device("st-b@example.com");
    await put("same", a.headers, { ...good, text: "A text" });
    await put("same", b.headers, { ...good, text: "B text" });
    expect((await api("/api/device/stickies/same", { headers: b.headers, method: "DELETE" })).status).toBe(204);
    const row = await env.DB.prepare("SELECT text FROM stickies WHERE user_id=? AND id='same'").bind(a.userId).first<{ text: string }>();
    expect(row?.text).toBe("A text");
    await seedUser("st-tech@example.com", "technician");
    expect((await api("/api/admin/stickies", { as: "st-tech@example.com" })).status).toBe(403);
    await seedUser("st-rev@example.com", "reviewer");
    expect((await api("/api/admin/stickies", { as: "st-rev@example.com" })).status).toBe(200);
  });

  it("caps a technician at 500 stickies, but still allows replacing existing ones", async () => {
    const t = await device("st-cap@example.com");
    const now = new Date().toISOString();
    const stmts = Array.from({ length: 500 }, (_, i) =>
      env.DB.prepare("INSERT INTO stickies (tenant_id,user_id,id,device_id,kind,title,text,job_title,created_at,done,updated_at) VALUES ('t_default',?,?,'d','note','t','x',NULL,?,0,?)")
        .bind(t.userId, `s${i}`, now, now));
    await env.DB.batch(stmts);
    const over = await put("extra", t.headers, good);
    expect(over.status).toBe(409);
    expect((await over.json()).error).toBe("too many stickies");
    expect((await put("s0", t.headers, good)).status).toBe(200);
  });
});
