import { env, SELF } from "cloudflare:test";
import type { Role } from "../src/env";

/** Response whose json() is loosely typed, so tests can read fields without casts. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export type ApiResponse = Omit<Response, "json"> & { json(): Promise<any> };

export const BOSS = "boss@example.com";

/** Calls the Worker. `as` signs in through the dev-only identity header. */
export function api(path: string, init: RequestInit & { as?: string } = {}): Promise<ApiResponse> {
  const { as, ...rest } = init;
  const headers = new Headers(rest.headers);
  if (as) headers.set("X-Dev-User-Email", as);
  const method = (rest.method ?? "GET").toUpperCase();
  const mutating = method !== "GET" && method !== "HEAD";
  if ((rest.body !== undefined || mutating) && !headers.has("content-type") && !(rest.body instanceof FormData)) headers.set("content-type", "application/json");
  // The dev identity header is honored only on loopback hosts.
  return SELF.fetch(`http://localhost${path}`, { ...rest, headers });
}

let seq = 0;

/** Inserts an active user directly and returns its id. */
export async function seedUser(email: string, role: Role, name = email.split("@")[0]): Promise<string> {
  const id = `U${String(++seq).padStart(25, "0")}`;
  const now = new Date().toISOString();
  await env.DB.prepare(
    "INSERT INTO users (id, tenant_id, email, name, role, active, created_at, updated_at) VALUES (?, 't_default', ?, ?, ?, 1, ?, ?)",
  ).bind(id, email, name, role, now, now).run();
  return id;
}

export async function auditActions(): Promise<string[]> {
  const { results } = await env.DB.prepare("SELECT action FROM audit_log ORDER BY rowid").all<{ action: string }>();
  return results.map((r) => r.action);
}
