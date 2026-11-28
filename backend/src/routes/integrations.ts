import { Hono } from "hono";
import type { AppEnv } from "../env";
import { planeConfig } from "../lib/plane";

export const integrations = new Hono<AppEnv>();

/** Any signed-in office role. Does not call Plane and never returns the API key. */
integrations.get("/integrations/plane", (c) => {
  const cfg = planeConfig(c.env);
  return c.json({
    enabled: cfg !== null,
    workspaceSlug: cfg?.workspaceSlug ?? null,
    webUrl: cfg ? `${cfg.baseUrl}/${encodeURIComponent(cfg.workspaceSlug)}/` : null,
  });
});
