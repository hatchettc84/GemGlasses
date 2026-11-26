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
import { csrfGuard } from "./auth/csrf";
import { adminUsers } from "./routes/admin-users";
import { adminDevices } from "./routes/admin-devices";
import { operations } from "./routes/operations";
import { documents } from "./routes/documents";
import { deviceApi } from "./routes/device";
import { legacyAuth, mintToken, searchPlaces } from "./legacy";

const app = new Hono<AppEnv>();

app.onError(handleError);
app.notFound(() => jsonResponse(errorBody("not_found", "Not found."), 404));

app.post("/token", legacyAuth, (c) => mintToken(c.env));
app.post("/places", legacyAuth, (c) => searchPlaces(c.req.raw, c.env));

app.use("/api/admin/*", csrfGuard, adminAuth);
app.route("/api/admin", adminUsers);
app.route("/api/admin", adminDevices);
app.route("/api/admin", operations);
app.route("/api/admin", documents);
app.route("/api/device", deviceApi);

export default app;
