/**
 * BobAI API Worker.
 *
 *   POST /token, POST /places  legacy phone endpoints (app secret or device key)
 *   /api/admin/*               console API (Cloudflare Access identity + role)
 *   /api/device/*              phone API (per-device key)
 *
 * The Google API key lives only here, as a Worker secret.
 */
import { Hono } from "hono";
import type { AppEnv } from "./env";
import { errorBody, handleError, jsonResponse } from "./lib/errors";
import { adminAuth } from "./auth/admin";
import { adminUsers } from "./routes/admin-users";
import { legacyAuth, mintToken, searchPlaces } from "./legacy";

const app = new Hono<AppEnv>();

app.onError(handleError);
app.notFound(() => jsonResponse(errorBody("not_found", "Not found."), 404));

app.post("/token", legacyAuth, (c) => mintToken(c.env));
app.post("/places", legacyAuth, (c) => searchPlaces(c.req.raw, c.env));

app.use("/api/admin/*", adminAuth);
app.route("/api/admin", adminUsers);

export default app;
