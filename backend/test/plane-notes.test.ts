import { env } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import app from "../src/index";
import { stickyHtml } from "../src/lib/plane-notes";
import { api, BOSS, seedUser } from "./helpers";

const BASE = "http://plane.test";
const KEY = "plane-secret-key-123";
const planeEnv = { ...env, PLANE_BASE_URL: BASE, PLANE_API_KEY: KEY, PLANE_WORKSPACE_SLUG: "acme" } as typeof env;

let seq = 0;
async function device(name = "Tess Tech") {
  const email = `pn${++seq}@example.com`;
  const userId = await seedUser(email, "technician", name);
  const code = (await (await api(`/api/admin/users/${userId}/pairing-code`, { as: BOSS, method: "POST" })).json()).code;
  const paired = await (await api("/api/device/pair", { method: "POST", body: JSON.stringify({ code, label: email }) })).json();
  return { userId, email, headers: { Authorization: `Bearer ${paired.deviceKey}` } };
}

/** Calls the Worker with Plane mode on and waits for its background (waitUntil) work. */
async function call(path: string, init: RequestInit, e: typeof env = planeEnv) {
  const pending: Promise<unknown>[] = [];
  const ctx = { waitUntil: (p: Promise<unknown>) => pending.push(p), passThroughOnException() {} } as unknown as ExecutionContext;
  const headers = new Headers(init.headers);
  if (init.body !== undefined) headers.set("content-type", "application/json");
  const res = await app.fetch(new Request(`http://localhost/api/device${path}`, { ...init, headers }), e, ctx);
  await Promise.all(pending);
  return res;
}
const put = (h: Record<string, string>, id: string, body: object, e?: typeof env) =>
  call(`/stickies/${id}`, { method: "PUT", headers: h, body: JSON.stringify(body) }, e);
const del = (h: Record<string, string>, id: string, e?: typeof env) => call(`/stickies/${id}`, { method: "DELETE", headers: h }, e);
const note = { kind: "note", title: "Order spare igniter", text: "Order the spare igniter.", createdAt: 1795658320970, done: false };

// ---- A small in-memory Plane -------------------------------------------------
interface Call { method: string; path: string; body: Record<string, unknown> | null }
const calls: Call[] = [];
interface Fake {
  projects: { id: string; name: string; identifier: string }[];
  items: Map<string, { project: string; name?: string; description_html?: string; external_id?: string; assignees?: string[]; state?: string }>;
  comments: Map<string, { item: string; comment_html: string; external_id?: string }>;
  members: { id: string; email: string }[];
  down: boolean;
}
let fake: Fake;
let nextId = 0;
const uuid = () => `00000000-0000-4000-8000-${String(++nextId).padStart(12, "0")}`;

function stubPlane(init: Partial<Fake> = {}) {
  calls.length = 0;
  fake = { projects: [], items: new Map(), comments: new Map(), members: [], down: false, ...init };
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input, reqInit) => {
    const req = new Request(input as RequestInfo, reqInit);
    if (!req.url.startsWith(BASE)) throw new Error(`unexpected fetch ${req.url}`);
    expect(req.headers.get("X-API-Key")).toBe(KEY);
    const url = new URL(req.url);
    const p = url.pathname.replace("/api/v1/workspaces/acme/", "");
    const text = await req.text();
    const body = text ? JSON.parse(text) : null;
    calls.push({ method: req.method, path: p, body });
    const json = (b: unknown, status = 200) => new Response(JSON.stringify(b), { status, headers: { "content-type": "application/json" } });
    const notFound = () => json({ error: "not found" }, 404);
    if (fake.down) return new Response("busy", { status: 503 });
    if (p === "members/") return json(fake.members);
    if (p === "projects/") {
      if (req.method === "GET") return json({ results: fake.projects, next_page_results: false });
      if (fake.projects.some((x) => x.identifier === body.identifier)) return json({ identifier: "taken" }, 409);
      const proj = { id: uuid(), name: body.name, identifier: body.identifier };
      fake.projects.push(proj);
      return json(proj, 201);
    }
    let m = p.match(/^projects\/([^/]+)\/states\/$/);
    if (m) return json([{ id: "s-open", group: "unstarted" }]);
    m = p.match(/^projects\/([^/]+)\/work-items\/$/);
    if (m) {
      const project = m[1];
      if (req.method === "GET") {
        const results = [...fake.items].filter(([, v]) => v.project === project)
          .map(([id, v]) => ({ id, name: v.name, state: v.state ?? "s-open", assignees: v.assignees ?? [], parent: null }));
        return json({ results, next_page_results: false });
      }
      const dup = [...fake.items].find(([, v]) => v.project === project && body.external_id && v.external_id === body.external_id);
      if (dup) return json({ error: "exists", id: dup[0] }, 409);
      const id = uuid();
      fake.items.set(id, { project, name: body.name, description_html: body.description_html, external_id: body.external_id });
      return json({ id }, 201);
    }
    m = p.match(/^projects\/([^/]+)\/work-items\/([^/]+)\/$/);
    if (m) {
      const it = fake.items.get(m[2]);
      if (!it || it.project !== m[1]) return notFound();
      if (req.method === "GET") return json({ id: m[2] });
      if (req.method === "PATCH") { Object.assign(it, body); return json({ id: m[2] }); }
      if (req.method === "DELETE") { fake.items.delete(m[2]); return new Response(null, { status: 204 }); }
    }
    m = p.match(/^projects\/([^/]+)\/work-items\/([^/]+)\/comments\/$/);
    if (m && req.method === "POST") {
      const it = fake.items.get(m[2]);
      if (!it || it.project !== m[1]) return notFound();
      const id = uuid();
      fake.comments.set(id, { item: m[2], comment_html: body.comment_html, external_id: body.external_id });
      return json({ id }, 201);
    }
    m = p.match(/^projects\/([^/]+)\/work-items\/([^/]+)\/comments\/([^/]+)\/$/);
    if (m) {
      const cm = fake.comments.get(m[3]);
      if (!cm || cm.item !== m[2]) return notFound();
      if (req.method === "PATCH") { Object.assign(cm, body); return json({ id: m[3] }); }
      if (req.method === "DELETE") { fake.comments.delete(m[3]); return new Response(null, { status: 204 }); }
    }
    return notFound();
  });
}

