import { env } from "cloudflare:test";
import { afterEach, describe, expect, it, vi } from "vitest";
import app from "../src/index";
import { planeConfig, fetchPlaneJobs } from "../src/lib/plane";
import { api, BOSS, seedUser } from "./helpers";

const BASE = "http://plane.test";
const KEY = "plane-secret-key-123";
const planeEnv = (extra: Record<string, string> = {}) => ({
  ...env,
  PLANE_BASE_URL: BASE,
  PLANE_API_KEY: KEY,
  PLANE_WORKSPACE_SLUG: "acme",
  ...extra,
});

async function device(email = "tech@example.com", name = "Tess Tech") {
  const userId = await seedUser(email, "technician", name);
  const code = (await (await api(`/api/admin/users/${userId}/pairing-code`, { as: BOSS, method: "POST" })).json()).code;
  const paired = await (await api("/api/device/pair", { method: "POST", body: JSON.stringify({ code, label: email }) })).json();
  return { userId, headers: { Authorization: `Bearer ${paired.deviceKey}` } };
}

type Json = unknown;
interface Fake {
  members: Json;
  projects: Json;
  states: Record<string, Json>;
  items: Record<string, Json[]>; // pages
}
const calls: { url: string; headers: Headers }[] = [];

function stubPlane(f: Partial<Fake> & { fail?: (url: URL) => Response | Error | null }) {
  calls.length = 0;
  return vi.spyOn(globalThis, "fetch").mockImplementation(async (input, init) => {
    const req = new Request(input as RequestInfo, init);
    if (!req.url.startsWith(BASE)) throw new Error(`unexpected fetch ${req.url}`);
    const url = new URL(req.url);
    calls.push({ url: req.url, headers: req.headers });
    const bad = f.fail?.(url);
    if (bad instanceof Error) throw bad;
    if (bad) return bad;
    const p = url.pathname.replace("/api/v1/workspaces/acme/", "");
    const ok = (b: Json) => new Response(JSON.stringify(b), { headers: { "content-type": "application/json" } });
    if (p === "members/") return ok(f.members ?? []);
    if (p === "projects/") return ok(f.projects ?? []);
    let m = p.match(/^projects\/([^/]+)\/states\/$/);
    if (m) return ok(f.states?.[m[1]] ?? []);
    m = p.match(/^projects\/([^/]+)\/work-items\/$/);
    if (m) {
      const pages = f.items?.[m[1]] ?? [];
      const idx = url.searchParams.get("cursor") ? Number(url.searchParams.get("cursor")) : 0;
      const more = idx + 1 < pages.length;
      return ok({ results: pages[idx] ?? [], next_cursor: more ? String(idx + 1) : null, next_page_results: more });
    }
    return new Response("nope", { status: 404 });
  });
}

const item = (id: string, o: Record<string, unknown> = {}) => ({
  id, name: `Item ${id}`, description_stripped: " desc ", state: "s-open", assignees: ["m1"],
  target_date: "2026-12-01", parent: null, sequence_id: 1, created_at: "2026-11-01T00:00:00Z", updated_at: "2026-11-02T00:00:00Z", ...o,
});
const STATES = [
  { id: "s-open", name: "Todo", group: "unstarted" },
  { id: "s-back", name: "Backlog", group: "backlog" },
  { id: "s-tri", name: "Triage", group: "triage" },
  { id: "s-go", name: "Doing", group: "started" },
  { id: "s-done", name: "Done", group: "completed" },
  { id: "s-x", name: "Cancelled", group: "cancelled" },
];
const base = (extra: Partial<Fake> = {}): Partial<Fake> => ({
  members: [{ id: "m1", email: "Tech@Example.com", display_name: "t" }, { id: "m2", email: "o@example.com" }],
  projects: { results: [{ id: "p1", name: "Plant 2" }, { id: "p2", name: "Plant 3" }], next_cursor: null, next_page_results: false },
  states: { p1: STATES, p2: STATES },
  items: { p1: [[]], p2: [[]] },
  ...extra,
});

const getJobs = async (headers: Record<string, string>, e: object = planeEnv()) =>
  app.fetch(new Request("http://localhost/api/device/jobs", { headers }), e as typeof env);

afterEach(() => vi.restoreAllMocks());

describe("planeConfig", () => {
  it("is null unless base url, key and slug are all set", () => {
    expect(planeConfig(env)).toBeNull();
    expect(planeConfig({ ...env, PLANE_BASE_URL: BASE, PLANE_API_KEY: KEY } as typeof env)).toBeNull();
    expect(planeConfig(planeEnv() as typeof env)).toMatchObject({ workspaceSlug: "acme", projectIds: [] });
    expect(planeConfig(planeEnv({ PLANE_PROJECT_IDS: " p1, ,p2 " }) as typeof env)?.projectIds).toEqual(["p1", "p2"]);
  });
});

