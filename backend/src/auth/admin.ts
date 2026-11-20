import type { MiddlewareHandler } from "hono";
import { TENANT_ID, type AdminUser, type AppEnv, type Env, type Role } from "../env";
import { ApiError } from "../lib/errors";
import { auditStmt, SYSTEM_ACTOR } from "../lib/audit";
import { nowIso, ulid } from "../lib/ids";
import { accessKeys, normalizeTeamDomain, verifyAccessJwt } from "./access-jwt";

/** Who is calling: the dev header (dev only) or a verified Cloudflare Access JWT. */
export async function identityEmail(req: Request, env: Env): Promise<string | null> {
  if (env.ENVIRONMENT === "dev") {
    const dev = req.headers.get("X-Dev-User-Email");
    if (dev) return dev.trim().toLowerCase();
  }
  const jwt = req.headers.get("Cf-Access-Jwt-Assertion");
  if (!jwt || !env.ACCESS_TEAM_DOMAIN || !env.ACCESS_AUD) return null;
  try {
    const team = normalizeTeamDomain(env.ACCESS_TEAM_DOMAIN);
    return await verifyAccessJwt(jwt, accessKeys(team), team, env.ACCESS_AUD);
  } catch (err) {
    console.warn("access jwt rejected", String(err));
    return null;
  }
}

interface UserRow {
  id: string;
  tenant_id: string;
  email: string;
  name: string;
  role: Role;
}

const toAdminUser = (r: UserRow): AdminUser => ({ id: r.id, tenantId: r.tenant_id, email: r.email, name: r.name, role: r.role });

/** Loads the active user; creates the bootstrap admin when no admin exists yet. */
async function loadUser(env: Env, email: string): Promise<AdminUser | null> {
  const row = await env.DB.prepare(
    "SELECT id, tenant_id, email, name, role FROM users WHERE tenant_id = ? AND email = ? AND active = 1",
  ).bind(TENANT_ID, email).first<UserRow>();
  if (row) return toAdminUser(row);

  const bootstrap = env.BOOTSTRAP_ADMIN_EMAIL?.trim().toLowerCase();
  if (!bootstrap || bootstrap !== email) return null;
  const admins = await env.DB.prepare("SELECT COUNT(*) AS n FROM users WHERE tenant_id = ? AND role = 'admin'")
    .bind(TENANT_ID).first<{ n: number }>();
  if ((admins?.n ?? 0) > 0) return null;

  const now = nowIso();
  const created: UserRow = { id: ulid(), tenant_id: TENANT_ID, email, name: email.split("@")[0], role: "admin" };
  await env.DB.batch([
    env.DB.prepare(
      "INSERT INTO users (id, tenant_id, email, name, role, active, created_at, updated_at) VALUES (?, ?, ?, ?, 'admin', 1, ?, ?)",
    ).bind(created.id, TENANT_ID, email, created.name, now, now),
    auditStmt(env.DB, SYSTEM_ACTOR, "user.bootstrapped", "user", created.id, null, { email, role: "admin" }),
  ]);
  return toAdminUser(created);
}

export const adminAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const email = await identityEmail(c.req.raw, c.env);
  if (!email) throw new ApiError(401, "unauthenticated", "Sign in to use the BobAI console.");
  const user = await loadUser(c.env, email);
  if (!user) throw new ApiError(403, "not_a_user", "Your account is not active in BobAI. Ask an admin to add you.");
  c.set("user", user);
  await next();
};

export function requireRole(...roles: Role[]): MiddlewareHandler<AppEnv> {
  return async (c, next) => {
    if (!roles.includes(c.get("user").role)) {
      throw new ApiError(403, "forbidden", "You don't have permission to do that.");
    }
    await next();
  };
}