const row = (userId: string, id: string) =>
  env.DB.prepare("SELECT * FROM stickies WHERE user_id=? AND id=?").bind(userId, id).first<Record<string, unknown>>();
const writes = () => calls.filter((c) => c.method !== "GET");

afterEach(() => vi.restoreAllMocks());

describe("sticky mirror to Plane", () => {
  it("does nothing in Plane when Plane mode is off", async () => {
    const t = await device();
    const spy = vi.spyOn(globalThis, "fetch");
    expect((await put(t.headers, "off1", { ...note, jobId: "plane-x" }, env)).status).toBe(200);
    expect(spy).not.toHaveBeenCalled();
    expect(await row(t.userId, "off1")).toMatchObject({ plane_state: "pending", job_id: "plane-x", deleted: 0 });
    expect((await del(t.headers, "off1", env)).status).toBe(204);
    expect(await row(t.userId, "off1")).toBeNull();
    expect(spy).not.toHaveBeenCalled();
  });

  it("rejects a bad jobId", async () => {
    const t = await device();
    stubPlane();
    expect((await put(t.headers, "j1", { ...note, jobId: "x".repeat(101) })).status).toBe(400);
    expect((await put(t.headers, "j1", { ...note, jobId: 5 })).status).toBe(400);
  });

  it("comments on a Plane job, PATCHes the same comment on update, deletes it then the row", async () => {
    const t = await device("Ada Tech");
    stubPlane({ projects: [{ id: "p1", name: "Plant 2", identifier: "PL2" }, { id: "p2", name: "Plant 3", identifier: "PL3" }] });
    fake.items.set("item-a", { project: "p2", name: "Replace igniter" });

    expect((await put(t.headers, "c1", { ...note, jobId: "plane-item-a" })).status).toBe(200);
    let r = await row(t.userId, "c1");
    expect(r).toMatchObject({ plane_state: "synced", plane_project_id: "p2", plane_item_id: "item-a", job_id: "plane-item-a" });
    const commentId = r!.plane_comment_id as string;
    expect(fake.comments.get(commentId)).toMatchObject({ item: "item-a", external_id: `sticky:t_default:${t.userId}:c1` });
    expect(fake.comments.get(commentId)!.comment_html).toContain("📝 Note from Ada Tech");
    expect(writes().map((c) => `${c.method} ${c.path}`)).toEqual([`POST projects/p2/work-items/item-a/comments/`]);

    calls.length = 0;
    expect((await put(t.headers, "c1", { ...note, jobId: "plane-item-a", text: "Ordered.", done: true })).status).toBe(200);
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([`PATCH projects/p2/work-items/item-a/comments/${commentId}/`]);
    expect(fake.comments.size).toBe(1);
    expect(fake.comments.get(commentId)!.comment_html).toContain("<p>Ordered.</p>");
    expect(fake.comments.get(commentId)!.comment_html).toContain("<p>✅ Done</p>");
    expect((await row(t.userId, "c1"))!.plane_state).toBe("synced");

    calls.length = 0;
    expect((await del(t.headers, "c1")).status).toBe(204);
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([`DELETE projects/p2/work-items/item-a/comments/${commentId}/`]);
    expect(fake.comments.size).toBe(0);
    expect(await row(t.userId, "c1")).toBeNull();
  });

  it("job-less stickies create the NOTES project once, then a work item each; delete removes it", async () => {
    const t = await device();
    stubPlane({ projects: [{ id: "p1", name: "Plant 2", identifier: "PL2" }] });
    expect((await put(t.headers, "n1", note)).status).toBe(200);
    expect((await put(t.headers, "n2", { ...note, kind: "draft", title: "Email the client", text: "Hi\nthere" })).status).toBe(200);
    expect(calls.filter((c) => c.method === "POST" && c.path === "projects/")).toEqual([
      { method: "POST", path: "projects/", body: { name: "Field Notes", identifier: "NOTES" } },
    ]);
    const notes = fake.projects.find((p) => p.identifier === "NOTES")!;
    const r1 = await row(t.userId, "n1");
    const r2 = await row(t.userId, "n2");
    expect(r1).toMatchObject({ plane_state: "synced", plane_project_id: notes.id, plane_comment_id: null });
    expect(r2).toMatchObject({ plane_state: "synced", plane_project_id: notes.id });
    const item2 = fake.items.get(r2!.plane_item_id as string)!;
    expect(item2).toMatchObject({ name: "Email the client", project: notes.id });
    expect(item2.description_html).toContain("✉️ Draft from Tess Tech");
    expect(item2.description_html).toContain("<p>Hi<br>there</p>");

    calls.length = 0;
    await put(t.headers, "n2", { ...note, kind: "draft", title: "Email the client (sent)", text: "Hi" });
    expect(calls.map((c) => `${c.method} ${c.path}`)).toEqual([`PATCH projects/${notes.id}/work-items/${r2!.plane_item_id}/`]);
    expect(fake.items.get(r2!.plane_item_id as string)!.name).toBe("Email the client (sent)");

    expect((await del(t.headers, "n1")).status).toBe(204);
    expect(fake.items.has(r1!.plane_item_id as string)).toBe(false);
    expect(await row(t.userId, "n1")).toBeNull();
  });

  it("answers 200 while Plane is down; the row stays pending and a later call syncs it", async () => {
    const t = await device();
    stubPlane({ projects: [{ id: "p1", name: "Plant 2", identifier: "PL2" }], down: true });
    fake.items.set("item-b", { project: "p1" });
    const err = vi.spyOn(console, "error").mockImplementation(() => {});
    expect((await put(t.headers, "r1", { ...note, jobId: "plane-item-b" })).status).toBe(200);
    expect((await put(t.headers, "r2", note)).status).toBe(200);
    expect(await row(t.userId, "r1")).toMatchObject({ plane_state: "pending", plane_item_id: null, plane_claimed_at: null });
    expect(JSON.stringify(err.mock.calls)).not.toContain(KEY);
    expect(JSON.stringify(err.mock.calls)).not.toContain("igniter");

    // The admin list still shows them.
    const listed = (await (await api("/api/admin/stickies", { as: BOSS })).json()).stickies.filter((s: { technician: { id: string } }) => s.technician.id === t.userId);
    expect(listed).toHaveLength(2);

    fake.down = false;
    await call("/jobs", { headers: t.headers }); // the jobs fetch retries in the background
    expect(await row(t.userId, "r1")).toMatchObject({ plane_state: "synced", plane_item_id: "item-b" });
    expect(await row(t.userId, "r2")).toMatchObject({ plane_state: "synced" });
    expect(fake.comments.size).toBe(1);
  });

  it("a delete that Plane misses is a hidden tombstone until a retry removes it", async () => {
    const t = await device();
    stubPlane({ projects: [] });
    await put(t.headers, "t1", note);
    const itemId = (await row(t.userId, "t1"))!.plane_item_id as string;
    fake.down = true;
    vi.spyOn(console, "error").mockImplementation(() => {});
    expect((await del(t.headers, "t1")).status).toBe(204);
    expect(await row(t.userId, "t1")).toMatchObject({ deleted: 1, plane_state: "delete_pending" });
    const listed = (await (await api("/api/admin/stickies", { as: BOSS })).json()).stickies.filter((s: { id: string }) => s.id === "t1");
    expect(listed).toHaveLength(0);
    fake.down = false;
    await put(t.headers, "other", { ...note, title: "x" });
    expect(fake.items.has(itemId)).toBe(false);
    expect(await row(t.userId, "t1")).toBeNull();
  });

  it("treats a Plane 404 on delete as already gone", async () => {
    const t = await device();
    stubPlane({ projects: [] });
    await put(t.headers, "g1", note);
    fake.items.clear();
    expect((await del(t.headers, "g1")).status).toBe(204);
    expect(await row(t.userId, "g1")).toBeNull();
  });

  it("does not run two mirrors of a claimed row", async () => {
    const t = await device();
    stubPlane({ projects: [] });
    await put(t.headers, "k1", note, env); // stored, not mirrored (Plane off)
    await env.DB.prepare("UPDATE stickies SET plane_claimed_at=? WHERE user_id=? AND id='k1'").bind(new Date().toISOString(), t.userId).run();
    await call("/jobs", { headers: t.headers });
    expect(writes()).toHaveLength(0);
    expect((await row(t.userId, "k1"))!.plane_state).toBe("pending");
    // An expired claim is taken over.
    await env.DB.prepare("UPDATE stickies SET plane_claimed_at='2000-01-01T00:00:00.000Z' WHERE user_id=? AND id='k1'").bind(t.userId).run();
    await call("/jobs", { headers: t.headers });
    expect((await row(t.userId, "k1"))!.plane_state).toBe("synced");
  });

  it("escapes user text", async () => {
    const t = await device("<b>Eve</b>");
    stubPlane({ projects: [] });
    await put(t.headers, "x1", { ...note, title: "<script>alert(1)</script>", text: "a & b <img src=x onerror=1>" });
    const it = fake.items.get((await row(t.userId, "x1"))!.plane_item_id as string)!;
    expect(it.description_html).not.toContain("<script>");
    expect(it.description_html).not.toContain("<img");
    expect(it.description_html).not.toContain("<b>Eve");
    expect(it.description_html).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(it.description_html).toContain("a &amp; b &lt;img src=x onerror=1&gt;");
    expect(it.name).toBe("<script>alert(1)</script>"); // plain-text field, Plane renders it as text
  });

  it("builds the body in the agreed shape", () => {
    expect(stickyHtml({ kind: "note", title: "T", text: "one\ntwo\n\nthree", created_at: "2026-11-27T10:00:00.000Z", done: 0 }, "Ada"))
      .toBe("<p><strong>📝 Note from Ada</strong></p><p><strong>T</strong></p><p>one<br>two</p><p>three</p><p><em>Saved 2026-11-27T10:00:00.000Z from BobAI</em></p>");
  });
});

