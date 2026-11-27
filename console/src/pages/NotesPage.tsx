import { useState } from "react";
import { useMe, useStickies } from "../api/hooks";
import type { Sticky, StickyKind } from "../api/types";

const message = (err: unknown) => (err instanceof Error ? err.message : "Something went wrong. Try again.");
const FILTERS: { label: string; kind: StickyKind | undefined }[] = [
  { label: "All", kind: undefined },
  { label: "Notes", kind: "note" },
  { label: "Drafts", kind: "draft" },
];

export function NotesPage() {
  const me = useMe();
  const role = me.data?.role;
  const canView = role === "admin" || role === "dispatcher" || role === "reviewer";
  const [kind, setKind] = useState<StickyKind | undefined>(undefined);
  const stickies = useStickies(canView, kind);

  if (me.isPending) return <p>Loading…</p>;
  if (me.error) return <p role="alert">{message(me.error)}</p>;
  if (!canView) return <p role="alert">Notes are visible to the office. Ask an admin if you need access.</p>;

  return (
    <section>
      <h1>Notes</h1>
      <div className="row" role="group" aria-label="Filter">
        {FILTERS.map((f) => (
          <button key={f.label} type="button" aria-pressed={kind === f.kind} onClick={() => setKind(f.kind)}>
            {f.label}
          </button>
        ))}
      </div>
      {stickies.error ? <p role="alert">{message(stickies.error)}</p> : stickies.isPending ? <p>Loading…</p> : stickies.data.stickies.length === 0 ? (
        <p className="muted">No notes yet. Notes and drafts Bob saves on technicians' phones appear here.</p>
      ) : (
        stickies.data.stickies.map((s) => <StickyCard key={`${s.technician.id}:${s.id}`} sticky={s} />)
      )}
    </section>
  );
}

function StickyCard({ sticky: s }: { sticky: Sticky }) {
  const [copied, setCopied] = useState(false);
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(s.text);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };
  return (
    <article className="card">
      <p>
        <span className="badge">{s.kind === "draft" ? "Draft" : "Note"}</span>
        {s.done ? <> <span className="badge">Done</span></> : null}
      </p>
      <h2>{s.title}</h2>
      <p style={{ whiteSpace: "pre-wrap" }}>{s.text}</p>
      <p className="muted">
        {s.technician.name}
        {s.jobTitle ? ` · ${s.jobTitle}` : ""} · {new Date(s.createdAt).toLocaleString()}
      </p>
      {s.kind === "draft" ? <button type="button" onClick={copy}>{copied ? "Copied" : "Copy"}</button> : null}
    </article>
  );
}
