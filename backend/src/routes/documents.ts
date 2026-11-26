import { Hono } from "hono";
import { bodyLimit } from "hono/body-limit";
import type { AppEnv } from "../env";
import { requireRole } from "../auth/admin";
import { ApiError, readJson } from "../lib/errors";
import { actorFromUser, auditStmt, guardedUpdate } from "../lib/audit";
import { nowIso, ulid } from "../lib/ids";
import { text } from "./operations";

export const documents = new Hono<AppEnv>();
export type DocumentRow = {
  id: string;
  tenant_id: string;
  title: string;
  revision: string;
  models_json: string;
  pages_json: string;
  filename: string;
  content_type: string;
  size_bytes: number;
  sha256: string;
  storage_key: string;
  status: string;
  version: number;
  updated_at: string;
};
const publicDoc = (d: DocumentRow) => ({
  ...d,
  storage_key: undefined,
  pages_json: undefined,
  models: JSON.parse(d.models_json),
  pages: JSON.parse(d.pages_json),
});
documents.get(
  "/documents",
  requireRole("admin", "dispatcher", "reviewer"),
  async (c) => {
    const { results } = await c.env.DB.prepare(
      "SELECT * FROM documents WHERE tenant_id=? ORDER BY updated_at DESC LIMIT 500",
    )
      .bind(c.get("user").tenantId)
      .all<DocumentRow>();
    return c.json({ documents: results.map(publicDoc) });
  },
);
documents.post(
  "/documents",
  requireRole("admin", "reviewer"),
  bodyLimit({
    maxSize: 21 * 1024 * 1024,
    onError: () => {
      throw new ApiError(413, "too_large", "Upload a file under 20 MB.");
    },
  }),
  async (c) => {
    const u = c.get("user"),
      form = await c.req.formData(),
      file: unknown = form.get("file");
    if (!(file instanceof File) || !file.size || file.size > 20 * 1024 * 1024)
      throw new ApiError(
        400,
        "invalid_file",
        "Choose a PDF or text file under 20 MB.",
      );
    const title = text(form.get("title"), "title"),
      revision = text(form.get("revision"), "revision", 80);
    const filename = file.name.replace(/[^a-zA-Z0-9._ -]/g, "_").slice(0, 150);
    const contentType = filename.toLowerCase().endsWith(".pdf")
      ? "application/pdf"
      : filename.toLowerCase().endsWith(".txt")
        ? "text/plain"
        : null;
    if (!contentType)
      throw new ApiError(
        400,
        "invalid_file",
        "Only PDF and UTF-8 text files are supported.",
      );
    const bytes = await file.arrayBuffer();
    if (
      contentType === "application/pdf" &&
      new TextDecoder().decode(bytes.slice(0, 5)) !== "%PDF-"
    )
      throw new ApiError(400, "invalid_file", "This file is not a PDF.");
    let pages: Array<{ number: number; heading: string; body: string }> = [];
    const pageText = form.get("pageText");
    if (typeof pageText === "string" && pageText.trim()) {
      const number = Number(form.get("pageNumber") || 1);
      if (
        !Number.isInteger(number) ||
        number < 1 ||
        number > 100000 ||
        pageText.length > 100000
      )
        throw new ApiError(
          400,
          "invalid_pages",
          "Provide a valid page number and under 100,000 characters of reviewed source text.",
        );
      pages = [{ number, heading: title, body: pageText.trim() }];
    } else if (contentType === "text/plain") {
      let body: string;
      try {
        body = new TextDecoder("utf-8", {
          fatal: true,
          ignoreBOM: false,
        }).decode(bytes);
      } catch {
        throw new ApiError(400, "invalid_text", "Upload UTF-8 encoded text.");
      }
      if (body.length > 100000)
        throw new ApiError(
          400,
          "invalid_text",
          "Text files are limited to 100,000 characters.",
        );
      pages = [{ number: 1, heading: title, body }];
    }
    const models = String(form.get("models") ?? "")
      .split(",")
      .map((x) => x.trim())
      .filter(Boolean);
    if (
      !models.length ||
      models.length > 100 ||
      models.some((m) => m.length > 120)
    )
      throw new ApiError(
        400,
        "invalid_models",
        "List at least one applicable equipment model, separated by commas.",
      );
    const id = ulid(),
      key = `${u.tenantId}/${id}/${filename}`,
      now = nowIso();
    const hash = Array.from(
      new Uint8Array(await crypto.subtle.digest("SHA-256", bytes)),
    )
      .map((x) => x.toString(16).padStart(2, "0"))
      .join("");
    await c.env.DOCUMENTS.put(key, bytes, { httpMetadata: { contentType } });
    try {
      await c.env.DB.batch([
        c.env.DB.prepare(
          "INSERT INTO documents (id,tenant_id,title,revision,models_json,pages_json,filename,content_type,size_bytes,sha256,storage_key,created_by,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        ).bind(
          id,
          u.tenantId,
          title,
          revision,
          JSON.stringify(models),
          JSON.stringify(pages),
          filename,
          contentType,
          file.size,
          hash,
          key,
          u.id,
          now,
          now,
        ),
        auditStmt(
          c.env.DB,
          actorFromUser(u),
          "document.uploaded",
          "document",
          id,
          null,
          { title, revision, sha256: hash },
        ),
      ]);
    } catch (err) {
      await c.env.DOCUMENTS.delete(key);
      throw err;
    }
    return c.json({ id, status: "draft" }, 201);
  },
);
documents.patch(
  "/documents/:id",
  requireRole("admin", "reviewer"),
  async (c) => {
    const u = c.get("user"),
      b = await readJson<Record<string, unknown>>(c),
      id = c.req.param("id");
    if (!["draft", "approved", "revoked"].includes(String(b.status)))
      throw new ApiError(
        400,
        "invalid_status",
        "Choose draft, approved or revoked.",
      );
    const row = await c.env.DB.prepare(
      "SELECT * FROM documents WHERE tenant_id=? AND id=?",
    )
      .bind(u.tenantId, id)
      .first<DocumentRow>();
    if (!row) throw new ApiError(404, "not_found", "Document not found.");
    if (row.version !== b.version)
      throw new ApiError(
        409,
        "stale",
        "Document changed. Refresh before reviewing.",
      );
    if (b.status === "approved" && !JSON.parse(row.pages_json).length)
      throw new ApiError(
        400,
        "source_text_required",
        "PDF approval for field retrieval requires reviewed page text. Upload a revision with reviewed text and its original page number.",
      );
    const applied = await guardedUpdate(
      c.env.DB,
      "documents",
      "status=?,version=version+1,reviewed_by=?,updated_at=?",
      [b.status, u.id, nowIso()],
      "tenant_id=? AND id=? AND version=?",
      [u.tenantId, id, b.version],
      { actor: actorFromUser(u), action: "document.reviewed", entity: "document", entityId: id, before: { status: row.status }, after: { status: b.status } },
    );
    if (!applied)
      throw new ApiError(
        409,
        "stale",
        "Document changed. Refresh before reviewing.",
      );
    return c.json({ ok: true });
  },
);
documents.get(
  "/documents/:id/file",
  requireRole("admin", "dispatcher", "reviewer"),
  async (c) => {
    const row = await c.env.DB.prepare(
      "SELECT * FROM documents WHERE tenant_id=? AND id=?",
    )
      .bind(c.get("user").tenantId, c.req.param("id"))
      .first<DocumentRow>();
    if (!row) throw new ApiError(404, "not_found", "Document not found.");
    const obj = await c.env.DOCUMENTS.get(row.storage_key);
    if (!obj) throw new ApiError(404, "not_found", "File is unavailable.");
    return new Response(obj.body, {
      headers: {
        "Content-Type": row.content_type,
        "Content-Disposition": `attachment; filename="${row.filename}"`,
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
      },
    });
  },
);
