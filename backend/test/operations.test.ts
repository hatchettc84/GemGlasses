import { env } from "cloudflare:test";
import { describe, it, expect } from "vitest";
import { api, auditActions, BOSS, seedUser } from "./helpers";
import { EMPTY_PDF, TWO_PAGES_PDF } from "./fixtures/pdfs";

const pdfFile = (b64: string, name = "manual.pdf") =>
  new File([Uint8Array.from(atob(b64), (c) => c.charCodeAt(0))], name, { type: "application/pdf" });

async function device(email = "tech@example.com") {
  const userId = await seedUser(email, "technician");
  const code = (await (await api(`/api/admin/users/${userId}/pairing-code`, { as: BOSS, method: "POST" })).json()).code;
  const paired = await (await api("/api/device/pair", { method: "POST", body: JSON.stringify({ code, label: email }) })).json();
  return { userId, headers: { Authorization: `Bearer ${paired.deviceKey}` }, deviceId: paired.deviceId as string };
}

const post = (path: string, body: unknown, as = BOSS) =>
  api(`/api/admin${path}`, { as, method: "POST", body: JSON.stringify(body) });
const patch = (path: string, body: unknown, as = BOSS) =>
  api(`/api/admin${path}`, { as, method: "PATCH", body: JSON.stringify(body) });
const devicePatch = (path: string, headers: Record<string, string>, body: unknown) =>
  api(`/api/device${path}`, { headers, method: "PATCH", body: JSON.stringify(body) });

/** Multipart upload; the admin CSRF guard needs an Origin equal to the request origin. */
function upload(fields: Record<string, string>, file: File, as = BOSS) {
  const form = new FormData();
  for (const [k, v] of Object.entries(fields)) form.set(k, v);
  form.set("file", file);
  return api("/api/admin/documents", { as, method: "POST", body: form, headers: { Origin: "http://localhost" } });
}
const manual = () =>
  upload(
    { title: "Pump manual", revision: "B", models: "P-100, P-200" },
    new File(["Turn the gas valve to OFF before opening the housing."], "manual.txt", { type: "text/plain" }),
  );

describe("assigned work", () => {
  it("dispatcher creates and assigns a job; a phone sees only its own; stale updates are 409", async () => {
    const first = await device();
    const other = await device("other@example.com");
    await seedUser("dispatch@example.com", "dispatcher");
    const created = await post("/jobs", { title: "Inspect pump", site: "Plant 2", assignedUserId: first.userId }, "dispatch@example.com");
    expect(created.status).toBe(201);
    const { job } = await created.json();
    expect(job).toMatchObject({ status: "open", revision: 1, assigned_user_id: first.userId });
    expect((await post(`/jobs/${job.id}/tasks`, { title: "Check rating plate" }, "dispatch@example.com")).status).toBe(201);

    const work = await (await api("/api/device/jobs", { headers: first.headers })).json();
    expect(work.jobs).toHaveLength(1);
    expect(work.tasks).toHaveLength(1);
    expect((await (await api("/api/device/jobs", { headers: other.headers })).json()).jobs).toHaveLength(0);

    const task = work.tasks[0];
    expect((await devicePatch(`/tasks/${task.id}`, other.headers, { revision: task.revision, completed: true })).status).toBe(409);
    expect((await devicePatch(`/tasks/${task.id}`, first.headers, { revision: task.revision, completed: true })).status).toBe(200);
    const stale = await devicePatch(`/tasks/${task.id}`, first.headers, { revision: task.revision, completed: false });
    expect(stale.status).toBe(409);
    expect((await stale.json()).error).toBe("stale");

    // Device job update, then the same revision again is stale.
    expect((await devicePatch(`/jobs/${job.id}`, first.headers, { revision: 1, status: "in_progress" })).status).toBe(200);
    expect((await devicePatch(`/jobs/${job.id}`, first.headers, { revision: 1, status: "completed" })).status).toBe(409);

    // Only applied changes are audited.
    const actions = await auditActions();
    expect(actions.filter((a) => a === "task.updated")).toHaveLength(1);
    expect(actions.filter((a) => a === "job.updated")).toHaveLength(1);
  });

  it("admin job PATCH with a stale revision is 409; two racing updates apply once", async () => {
    const tech = await device();
    const { job } = await (await post("/jobs", { title: "J", site: "S", assignedUserId: tech.userId })).json();
    const stale = await patch(`/jobs/${job.id}`, { revision: 99, status: "in_progress" });
    expect(stale.status).toBe(409);
    expect((await stale.json()).error).toBe("stale");

    const [a, b] = await Promise.all([
      patch(`/jobs/${job.id}`, { revision: 1, status: "in_progress" }),
      patch(`/jobs/${job.id}`, { revision: 1, status: "completed" }),
    ]);
    expect([a.status, b.status].sort()).toEqual([200, 409]);
    expect((await auditActions()).filter((x) => x === "job.updated")).toHaveLength(1);
  });

  it("admin PATCH of a missing task is 404; admin job PATCH needs an integer revision", async () => {
    const missing = await patch("/tasks/nope", { completed: true, revision: 1 });
    expect(missing.status).toBe(404);
    expect((await missing.json()).error).toBe("not_found");
    const tech = await device();
    const { job } = await (await post("/jobs", { title: "J", site: "S", assignedUserId: tech.userId })).json();
    for (const body of [{ status: "in_progress" }, { status: "in_progress", revision: "1" }, { status: "in_progress", revision: 1.5 }]) {
      const res = await patch(`/jobs/${job.id}`, body);
      expect(res.status).toBe(400);
      expect((await res.json()).error).toBe("bad_request");
    }
  });

  it("enforces management roles and validates technicians", async () => {
    await seedUser("review@example.com", "reviewer");
    expect((await post("/jobs", { title: "x", site: "x" }, "review@example.com")).status).toBe(403);
    expect((await post("/jobs", { title: "x", site: "x", assignedUserId: "bad" })).status).toBe(400);
  });

  it("an unpaired request cannot read jobs", async () => {
    expect((await api("/api/device/jobs")).status).toBe(401);
  });
});