describe("legacy stickies with only a job title", () => {
  const setup = async () => {
    const t = await device();
    stubPlane({
      projects: [{ id: "p1", name: "Riverside Clinic", identifier: "RC" }, { id: "p2", name: "Plant 3", identifier: "PL3" }],
      members: [{ id: "m1", email: t.email }],
    });
    fake.items.set("job-1", { project: "p1", name: "Replace igniter on the ABC-123 boiler", assignees: ["m1"] });
    fake.items.set("job-2", { project: "p2", name: "Check pump", assignees: ["m1"] });
    fake.items.set("job-3", { project: "p1", name: "Check pump", assignees: ["m1"] });
    fake.items.set("job-4", { project: "p2", name: "Other tech's job", assignees: ["m9"] });
    return t;
  };

  for (const title of ["replace igniter on the ABC-123 boiler", "Replace igniter on the ABC-123 boiler — Riverside Clinic"]) {
    it(`matches "${title}" to the technician's one Plane job`, async () => {
      const t = await setup();
      await put(t.headers, "l1", { ...note, jobTitle: title });
      expect(await row(t.userId, "l1")).toMatchObject({ job_id: "plane-job-1", plane_item_id: "job-1", plane_project_id: "p1", plane_state: "synced" });
      expect([...fake.comments.values()].map((c) => c.item)).toEqual(["job-1"]);
      // An older phone re-uploading without jobId keeps the resolved job: same comment is PATCHed.
      calls.length = 0;
      await put(t.headers, "l1", { ...note, jobTitle: title, text: "Edited" });
      expect(writes().map((c) => c.method)).toEqual(["PATCH"]);
      expect((await row(t.userId, "l1"))!.job_id).toBe("plane-job-1");
    });
  }

  for (const [label, title] of [["ambiguous", "Check pump"], ["unknown", "No such job"], ["someone else's", "Other tech's job"]]) {
    it(`falls back to Field Notes for an ${label} title`, async () => {
      const t = await setup();
      await put(t.headers, "l2", { ...note, jobTitle: title });
      const r = await row(t.userId, "l2");
      const notes = fake.projects.find((p) => p.identifier === "NOTES")!;
      expect(r).toMatchObject({ job_id: null, plane_project_id: notes.id, plane_comment_id: null, plane_state: "synced" });
      expect(fake.comments.size).toBe(0);
    });
  }
});