describe("device jobs in D1 mode", () => {
  it("does not call Plane when vars are absent", async () => {
    const d = await device();
    const spy = vi.spyOn(globalThis, "fetch");
    const res = await api("/api/device/jobs", { headers: d.headers });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ jobs: [], tasks: [] });
    expect(spy).not.toHaveBeenCalled();
  });
});

describe("device jobs from Plane", () => {
  it("maps items to job and task rows", async () => {
    const d = await device();
    stubPlane(base({
      items: {
        p1: [[
          item("a", { state: "s-go", updated_at: "2026-11-03T00:00:00Z" }),
          item("b", { state: "s-done", description_stripped: null, target_date: null, updated_at: "2026-11-05T00:00:00Z" }),
          item("c", { state: "s-open", assignees: ["m2"] }),
          item("d", { state: "s-x" }),
          item("e", { state: "s-back" }),
          item("f", { state: "s-tri", updated_at: "2026-11-01T00:00:00Z" }),
          item("sa", { parent: "a", assignees: [], state: "s-done" }),
          item("sb", { parent: "a", assignees: [], state: "s-open" }),
          item("sx", { parent: "a", assignees: [], state: "s-x" }),
          item("sc", { parent: "c", assignees: [] }),
          item("long", { description_stripped: "x".repeat(3000) }),
        ]],
        p2: [[]],
      },
    }));
    const res = await getJobs(d.headers);
    expect(res.status).toBe(200);
    const { jobs, tasks } = await res.json() as { jobs: Record<string, unknown>[]; tasks: Record<string, unknown>[] };
    const byId = Object.fromEntries(jobs.map((j) => [j.id, j]));
    expect(Object.keys(byId).sort()).toEqual(["plane-a", "plane-b", "plane-e", "plane-f", "plane-long"]);
    expect(byId["plane-a"]).toEqual({
      id: "plane-a", tenant_id: "t_default", title: "Item a", site: "Plant 2", description: "desc",
      assigned_user_id: d.userId, assigned_name: "Tess Tech", status: "in_progress", due_at: "2026-12-01",
      revision: 1, created_by: "plane", created_at: "2026-11-01T00:00:00Z", updated_at: "2026-11-03T00:00:00Z",
    });
    expect(byId["plane-b"]).toMatchObject({ status: "completed", description: "", due_at: null });
    expect(byId["plane-e"]).toMatchObject({ status: "open" });
    expect(byId["plane-f"]).toMatchObject({ status: "open" });
    expect((byId["plane-long"] as { description: string }).description).toHaveLength(2000);
    expect(jobs.map((j) => j.id).slice(0, 2)).toEqual(["plane-b", "plane-a"]); // updated_at desc
    expect(tasks.map((t) => t.id).sort()).toEqual(["plane-sa", "plane-sb"]);
    expect(tasks.find((t) => t.id === "plane-sa")).toEqual({
      id: "plane-sa", tenant_id: "t_default", job_id: "plane-a", title: "Item sa", completed: 1, revision: 1,
      created_at: "2026-11-01T00:00:00Z", updated_at: "2026-11-02T00:00:00Z",
    });
    expect(tasks.find((t) => t.id === "plane-sb")).toMatchObject({ completed: 0 });
  });

  it("sends X-API-Key and never puts the key in a URL", async () => {
    const d = await device();
    stubPlane(base());
    await getJobs(d.headers);
    expect(calls.length).toBeGreaterThan(3);
    for (const c of calls) {
      expect(c.headers.get("X-API-Key")).toBe(KEY);
      expect(c.url).not.toContain(KEY);
    }
  });

  it("follows next_cursor across pages", async () => {
    const d = await device();
    stubPlane(base({ items: { p1: [[item("a")], [item("b")], [item("c")]], p2: [[]] } }));
    const { jobs } = await (await getJobs(d.headers)).json() as { jobs: { id: string }[] };
    expect(jobs.map((j) => j.id).sort()).toEqual(["plane-a", "plane-b", "plane-c"]);
  });

  it("stops at 20 pages per project", async () => {
    const d = await device();
    const pages = Array.from({ length: 30 }, (_, i) => [item(`i${i}`)]);
    stubPlane(base({ items: { p1: pages, p2: [[]] } }));
    const { jobs } = await (await getJobs(d.headers)).json() as { jobs: unknown[] };
    expect(jobs).toHaveLength(20);
    expect(calls.filter((c) => c.url.includes("/work-items/"))).toHaveLength(21); // 20 for p1 + 1 for p2
  });

  it("restricts to PLANE_PROJECT_IDS", async () => {
    const d = await device();
    stubPlane(base({ items: { p1: [[item("a")]], p2: [[item("b")]] } }));
    const { jobs } = await (await getJobs(d.headers, planeEnv({ PLANE_PROJECT_IDS: "p2" }))).json() as { jobs: { id: string; site: string }[] };
    expect(jobs.map((j) => [j.id, j.site])).toEqual([["plane-b", "Plant 3"]]);
    expect(calls.some((c) => c.url.includes("/projects/p1/"))).toBe(false);
  });

  it("tolerates a bare-array members response and matches email case-insensitively", async () => {
    const d = await device("MiXed@Example.com");
    stubPlane(base({
      members: { results: [{ id: "m1", email: "mixed@example.com" }] },
      items: { p1: [[item("a")]], p2: [[]] },
    }));
    expect(((await (await getJobs(d.headers)).json()) as { jobs: unknown[] }).jobs).toHaveLength(1);
  });

  it("returns empty lists when no Plane member has the email", async () => {
    const d = await device("nobody@example.com");
    stubPlane(base({ items: { p1: [[item("a")]], p2: [[]] } }));
    const res = await getJobs(d.headers);
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ jobs: [], tasks: [] });
  });

  const unavailable = {
    error: "plane_unavailable",
    message: "Jobs are managed in Plane, which could not be reached. Try again shortly.",
  };
  for (const [name, fail] of [
    ["a 500", () => new Response(`boom ${KEY}`, { status: 500 })],
    ["a network error", () => new TypeError(`connect failed ${KEY}`)],
    ["bad JSON", () => new Response("<html>not json " + KEY, { status: 200 })],
  ] as const) {
    it(`returns 502 plane_unavailable on ${name} without leaking the key`, async () => {
      const d = await device();
      stubPlane({ fail: () => fail() });
      const err = vi.spyOn(console, "error").mockImplementation(() => {});
      const res = await getJobs(d.headers);
      expect(res.status).toBe(502);
      const text = await res.text();
      expect(JSON.parse(text)).toEqual(unavailable);
      expect(text).not.toContain(KEY);
      expect(JSON.stringify(err.mock.calls)).not.toContain(KEY);
    });
  }

  it("fetchPlaneJobs is callable directly", async () => {
    stubPlane(base({ items: { p1: [[item("a")]], p2: [[]] } }));
    const cfg = planeConfig(planeEnv() as typeof env)!;
    const r = await fetchPlaneJobs(cfg, "tech@example.com", { id: "u1", name: "T", tenantId: "t_default" });
    expect(r.jobs).toHaveLength(1);
  });
});

