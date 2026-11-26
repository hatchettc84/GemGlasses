export interface Env {
  DB: D1Database;
  /** Uploaded manuals (R2). */
  DOCUMENTS: R2Bucket;
  /** Google AI Studio API key (secret). */
  GEMINI_API_KEY: string;
  /** Legacy shared secret the app sends in X-App-Secret (secret). */
  /** Legacy shared secret; unset once every phone is paired. */
  TOKEN_APP_SECRET?: string;
  /** "dev" only in .dev.vars and tests; enables X-Dev-User-Email. */
  ENVIRONMENT?: string;
  /** e.g. https://yourteam.cloudflareaccess.com */
  ACCESS_TEAM_DOMAIN?: string;
  /** Access application audience tag. */
  ACCESS_AUD?: string;
  /** Becomes the first admin while no admin exists. */
  BOOTSTRAP_ADMIN_EMAIL?: string;
  /** Self-hosted Plane, e.g. http://127.0.0.1:8095. Plane mode needs this, the key and the slug. */
  PLANE_BASE_URL?: string;
  /** Plane personal access token (secret). Never logged or returned. */
  PLANE_API_KEY?: string;
  /** Plane workspace slug. */
  PLANE_WORKSPACE_SLUG?: string;
  /** Comma-separated Plane project ids; empty = all projects in the workspace. */
  PLANE_PROJECT_IDS?: string;
}

export const TENANT_ID = "t_default";

export const ROLES = ["admin", "reviewer", "dispatcher", "technician"] as const;
export type Role = (typeof ROLES)[number];
export function isRole(value: unknown): value is Role {
  return typeof value === "string" && (ROLES as readonly string[]).includes(value);
}

export interface AdminUser {
  id: string;
  tenantId: string;
  email: string;
  name: string;
  role: Role;
}

export interface DeviceCtx {
  id: string;
  tenantId: string;
  userId: string;
}

export type AppEnv = {
  Bindings: Env;
  Variables: { user: AdminUser; device: DeviceCtx };
};
