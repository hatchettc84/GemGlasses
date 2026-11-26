import { Fragment, useCallback, useEffect, useState, type FormEvent } from "react";
import { documentFileUrl, useDocumentPages, useDocuments, useMe, useReviewDocument, useUploadDocument } from "../api/hooks";
import type { ManualDoc } from "../api/types";

const message = (err: unknown) => (err instanceof Error ? err.message : "Something went wrong. Try again.");
const STATUS_LABEL = { draft: "Draft", approved: "Approved", revoked: "Revoked" } as const;

export function ManualsPage() {
  const me = useMe();
  const role = me.data?.role;
  const canView = role === "admin" || role === "dispatcher" || role === "reviewer";
  const canReview = role === "admin" || role === "reviewer";
  const docs = useDocuments(canView);

  if (me.isPending) return <p>Loading…</p>;
  if (me.error) return <p role="alert">{message(me.error)}</p>;
  if (!canView) return <p role="alert">Manuals are managed from the office. Ask an admin if you need access.</p>;

  return (
    <section>
      <h1>Manuals</h1>
      <p className="muted">Technicians only see approved manuals.</p>
      {canReview ? <UploadForm /> : <p className="muted">Only admins and reviewers can upload or approve manuals.</p>}
      {docs.error ? <p role="alert">{message(docs.error)}</p> : docs.isPending ? <p>Loading…</p> : (
        <DocumentsTable docs={docs.data.documents} canReview={canReview} />
      )}
    </section>
  );
}

function UploadForm() {
  const upload = useUploadDocument();
  const [title, setTitle] = useState("");
  const [revision, setRevision] = useState("");
  const [models, setModels] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [fileKey, setFileKey] = useState(0);

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (!file) return;
    const form = new FormData();
    form.set("file", file);
    form.set("title", title);
    form.set("revision", revision);
    form.set("models", models);
    upload.mutate(form, {
      onSuccess: () => { setTitle(""); setRevision(""); setModels(""); setFile(null); setFileKey((k) => k + 1); },
    });
  };

  return (
    <form className="card row" onSubmit={submit}>
      <div className="field">
        <label htmlFor="doc-file">File (PDF or text)</label>
        <input key={fileKey} id="doc-file" type="file" accept=".pdf,.txt,application/pdf,text/plain"
          onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
      </div>
      <div className="field">
        <label htmlFor="doc-title">Title</label>
        <input id="doc-title" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="doc-revision">Revision</label>
        <input id="doc-revision" value={revision} maxLength={80} onChange={(e) => setRevision(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="doc-models">Models (comma separated)</label>
        <input id="doc-models" value={models} onChange={(e) => setModels(e.target.value)} required />
      </div>
      <button type="submit" disabled={upload.isPending || !file}>Upload manual</button>
      {upload.error && <p role="alert">{message(upload.error)}</p>}
    </form>
  );
}

function DocumentsTable({ docs, canReview }: { docs: ManualDoc[]; canReview: boolean }) {
  const review = useReviewDocument();
  const [confirming, setConfirming] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  // Drafts whose extracted text the reviewer has opened: only those can be approved.
  const [read, setRead] = useState<ReadonlySet<string>>(new Set());
  // Stable, so DocumentText's effect runs when its text loads, not on every render.
  const markRead = useCallback((id: string) => setRead((r) => (r.has(id) ? r : new Set(r).add(id))), []);
  if (docs.length === 0) return <p className="muted">No manuals yet.</p>;
  return (
    <>
      {review.error && <p role="alert">{message(review.error)}</p>}
      <table>
        <thead><tr><th>Title</th><th>Revision</th><th>Models</th><th>Status</th><th><span className="visually-hidden">Actions</span></th></tr></thead>
        <tbody>
          {docs.map((d) => (
            <Fragment key={d.id}>
              <tr>
                <td>{d.title}</td>
                <td>{d.revision}</td>
                <td>{d.models.join(", ")}</td>
                <td>{STATUS_LABEL[d.status]}</td>
                <td>
                  <span className="confirm">
                    <button type="button" className="secondary" aria-expanded={open === d.id} aria-controls={`text-${d.id}`}
                      onClick={() => setOpen(open === d.id ? null : d.id)}>
                      {open === d.id ? "Hide text" : "View text"}
                    </button>
                    <a href={documentFileUrl(d.id)} download={d.filename}>Download original</a>
                  </span>
                  {canReview && d.status !== "revoked" && (confirming === d.id ? (
                    <span className="confirm" role="group" aria-label={`Confirm revoking ${d.title}`}>
                      <span>Revoke {d.title}? Phones stop using it.</span>
                      <button type="button" className="danger" disabled={review.isPending}
                        onClick={() => review.mutate({ id: d.id, status: "revoked", version: d.version }, { onSettled: () => setConfirming(null) })}>
                        Yes, revoke
                      </button>
                      <button type="button" className="secondary" onClick={() => setConfirming(null)}>Cancel</button>
                    </span>
                  ) : (
                    <span className="confirm">
                      {d.status === "draft" && (
                        <button type="button" disabled={review.isPending || !read.has(d.id)}
                          title={read.has(d.id) ? undefined : "Open the text first: approve only what you have read."}
                          onClick={() => review.mutate({ id: d.id, status: "approved", version: d.version })}>
                          Approve
                        </button>
                      )}
                      <button type="button" className="danger" disabled={review.isPending} onClick={() => setConfirming(d.id)}>Revoke</button>
                    </span>
                  ))}
                </td>
              </tr>
              {open === d.id && (
                <tr>
                  <td colSpan={5}>
                    <DocumentText doc={d} onLoaded={markRead} />
                  </td>
                </tr>
              )}
            </Fragment>
          ))}
        </tbody>
      </table>
    </>
  );
}

/** The page text Bob will quote, exactly as extracted; reviewers read it before approving. */
function DocumentText({ doc, onLoaded }: { doc: ManualDoc; onLoaded: (id: string) => void }) {
  const pages = useDocumentPages(doc.id, doc.version);
  const loaded = pages.isSuccess;
  useEffect(() => {
    if (loaded) onLoaded(doc.id);
  }, [loaded, onLoaded, doc.id]);
  return (
    <section id={`text-${doc.id}`} className="card" aria-label={`Extracted text of ${doc.title}`}>
      <h2>Extracted text: {doc.title} rev {doc.revision}</h2>
      <p className="muted">This is the text Bob will quote. Compare it with the original file before approving.</p>
      {pages.error ? <p role="alert">{message(pages.error)}</p> : pages.isPending ? <p>Loading text…</p> : (
        pages.data.pages.length === 0 ? <p className="muted">This document has no extracted text.</p> : (
          pages.data.pages.map((p) => (
            <article key={p.number}>
              <h3>Page {p.number}{p.heading ? ` — ${p.heading}` : ""}</h3>
              <pre className="page-text">{p.body}</pre>
            </article>
          ))
        )
      )}
    </section>
  );
}
