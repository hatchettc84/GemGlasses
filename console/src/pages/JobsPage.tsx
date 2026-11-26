import { useState, type FormEvent } from "react";
import { useAddTask, useCreateJob, useJobs, useMe, useSetTaskDone, useTeam, useUpdateJob } from "../api/hooks";
import type { Job, JobStatus, Task, Technician } from "../api/types";

const message = (err: unknown) => (err instanceof Error ? err.message : "Something went wrong. Try again.");
const STATUS_LABEL: Record<JobStatus, string> = { open: "Open", in_progress: "In progress", completed: "Completed" };

export function JobsPage() {
  const me = useMe();
  const role = me.data?.role;
  const canView = role === "admin" || role === "dispatcher" || role === "reviewer";
  const canEdit = role === "admin" || role === "dispatcher";
  const jobs = useJobs(canView);
  const team = useTeam(canEdit);

  if (me.isPending) return <p>Loading…</p>;
  if (me.error) return <p role="alert">{message(me.error)}</p>;
  if (!canView) return <p role="alert">Jobs are managed from the office. Use your paired phone to see your work.</p>;

  return (
    <section>
      <h1>Jobs</h1>
      {canEdit ? <NewJobForm technicians={team.data?.technicians ?? []} /> : <p className="muted">You can view jobs but not change them.</p>}
      {jobs.error ? <p role="alert">{message(jobs.error)}</p> : jobs.isPending ? <p>Loading…</p> : (
        jobs.data.jobs.length === 0 ? <p className="muted">No jobs yet.</p> : (
          jobs.data.jobs.map((job) => (
            <JobCard key={job.id} job={job} tasks={jobs.data.tasks.filter((t) => t.job_id === job.id)} canEdit={canEdit} />
          ))
        )
      )}
    </section>
  );
}

function NewJobForm({ technicians }: { technicians: Technician[] }) {
  const create = useCreateJob();
  const [title, setTitle] = useState("");
  const [site, setSite] = useState("");
  const [assignedUserId, setAssigned] = useState("");
  const [dueAt, setDueAt] = useState("");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate(
      { title, site, description: "", assignedUserId, dueAt: dueAt ? new Date(dueAt).toISOString() : null },
      { onSuccess: () => { setTitle(""); setSite(""); setAssigned(""); setDueAt(""); } },
    );
  };

  return (
    <form className="card row" onSubmit={submit}>
      <div className="field">
        <label htmlFor="job-title">Job title</label>
        <input id="job-title" value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="job-site">Site</label>
        <input id="job-site" value={site} maxLength={200} onChange={(e) => setSite(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="job-tech">Assigned to</label>
        <select id="job-tech" value={assignedUserId} onChange={(e) => setAssigned(e.target.value)}>
          <option value="">Unassigned</option>
          {technicians.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
        </select>
      </div>
      <div className="field">
        <label htmlFor="job-due">Due</label>
        <input id="job-due" type="date" value={dueAt} onChange={(e) => setDueAt(e.target.value)} />
      </div>
      <button type="submit" disabled={create.isPending}>Create job</button>
      {create.error && <p role="alert">{message(create.error)}</p>}
    </form>
  );
}

function JobCard({ job, tasks, canEdit }: { job: Job; tasks: Task[]; canEdit: boolean }) {
  const update = useUpdateJob();
  const addTask = useAddTask();
  const setDone = useSetTaskDone();
  const [taskTitle, setTaskTitle] = useState("");

  const submitTask = (e: FormEvent) => {
    e.preventDefault();
    addTask.mutate({ jobId: job.id, title: taskTitle }, { onSuccess: () => setTaskTitle("") });
  };
  const error = update.error ?? addTask.error ?? setDone.error;

  return (
    <article className="card" aria-label={job.title}>
      <div className="row">
        <h2>{job.title}</h2>
        {canEdit ? (
          <div className="field">
            <label htmlFor={`status-${job.id}`}>Status</label>
            <select id={`status-${job.id}`} value={job.status} disabled={update.isPending}
              onChange={(e) => update.mutate({ id: job.id, revision: job.revision, status: e.target.value as JobStatus })}>
              {(Object.keys(STATUS_LABEL) as JobStatus[]).map((s) => <option key={s} value={s}>{STATUS_LABEL[s]}</option>)}
            </select>
          </div>
        ) : <span>{STATUS_LABEL[job.status]}</span>}
      </div>
      <p className="muted">
        {job.site} · {job.assigned_name ? `Assigned to ${job.assigned_name}` : "Unassigned"}
        {job.due_at ? ` · Due ${new Date(job.due_at).toLocaleDateString()}` : ""}
      </p>
      {tasks.length > 0 && (
        <ul>
          {tasks.map((t) => (
            <li key={t.id}>
              <label>
                <input type="checkbox" checked={t.completed === 1} disabled={!canEdit || setDone.isPending}
                  onChange={(e) => setDone.mutate({ id: t.id, revision: t.revision, completed: e.target.checked })} />
                {" "}{t.title}
              </label>
            </li>
          ))}
        </ul>
      )}
      {canEdit && (
        <form className="row" onSubmit={submitTask}>
          <div className="field">
            <label htmlFor={`task-${job.id}`}>New task for {job.title}</label>
            <input id={`task-${job.id}`} value={taskTitle} maxLength={500} onChange={(e) => setTaskTitle(e.target.value)} required />
          </div>
          <button type="submit" className="secondary" disabled={addTask.isPending}>Add task</button>
        </form>
      )}
      {error && <p role="alert">{message(error)}</p>}
    </article>
  );
}
