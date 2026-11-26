import type { MiddlewareHandler } from "hono";
import type { AppEnv } from "../env";
import { ApiError } from "../lib/errors";

const MUTATING = new Set(["POST", "PATCH", "PUT", "DELETE"]);

/**
 * Defense in depth against cross-site requests that ride the console's Access
 * cookie. A mutating request must either carry an Origin equal to this
 * request's own origin, or (no Origin) declare application/json, which a
 * cross-site form cannot send.
 */
export const csrfGuard: MiddlewareHandler<AppEnv> = async (c, next) => {
  if (MUTATING.has(c.req.method)) {
    const origin = c.req.header("Origin");
    const type = (c.req.header("Content-Type") ?? "").split(";")[0].trim().toLowerCase();
    const ok = origin ? origin === new URL(c.req.url).origin : type === "application/json";
    if (!ok) throw new ApiError(403, "bad_origin", "This request did not come from the BobAI console. Reload the page and try again.");
  }
  await next();
};