describe("device writes for Plane ids", () => {
  it("PATCH job/task with plane- ids is 409 managed_in_plane in Plane mode", async () => {
    const d = await device();
    for (const path of ["/jobs/plane-a", "/tasks/plane-b"]) {
      const res = await app.fetch(
        new Request(`http://localhost/api/device${path}`, {
          method: "PATCH", headers: { ...d.headers, "content-type": "application/json" },
          body: JSON.stringify({ revision: 1, status: "completed", completed: true }),
        }),
        planeEnv() as typeof env,
      );
      expect(res.status).toBe(409);
      expect(await res.json()).toEqual({ error: "managed_in_plane", message: "This job is managed in Plane. Update it there." });
    }
  });
});

describe("GET /api/admin/integrations/plane", () => {
  const get = (e: object) =>
    app.fetch(new Request("http://localhost/api/admin/integrations/plane", { headers: { "X-Dev-User-Email": BOSS } }), e as typeof env);
  it("reports disabled", async () => {
    expect(await (await get(env)).json()).toEqual({ enabled: false, workspaceSlug: null, webUrl: null });
  });
  it("reports enabled with web url, without calling Plane or leaking the key", async () => {
    const spy = vi.spyOn(globalThis, "fetch");
    const res = await get(planeEnv({ PLANE_BASE_URL: "http://127.0.0.1:8095/" }));
    const text = await res.text();
    expect(JSON.parse(text)).toEqual({ enabled: true, workspaceSlug: "acme", webUrl: "http://127.0.0.1:8095/acme/" });
    expect(text).not.toContain(KEY);
    expect(spy).not.toHaveBeenCalled();
  });
  it("requires sign-in", async () => {
    const res = await app.fetch(new Request("http://localhost/api/admin/integrations/plane"), env);
    expect(res.status).toBeGreaterThanOrEqual(401);
  });
});
