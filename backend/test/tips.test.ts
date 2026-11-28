import { env } from "cloudflare:test";
import { describe, expect, it, vi } from "vitest";
import { api, auditActions, BOSS, seedUser } from "./helpers";

async function device(email: string, name: string) {
  const userId = await seedUser(email, "technician", name);
  const code = (await (await api(`/api/admin/users/${userId}/pairing-code`, { as: BOSS, method: "POST" })).json()).code;
  const paired = await (await api("/api/device/pair", { method: "POST", body: JSON.stringify({ code, label: email }) })).json();
  return { userId, headers: { Authorization: `Bearer ${paired.deviceKey}` } };
}
const makeSenior = async (userId: string) => {
  const user = (await (await api("/api/admin/users", { as: BOSS })).json()).users.find((u: { id: string }) => u.id === userId);
  return api(`/api/admin/users/${userId}`, { as: BOSS, method: "PATCH", body: JSON.stringify({ senior: true, updatedAt: user.updatedAt }) });
};
const tip = (headers: Record<string, string>, body: unknown) =>
  api("/api/device/tips", { headers, method: "POST", body: JSON.stringify(body) });
const patchDoc = (id: string, body: unknown) =>
  api(`/api/admin/documents/${id}`, { as: BOSS, method: "PATCH", body: JSON.stringify(body) });

describe("senior flag", () => {
  it("admin sets senior on a technician; the list and /device/me reflect it", async () => {
    const tech = await device("sam@example.com", "Sam");
    const me = () => api("/api/device/me", { headers: tech.headers });
    expect((await (await me()).json()).senior).toBe(false);
    const res = await makeSenior(tech.userId);
    expect(res.status).toBe(200);
    expect((await res.json()).user.senior).toBe(true);
    const body = await (await me()).json();
    expect(body.senior).toBe(true);
    expect(body.user).toEqual({ name: "Sam", email: "sam@example.com" });
    const listed = (await (await api("/api/admin/users", { as: BOSS })).json()).users.find((u: { id: string }) => u.id === tech.userId);
    expect(listed.senior).toBe(true);
  });

  it("rejects senior on non-technicians and non-boolean values", async () => {
    await api("/api/admin/me", { as: BOSS });
    const rev = await seedUser("rev@example.com", "reviewer");
    const tech = await seedUser("t@example.com", "technician");
    const get = async (id: string) =>
      (await (await api("/api/admin/users", { as: BOSS })).json()).users.find((u: { id: string }) => u.id === id).updatedAt;
    const r1 = await api(`/api/admin/users/${rev}`, { as: BOSS, method: "PATCH", body: JSON.stringify({ senior: true, updatedAt: await get(rev) }) });
    expect(r1.status).toBe(400);
    const r2 = await api(`/api/admin/users/${tech}`, { as: BOSS, method: "PATCH", body: JSON.stringify({ senior: "yes", updatedAt: await get(tech) }) });
    expect(r2.status).toBe(400);
  });

  it("clears senior when a technician's role changes", async () => {
    const tech = await device("lee@example.com", "Lee");
    await makeSenior(tech.userId);
    const u = (await (await api("/api/admin/users", { as: BOSS })).json()).users.find((x: { id: string }) => x.id === tech.userId);
    const res = await api(`/api/admin/users/${tech.userId}`, { as: BOSS, method: "PATCH", body: JSON.stringify({ role: "dispatcher", updatedAt: u.updatedAt }) });
    expect((await res.json()).user.senior).toBe(false);
  });
});

