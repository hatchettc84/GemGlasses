export type Role = "admin" | "reviewer" | "dispatcher" | "technician";
export const ROLES: Role[] = ["admin", "reviewer", "dispatcher", "technician"];

export interface Me { id: string; email: string; name: string; role: Role }
export interface User { id: string; email: string; name: string; role: Role; active: boolean; createdAt: string; updatedAt: string }
export interface Device {
  id: string;
  label: string;
  pairedAt: string;
  lastSeenAt: string | null;
  revokedAt: string | null;
  user: { id: string; name: string; email: string };
}
export interface PairingCode { code: string; expiresAt: string }

export interface Technician { id: string; name: string; email: string }
export type JobStatus = "open" | "in_progress" | "completed";
export interface Job {
  id: string;
  title: string;
  site: string;
  description: string;
  assigned_user_id: string | null;
  assigned_name: string | null;
  status: JobStatus;
  revision: number;
  due_at: string | null;
}
export interface Task { id: string; job_id: string; title: string; completed: number; revision: number }
export type DocStatus = "draft" | "approved" | "revoked";
export interface ManualDoc {
  id: string;
  title: string;
  revision: string;
  filename: string;
  models: string[];
  status: DocStatus;
  version: number;
  size_bytes: number;
  page_count?: number;
}
export interface DocPage { number: number; heading: string; body: string }
export interface DocPages { id: string; title: string; revision: string; status: DocStatus; version: number; pages: DocPage[] }
export type StickyKind = "note" | "draft";
export interface Sticky {
  id: string;
  kind: StickyKind;
  title: string;
  text: string;
  jobTitle: string | null;
  createdAt: string;
  done: boolean;
  updatedAt: string;
  technician: { id: string; name: string };
}
