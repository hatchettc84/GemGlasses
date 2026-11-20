import { SELF } from "cloudflare:test";

export const BOSS = "boss@example.com";

/** Calls the Worker. `as` signs in through the dev-only identity header. */
export function api(path: string, init: RequestInit & { as?: string } = {}): Promise<Response> {
  const { as, ...rest } = init;
  const headers = new Headers(rest.headers);
  if (as) headers.set("X-Dev-User-Email", as);
  if (rest.body !== undefined && !headers.has("content-type")) headers.set("content-type", "application/json");
  return SELF.fetch(`https://bobai.test${path}`, { ...rest, headers });
}
