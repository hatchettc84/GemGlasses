import { useState, type FormEvent } from "react";
import { useCreateUser, useDevices, useIssuePairingCode, useMe, useRevokeDevice, useUsers } from "../api/hooks";
import { ROLES, type Device, type PairingCode, type Role, type User } from "../api/types";

const message = (err: unknown) => (err instanceof Error ? err.message : "Something went wrong. Try again.");
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "Never");

export function PeoplePage() {
  const me = useMe();
  const isAdmin = me.data?.role === "admin";
  const users = useUsers(isAdmin);
  const devices = useDevices(isAdmin);

  if (me.isPending) return <p>Loading…</p>;
  if (me.error) return <p role="alert">{message(me.error)}</p>;

  return (
    <section>
      <h1>People and phones</h1>
      <p className="muted">Signed in as {me.data.email} ({me.data.role})</p>
      {!isAdmin ? (
        <p role="alert">Only admins can manage people and phones. Ask an admin if you need access.</p>
      ) : (
        <>
          <AddPersonForm />
          <h2>People</h2>
          {users.error ? <p role="alert">{message(users.error)}</p> : users.isPending ? <p>Loading…</p> : <PeopleTable users={users.data?.users ?? []} />}
          <h2>Paired phones</h2>
          {devices.error ? <p role="alert">{message(devices.error)}</p> : devices.isPending ? <p>Loading…</p> : <DevicesTable devices={devices.data?.devices ?? []} />}
        </>
      )}
    </section>
  );
}

function AddPersonForm() {
  const create = useCreateUser();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState<Role>("technician");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate({ email, name, role }, { onSuccess: () => { setEmail(""); setName(""); } });
  };

  return (
    <form className="card row" onSubmit={submit}>
      <div className="field">
        <label htmlFor="new-email">Email</label>
        <input id="new-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="new-name">Name</label>
        <input id="new-name" value={name} onChange={(e) => setName(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="new-role">Role</label>
        <select id="new-role" value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
      </div>
      <button type="submit" disabled={create.isPending}>Add person</button>
      {create.error && <p role="alert">{message(create.error)}</p>}
    </form>
  );
}

function PeopleTable({ users }: { users: User[] }) {
  const issue = useIssuePairingCode();
  const [shown, setShown] = useState<{ user: User; code: PairingCode } | null>(null);

  return (
    <>
      {shown && (
        <div className="card notice" role="status">
          <strong className="code">{shown.code.code}</strong>
          <span>Enter it on {shown.user.name}'s phone under Settings → Office console. It works once and expires at {new Date(shown.code.expiresAt).toLocaleTimeString()}.</span>
          <button type="button" onClick={() => setShown(null)}>Done</button>
        </div>
      )}
      {issue.error && <p role="alert">{message(issue.error)}</p>}
      <table>
        <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th><span className="visually-hidden">Actions</span></th></tr></thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.id}>
              <td>{u.name}</td>
              <td>{u.email}</td>
              <td>{u.role}</td>
              <td>{u.active ? "Active" : "Inactive"}</td>
              <td>
                {u.role === "technician" && u.active && (
                  <button type="button" disabled={issue.isPending}
                    onClick={() => issue.mutate(u.id, { onSuccess: (code) => setShown({ user: u, code }) })}>
                    Pairing code
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function DevicesTable({ devices }: { devices: Device[] }) {
  const revoke = useRevokeDevice();
  const [confirming, setConfirming] = useState<string | null>(null);
  if (devices.length === 0) return <p className="muted">No phones paired yet.</p>;
  return (
    <>
      {revoke.error && <p role="alert">{message(revoke.error)}</p>}
      <table>
        <thead><tr><th>Phone</th><th>Technician</th><th>Paired</th><th>Last seen</th><th><span className="visually-hidden">Actions</span></th></tr></thead>
        <tbody>
          {devices.map((d) => (
            <tr key={d.id}>
              <td>{d.label}</td>
              <td>{d.user.name}</td>
              <td>{when(d.pairedAt)}</td>
              <td>{when(d.lastSeenAt)}</td>
              <td>
                {d.revokedAt ? "Removed" : confirming === d.id ? (
                  <span className="confirm" role="group" aria-label={`Confirm removing ${d.label}`}>
                    <span>Remove {d.label}? It stops working until paired again.</span>
                    <button type="button" className="danger" disabled={revoke.isPending}
                      onClick={() => revoke.mutate(d.id, { onSettled: () => setConfirming(null) })}>
                      Yes, remove
                    </button>
                    <button type="button" className="secondary" onClick={() => setConfirming(null)}>Cancel</button>
                  </span>
                ) : (
                  <button type="button" className="danger" disabled={revoke.isPending}
                    onClick={() => setConfirming(d.id)}>
                    Remove phone
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