describe("POST /api/device/tips", () => {
  it("a senior technician's tip is a draft document with the text in R2, never logged", async () => {
    const logs = [vi.spyOn(console, "log"), vi.spyOn(console, "info"), vi.spyOn(console, "warn"), vi.spyOn(console, "error")];
    const tech = await device("ana@example.com", "Ana");
    await makeSenior(tech.userId);
    const res = await tip(tech.headers, { title: "  Reset the boiler  ", text: "  Hold the red button for ten seconds.  " });
    expect(res.status).toBe(201);
    const { id, status } = await res.json();
    expect(status).toBe("draft");
    const row = await env.DB.prepare("SELECT * FROM documents WHERE id = ?").bind(id).first<Record<string, unknown>>();
    expect(row).toMatchObject({
      kind: "tip", status: "draft", title: "Reset the boiler", revision: "tip", models_json: "[]",
      content_type: "text/plain", filename: "tip.txt", created_by: tech.userId,
    });
    expect(JSON.parse(row!.pages_json as string)).toEqual([{ number: 1, heading: "Reset the boiler", body: "Hold the red button for ten seconds." }]);
    const obj = await env.DOCUMENTS.get(row!.storage_key as string);
    expect(await obj!.text()).toBe("Hold the red button for ten seconds.");
    expect(row!.size_bytes).toBe(36);
    expect(await auditActions()).toContain("document.uploaded");
    const audit = JSON.stringify((await env.DB.prepare("SELECT before_json, after_json FROM audit_log").all()).results);
    expect(audit).not.toContain("boiler");
    expect(audit).not.toContain("red button");
    for (const spy of logs) expect(JSON.stringify(spy.mock.calls)).not.toContain("red button");
    vi.restoreAllMocks();
  });

  it("refuses non-senior technicians with 403 not_senior, and devices with no key with 401", async () => {
    const tech = await device("bo@example.com", "Bo");
    const res = await tip(tech.headers, { title: "T", text: "x" });
    expect(res.status).toBe(403);
    expect((await res.json()).error).toBe("not_senior");
    expect((await api("/api/device/tips", { method: "POST", body: JSON.stringify({ title: "T", text: "x" }) })).status).toBe(401);
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM documents").first<{ n: number }>())?.n).toBe(0);
  });

  it("refuses a senior who was deactivated", async () => {
    const tech = await device("cy@example.com", "Cy");
    await makeSenior(tech.userId);
    await env.DB.prepare("UPDATE users SET active = 0 WHERE id = ?").bind(tech.userId).run();
    const res = await tip(tech.headers, { title: "T", text: "x" });
    expect(res.status).toBe(401);
  });

  it("validates title and text lengths after trimming", async () => {
    const tech = await device("di@example.com", "Di");
    await makeSenior(tech.userId);
    const bad = [
      { title: "", text: "x" }, { title: "   ", text: "x" }, { title: "T", text: "" }, { title: "T", text: "  " },
      { title: "a".repeat(81), text: "x" }, { title: "T", text: "a".repeat(4001) },
      { title: 5, text: "x" }, { title: "T" }, null,
    ];
    for (const b of bad) {
      const res = await tip(tech.headers, b);
      expect(res.status).toBe(400);
      expect(await res.json()).toMatchObject({ error: "invalid" });
    }
    expect((await tip(tech.headers, { title: "a".repeat(80), text: "b".repeat(4000) })).status).toBe(201);
  });

  it("flows through review, manifest and phone download like a manual, with kind and author", async () => {
    const author = await device("eve@example.com", "Eve Expert");
    const other = await device("fay@example.com", "Fay");
    await makeSenior(author.userId);
    const { id } = await (await tip(author.headers, { title: "Pump priming", text: "Open the bleed screw first." })).json();

    // Draft: office sees it with kind and author; phones do not.
    const list = (await (await api("/api/admin/documents", { as: BOSS })).json()).documents;
    expect(list[0]).toMatchObject({ id, kind: "tip", author: "Eve Expert", status: "draft", page_count: 1 });
    const pages = await (await api(`/api/admin/documents/${id}/pages`, { as: BOSS })).json();
    expect(pages).toMatchObject({ kind: "tip", author: "Eve Expert", pages: [{ body: "Open the bleed screw first." }] });
    expect((await (await api("/api/device/knowledge", { headers: other.headers })).json()).documents).toHaveLength(0);

    expect((await patchDoc(id, { status: "approved", version: 1 })).status).toBe(200);
    const manifest = (await (await api("/api/device/knowledge", { headers: other.headers })).json()).documents;
    expect(manifest).toHaveLength(1);
    expect(manifest[0]).toMatchObject({ id, kind: "tip", author: "Eve Expert", title: "Pump priming", approvalStatus: "APPROVED", applicableModels: [] });
    const single = await (await api(`/api/device/knowledge/${id}`, { headers: other.headers })).json();
    expect(single.document).toMatchObject({ kind: "tip", author: "Eve Expert", pages: [{ number: 1, body: "Open the bleed screw first." }] });
    const docs = (await (await api("/api/device/documents", { headers: other.headers })).json()).documents;
    expect(docs[0]).toMatchObject({ id, kind: "tip", author: "Eve Expert" });
    const file = await api(`/api/device/documents/${id}/file`, { headers: other.headers });
    expect(file.status).toBe(200);
    expect(await file.text()).toBe("Open the bleed screw first.");

    expect((await patchDoc(id, { status: "revoked", version: 2 })).status).toBe(200);
    expect((await (await api("/api/device/knowledge", { headers: other.headers })).json()).documents).toHaveLength(0);
  });

  it("manuals have kind 'manual' and no author in the manifest", async () => {
    const tech = await device("gus@example.com", "Gus");
    await api("/api/admin/me", { as: BOSS });
    const form = new FormData();
    form.set("title", "Manual"); form.set("revision", "A"); form.set("models", "X1");
    form.set("file", new File(["Step one."], "m.txt", { type: "text/plain" }));
    const { id } = await (await api("/api/admin/documents", { as: BOSS, method: "POST", body: form, headers: { Origin: "http://localhost" } })).json();
    await patchDoc(id, { status: "approved", version: 1 });
    const m = (await (await api("/api/device/knowledge", { headers: tech.headers })).json()).documents[0];
    expect(m.kind).toBe("manual");
    expect("author" in m).toBe(false);
  });
});