describe("document approval pipeline", () => {
  it("reviewer uploads a .txt, approves it, and the phone gets it in SourceDocument shape", async () => {
    const tech = await device();
    await seedUser("reviewer@example.com", "reviewer");
    const uploaded = await upload(
      { title: "Pump manual", revision: "B", models: "P-100, P-200" },
      new File(["Turn the gas valve to OFF before opening the housing."], "manual.txt", { type: "text/plain" }),
      "reviewer@example.com",
    );
    expect(uploaded.status).toBe(201);
    const { id } = await uploaded.json();
    expect((await (await api("/api/device/knowledge", { headers: tech.headers })).json()).documents).toHaveLength(0);

    expect((await patch(`/documents/${id}`, { status: "approved", version: 1 }, "reviewer@example.com")).status).toBe(200);
    const body = await (await api("/api/device/knowledge", { headers: tech.headers })).json();
    expect(body.tenantId).toBe("t_default");
    expect(body.expiresAt).toBeGreaterThan(body.generatedAt);
    expect(body.documents).toHaveLength(1);
    expect(body.documents[0]).toMatchObject({
      id,
      packId: "office-approved",
      approvalStatus: "APPROVED",
      isMock: false,
      revision: "B",
      applicableModels: ["P-100", "P-200"],
    });
    expect(body.documents[0].pages[0]).toEqual({
      number: 1,
      heading: expect.any(String),
      body: expect.stringContaining("Turn the gas valve to OFF"),
    });
    const file = await api(`/api/device/documents/${id}/file`, { headers: tech.headers });
    expect(file.status).toBe(200);
    expect(await file.text()).toContain("gas valve");

    // A stale version cannot revoke; the current one can, and the document leaves /knowledge.
    expect((await patch(`/documents/${id}`, { status: "revoked", version: 1 })).status).toBe(409);
    expect((await patch(`/documents/${id}`, { status: "revoked", version: 2 })).status).toBe(200);
    expect((await (await api("/api/device/knowledge", { headers: tech.headers })).json()).documents).toHaveLength(0);
    expect((await api(`/api/device/documents/${id}/file`, { headers: tech.headers })).status).toBe(404);

    const actions = await auditActions();
    expect(actions).toContain("document.uploaded");
    expect(actions.filter((a) => a === "document.reviewed")).toHaveLength(2);
  });

  it("admin can download the original file", async () => {
    const { id } = await (await manual()).json();
    const res = await api(`/api/admin/documents/${id}/file`, { as: BOSS });
    expect(res.status).toBe(200);
    expect(await res.text()).toContain("gas valve");
  });

  it("a technician cannot upload or list documents", async () => {
    await seedUser("tech2@example.com", "technician");
    const res = await upload(
      { title: "x", revision: "1", models: "X" },
      new File(["hello"], "a.txt", { type: "text/plain" }),
      "tech2@example.com",
    );
    expect(res.status).toBe(403);
    expect((await api("/api/admin/documents", { as: "tech2@example.com" })).status).toBe(403);
  });

  it("does not approve a document that has no source page text", async () => {
    const { id } = await (await manual()).json();
    await env.DB.prepare("UPDATE documents SET pages_json='[]' WHERE id=?").bind(id).run();
    const approve = await patch(`/documents/${id}`, { status: "approved", version: 1 });
    expect(approve.status).toBe(400);
    expect((await approve.json()).error).toBe("source_text_required");
  });

  it("extracts page text from an uploaded PDF without pasted text", async () => {
    const res = await upload({ title: "Manual", revision: "1", models: "X" }, pdfFile(TWO_PAGES_PDF));
    expect(res.status).toBe(201);
    const { id } = await res.json();
    const row = await env.DB.prepare("SELECT pages_json FROM documents WHERE id=?").bind(id).first<{ pages_json: string }>();
    const pages = JSON.parse(row!.pages_json);
    expect(pages).toHaveLength(2);
    expect(pages[0]).toMatchObject({ number: 1 });
    expect(pages[0].body).toContain("torque 12 Nm");
    expect(pages[1].body).toContain("reset the breaker");
    expect((await patch(`/documents/${id}`, { status: "approved", version: 1 })).status).toBe(200);
  });

  it("rejects a PDF with no text layer (422) and stores nothing", async () => {
    const res = await upload({ title: "Scan", revision: "1", models: "X" }, pdfFile(EMPTY_PDF, "scan.pdf"));
    expect(res.status).toBe(422);
    const body = await res.json();
    expect(body.error).toBe("no_text_layer");
    expect(body.message).toContain("scanned manuals are not supported yet");
    expect((await env.DB.prepare("SELECT COUNT(*) AS n FROM documents").first<{ n: number }>())!.n).toBe(0);
  });

  it("keeps pasted text for a text-less PDF and rejects an unreadable PDF", async () => {
    const pasted = await upload({ title: "Scan", revision: "1", models: "X", pageText: "Typed from the scan." }, pdfFile(EMPTY_PDF, "scan.pdf"));
    expect(pasted.status).toBe(201);
    const bad = await upload({ title: "Bad", revision: "1", models: "X" }, new File(["%PDF-1.4 not really"], "bad.pdf", { type: "application/pdf" }));
    expect(bad.status).toBe(400);
    expect((await bad.json()).error).toBe("invalid_file");
  });

  it("only allows draft to approved, draft to revoked and approved to revoked", async () => {
    const bad = async (id: string, status: string, version: number) => {
      const res = await patch(`/documents/${id}`, { status, version });
      expect(res.status).toBe(409);
      const body = await res.json();
      expect(body.error).toBe("bad_transition");
      expect(typeof body.message).toBe("string");
    };
    const a = (await (await manual()).json()).id;
    await bad(a, "draft", 1); // same -> same
    expect((await patch(`/documents/${a}`, { status: "approved", version: 1 })).status).toBe(200);
    await bad(a, "approved", 2);
    await bad(a, "draft", 2); // approved -> draft
    expect((await patch(`/documents/${a}`, { status: "revoked", version: 2 })).status).toBe(200);
    await bad(a, "approved", 3); // revoked -> approved
    await bad(a, "revoked", 3);
    await bad(a, "draft", 3);
    const d = (await (await manual()).json()).id;
    expect((await patch(`/documents/${d}`, { status: "revoked", version: 1 })).status).toBe(200);
    // The status is also guarded in the SQL, so a raced change cannot be overwritten.
    const e = (await (await manual()).json()).id;
    await env.DB.prepare("UPDATE documents SET status='revoked' WHERE id=?").bind(e).run();
    expect((await patch(`/documents/${e}`, { status: "approved", version: 1 })).status).toBe(409);
    expect((await env.DB.prepare("SELECT status FROM documents WHERE id=?").bind(e).first<{ status: string }>())!.status).toBe("revoked");
  });

  it("serves device files with nosniff, no-store and an explicit text charset", async () => {
    const tech = await device();
    const { id } = await (await manual()).json();
    await patch(`/documents/${id}`, { status: "approved", version: 1 });
    const res = await api(`/api/device/documents/${id}/file`, { headers: tech.headers });
    expect(res.headers.get("x-content-type-options")).toBe("nosniff");
    expect(res.headers.get("cache-control")).toBe("no-store");
    expect(res.headers.get("content-type")).toBe("text/plain; charset=utf-8");
    await res.arrayBuffer();
    const pdf = await (await upload({ title: "P", revision: "1", models: "X" }, pdfFile(TWO_PAGES_PDF))).json();
    await patch(`/documents/${pdf.id}`, { status: "approved", version: 1 });
    const pres = await api(`/api/device/documents/${pdf.id}/file`, { headers: tech.headers });
    expect(pres.headers.get("content-type")).toBe("application/pdf");
    expect(pres.headers.get("x-content-type-options")).toBe("nosniff");
    await pres.arrayBuffer();
  });

  it("rejects unsupported file types", async () => {
    const res = await upload({ title: "x", revision: "1", models: "X" }, new File(["x"], "a.exe"));
    expect(res.status).toBe(400);
  });

  it("an unpaired request cannot read knowledge", async () => {
    expect((await api("/api/device/knowledge")).status).toBe(401);
    expect((await api("/api/device/knowledge", { headers: { Authorization: "Bearer nope" } })).status).toBe(401);
  });

  it("stores the uploaded bytes in R2", async () => {
    const { id } = await (await manual()).json();
    const row = await env.DB.prepare("SELECT storage_key FROM documents WHERE id = ?").bind(id).first<{ storage_key: string }>();
    expect(await env.DOCUMENTS.head(row!.storage_key)).not.toBeNull();
  });
});
