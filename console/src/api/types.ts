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
