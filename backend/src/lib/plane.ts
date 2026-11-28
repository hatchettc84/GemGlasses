import type { Env } from "../env";
import { ApiError } from "./errors";

/** Adapter for a self-hosted Plane instance (API v1): job reads, plus the write helpers the notes mirror uses. */
export interface PlaneConfig {
  baseUrl: string;
  apiKey: string;
  workspaceSlug: string;
  /** Empty = every project in the workspace. */
  projectIds: string[];
}

export interface JobRow {
  id: string;
  tenant_id: string;
  title: string;
  site: string;
  description: string;
  assigned_user_id: string;
  assigned_name: string;
  status: "open" | "in_progress" | "completed";
  due_at: string | null;
  revision: number;
  created_by: string;
  created_at: string;
  updated_at: string;
}

export interface TaskRow {
  id: string;
  tenant_id: string;
  job_id: string;
  title: string;
  completed: 0 | 1;
  revision: number;
  created_at: string;
  updated_at: string;
}

const MAX_PAGES = 20;
const PLANE_TIMEOUT_MS = 8000;
const MAX_JOBS = 500;
const MAX_TASKS = 5000;
const MAX_DESCRIPTION = 2000;
export const PLANE_ID_PREFIX = "plane-";

export function planeConfig(env: Env): PlaneConfig | null {
  const baseUrl = env.PLANE_BASE_URL?.trim();
  const apiKey = env.PLANE_API_KEY?.trim();
  const workspaceSlug = env.PLANE_WORKSPACE_SLUG?.trim();
  if (!baseUrl || !apiKey || !workspaceSlug) return null;
  const projectIds = (env.PLANE_PROJECT_IDS ?? "").split(",").map((s) => s.trim()).filter(Boolean);
  return { baseUrl: baseUrl.replace(/\/+$/, ""), apiKey, workspaceSlug, projectIds };
}

function unavailable(): ApiError {
  return new ApiError(502, "plane_unavailable", "Jobs are managed in Plane, which could not be reached. Try again shortly.");
}

/** One GET against Plane. Logs status only (never the key or body). */
async function planeGet(cfg: PlaneConfig, path: string): Promise<unknown> {
  const url = `${cfg.baseUrl}/api/v1/workspaces/${encodeURIComponent(cfg.workspaceSlug)}/${path}`;
  // For logs: the path only (no query string, never the key or body).
  const where = path.split("?")[0];
  const started = Date.now();
  let res: Response;
  try {
    res = await fetch(url, {
      headers: { "X-API-Key": cfg.apiKey, Accept: "application/json" },
      // A stalled Plane must surface as plane_unavailable, not hang the phone's sync.
      signal: AbortSignal.timeout(PLANE_TIMEOUT_MS),
    });
  } catch (e) {
    const kind = e instanceof Error ? e.name : "unknown";
    console.error(`plane request failed: ${kind} after ${Date.now() - started} ms (${where})`);
    throw unavailable();
  }
  if (!res.ok) {
    console.error(`plane request failed: status ${res.status} (${where})`);
    throw unavailable();
  }
  console.log(`plane ${res.status} ${Date.now() - started} ms (${where})`);
  try {
    return await res.json();
  } catch {
    console.error("plane request failed: invalid json");
    throw unavailable();
  }
}

export interface PlaneResult {
  status: number;
  /** Parsed JSON body, or null when there is none / it is not JSON. */
  data: unknown;
}

/**
 * One write (or probe) against Plane that hands back the HTTP status instead of throwing on 4xx/5xx,
 * so callers can treat 404/409 as answers. Throws plane_unavailable only when no response arrived
 * (network error or the timeout). Logs method, path, status and duration only — never the key or body.
 */
