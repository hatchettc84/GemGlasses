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