export async function planeSend(
  cfg: PlaneConfig,
  method: "GET" | "POST" | "PATCH" | "DELETE",
  path: string,
  body?: unknown,
): Promise<PlaneResult> {
  const url = `${cfg.baseUrl}/api/v1/workspaces/${encodeURIComponent(cfg.workspaceSlug)}/${path}`;
  const where = path.split("?")[0];
  const started = Date.now();
  const headers: Record<string, string> = { "X-API-Key": cfg.apiKey, Accept: "application/json" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let res: Response;
  try {
    res = await fetch(url, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: AbortSignal.timeout(PLANE_TIMEOUT_MS),
    });
  } catch (e) {
    const kind = e instanceof Error ? e.name : "unknown";
    console.error(`plane ${method} failed: ${kind} after ${Date.now() - started} ms (${where})`);
    throw unavailable();
  }
  const log = res.ok || res.status === 404 || res.status === 409 ? console.log : console.error;
  log(`plane ${method} ${res.status} ${Date.now() - started} ms (${where})`);
  let data: unknown = null;
  try {
    const text = await res.text();
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  return { status: res.status, data };
}

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => v !== null && typeof v === "object" && !Array.isArray(v);
const str = (v: unknown): string => (typeof v === "string" ? v : "");

function pageResults(body: unknown): Obj[] {
  const list = Array.isArray(body) ? body : isObj(body) && Array.isArray(body.results) ? body.results : null;
  if (!list) {
    console.error("plane request failed: unexpected shape");
    throw unavailable();
  }
  return list.filter(isObj);
}

/** Reads every page (cap MAX_PAGES), following next_cursor while next_page_results is true. */
export async function planeList(cfg: PlaneConfig, path: string): Promise<Obj[]> {
  const out: Obj[] = [];
  let cursor: string | null = null;
  for (let page = 0; page < MAX_PAGES; page++) {
    const sep = path.includes("?") ? "&" : "?";
    const body = await planeGet(cfg, cursor ? `${path}${sep}cursor=${encodeURIComponent(cursor)}` : path);
    out.push(...pageResults(body));
    if (!isObj(body) || body.next_page_results !== true || typeof body.next_cursor !== "string" || !body.next_cursor) break;
    cursor = body.next_cursor;
  }
  return out;
}

function statusFor(group: string): JobRow["status"] | null {
  if (group === "cancelled") return null;
  if (group === "started") return "in_progress";
  if (group === "completed") return "completed";
  return "open"; // backlog, unstarted, triage, unknown
}

export async function fetchPlaneJobs(
  cfg: PlaneConfig,
  email: string,
  user: { id: string; name: string; tenantId: string },
): Promise<{ jobs: JobRow[]; tasks: TaskRow[] }> {
  const wanted = email.trim().toLowerCase();
  const members = pageResults(await planeGet(cfg, "members/"));
  const member = members.find((m) => str(m.email).trim().toLowerCase() === wanted);
  const memberId = member ? str(member.id) : "";
  if (!memberId) return { jobs: [], tasks: [] };

  let projects = (await planeList(cfg, "projects/")).filter((p) => str(p.id));
  if (cfg.projectIds.length) projects = projects.filter((p) => cfg.projectIds.includes(str(p.id)));

  const perProject = await Promise.all(
    projects.map(async (p) => {
      const pid = encodeURIComponent(str(p.id));
      const [states, items] = await Promise.all([
        planeList(cfg, `projects/${pid}/states/`),
        planeList(cfg, `projects/${pid}/work-items/?per_page=100`),
      ]);
      return { site: str(p.name), states, items };
    }),
  );

  const jobs: JobRow[] = [];
  const tasks: TaskRow[] = [];
  for (const { site, states, items } of perProject) {
    const groupOf = new Map(states.map((s) => [str(s.id), str(s.group)]));
    const live = items.filter((i) => str(i.id) && statusFor(groupOf.get(str(i.state)) ?? "") !== null);
    const jobIds = new Set<string>();
    for (const i of live) {
      const assignees = Array.isArray(i.assignees) ? i.assignees : [];
      if (i.parent || !assignees.includes(memberId)) continue;
      jobIds.add(str(i.id));
      jobs.push({
        id: PLANE_ID_PREFIX + str(i.id),
        tenant_id: user.tenantId,
        title: str(i.name),
        site,
        description: str(i.description_stripped).trim().slice(0, MAX_DESCRIPTION),
        assigned_user_id: user.id,
        assigned_name: user.name,
        status: statusFor(groupOf.get(str(i.state)) ?? "")!,
        due_at: str(i.target_date) || null,
        revision: 1,
        created_by: "plane",
        created_at: str(i.created_at),
        updated_at: str(i.updated_at),
      });
    }
    for (const i of live) {
      if (typeof i.parent !== "string" || !jobIds.has(i.parent)) continue;
      tasks.push({
        id: PLANE_ID_PREFIX + str(i.id),
        tenant_id: user.tenantId,
        job_id: PLANE_ID_PREFIX + i.parent,
        title: str(i.name),
        completed: groupOf.get(str(i.state)) === "completed" ? 1 : 0,
        revision: 1,
        created_at: str(i.created_at),
        updated_at: str(i.updated_at),
      });
    }
  }
  jobs.sort((a, b) => (a.updated_at < b.updated_at ? 1 : a.updated_at > b.updated_at ? -1 : 0));
  const keptJobs = jobs.slice(0, MAX_JOBS);
  const kept = new Set(keptJobs.map((j) => j.id));
  tasks.sort((a, b) => (a.created_at < b.created_at ? -1 : a.created_at > b.created_at ? 1 : 0));
  return { jobs: keptJobs, tasks: tasks.filter((t) => kept.has(t.job_id)).slice(0, MAX_TASKS) };
}
