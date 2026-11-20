# BobAI Console Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build step 1 of the management console spec: the API foundation
(D1 schema, Access + device authentication, roles, audit log, error format),
phone pairing end to end, and a minimal console People page to add people and
issue pairing codes.

**Architecture:** The existing `backend/` Cloudflare Worker becomes a Hono app.
Legacy `/token` and `/places` keep working (and also accept a device key). New
`/api/admin/*` routes authenticate with Cloudflare Access (or a dev-only
header) and check the role stored in D1; `/api/device/*` routes authenticate
with a per-phone key. A new `console/` React app talks to `/api/admin/*`. The
Android app gains a pairing card in Settings, stores its device key encrypted
with the Android Keystore, and uses it for `/token`.

**Tech Stack:** Cloudflare Workers, Hono 4.6, D1, jose 5.9, Vitest 2.1 with
`@cloudflare/vitest-pool-workers` 0.5; React 18, Vite 5, TanStack Query 5,
Testing Library; Kotlin/Compose, OkHttp 4.12 + MockWebServer.

Spec: `docs/superpowers/specs/2026-11-20-bobai-management-console-design.md`.
This plan covers spec §12 step 1 only. Steps 2–5 get their own plans.

## Global Constraints

- Roles are exactly `admin`, `reviewer`, `dispatcher`, `technician`; one per person.
- Every write is checked on the server; every admin and device write is recorded in `audit_log`, in the same D1 batch as the change.
- IDs are ULIDs; timestamps are ISO-8601 UTC strings; every table has `tenant_id`; the single launch tenant is `t_default`.
- API errors are JSON `{ "error": <code>, "message": <plain text> }`; internal details go to `console.error` only, never into responses.
- Device keys: 32 random bytes, base64url; only the SHA-256 hex is stored. Pairing codes: 6 digits, SHA-256 stored, 10-minute expiry, single use, issuing a new code voids older unused ones; pairing refused for 10 minutes after 20 wrong codes per tenant.
- `X-Dev-User-Email` is accepted only when `ENVIRONMENT` is exactly `dev`. `ENVIRONMENT` is never set in `wrangler.toml`; only in `.dev.vars` and tests.
- No secrets in the repo. `backend/.dev.vars` and `local.properties` stay gitignored. Never print key values.
- Nothing is deployed to Cloudflare in this plan.
- Copy rule for user-facing text: plain sentences that say what happened and what to do.

## File structure

```
backend/
  package.json                 (modify: deps, scripts)
  wrangler.toml                (modify: D1 binding, nodejs_compat)
  vitest.config.ts             (create)
  migrations/0001_foundation.sql
  src/index.ts                 (rewrite: Hono app assembly only)
  src/env.ts                   Env, roles, context types
  src/legacy.ts                /token + /places (moved from index.ts, errors fixed)
  src/lib/ids.ts               ulid(), nowIso()
  src/lib/crypto.ts            sha256Hex, randomKey, sixDigitCode, timingSafeEqual
  src/lib/errors.ts            ApiError, jsonResponse, handleError, readJson
  src/lib/audit.ts             Actor, auditStmt()
  src/auth/access-jwt.ts       Cloudflare Access JWT verification
  src/auth/admin.ts            identityEmail, adminAuth, requireRole
  src/auth/device.ts           deviceFromBearer, deviceAuth
  src/routes/admin-users.ts    /me, /users, /users/:id, /users/:id/pairing-code
  src/routes/admin-devices.ts  /devices, /devices/:id/revoke
  src/routes/device.ts         /pair, /me
  test/tsconfig.json, test/env.d.ts, test/apply-migrations.ts, test/helpers.ts
  test/*.test.ts
console/
  package.json, tsconfig.json, vite.config.ts, index.html, README.md
  src/main.tsx, src/App.tsx, src/styles.css
  src/api/client.ts, src/api/types.ts, src/api/hooks.ts
  src/pages/PeoplePage.tsx, src/pages/PeoplePage.test.tsx
  src/test/setup.ts
app/ (Android, package com.lpecom.gemglasses)
  bobai/sync/DeviceKeyStore.kt          interface + DevicePairing
  bobai/sync/KeystoreDeviceKeyStore.kt  Android Keystore implementation
  bobai/sync/PairingClient.kt           POST /api/device/pair
  gemini/TokenProvider.kt               (modify: device key auth)
  di/AppModule.kt                       (modify: bind DeviceKeyStore)
  bobai/ui/BobAiViewModel.kt            (modify: pairing state)
  bobai/ui/Screens.kt                   (modify: Office console card)
  test: bobai/sync/PairingClientTest.kt, gemini/TokenProviderTest.kt
```

---

### Task 1: Test harness, Hono app, legacy routes with fixed errors

**Files:**
- Modify: `backend/package.json`, `backend/wrangler.toml`
- Create: `backend/vitest.config.ts`, `backend/migrations/0001_foundation.sql`, `backend/src/env.ts`, `backend/src/lib/errors.ts`, `backend/src/lib/crypto.ts`, `backend/src/legacy.ts`, `backend/src/auth/device.ts` (stub, replaced in Task 4), `backend/test/tsconfig.json`, `backend/test/env.d.ts`, `backend/test/apply-migrations.ts`, `backend/test/helpers.ts`, `backend/test/legacy.test.ts`
- Rewrite: `backend/src/index.ts`

**Interfaces:**
- Produces: `Env`, `AppEnv`, `Role`, `ROLES`, `isRole`, `AdminUser`, `DeviceCtx`, `TENANT_ID` (src/env.ts); `ApiError(status, code, message)`, `jsonResponse(body, status)`, `errorBody(code, message)`, `handleError(err, c)`, `readJson<T>(c)` (src/lib/errors.ts); `sha256Hex(input)`, `randomKey(bytes?)`, `sixDigitCode()`, `timingSafeEqual(a, b)` (src/lib/crypto.ts); `mintToken(env)`, `searchPlaces(req, env)`, `legacyAuth` (src/legacy.ts); `deviceAuth` (src/auth/device.ts, stub); test helpers `api(path, init & { as? })`, `BOSS`.

- [ ] **Step 1: Install dependencies**

Run in `backend/`:
```bash
npm install hono@4.6.14 jose@5.9.6
npm install -D vitest@2.1.8 @cloudflare/vitest-pool-workers@0.5.41
```
Then set `scripts` in `backend/package.json` to:
```json
"scripts": {
  "dev": "wrangler dev",
  "deploy": "wrangler deploy",
  "typecheck": "tsc --noEmit && tsc --noEmit -p test",
  "test": "vitest run",
  "db:migrate:local": "wrangler d1 migrations apply bobai --local"
}
```

- [ ] **Step 2: Configure wrangler**

Replace `backend/wrangler.toml` with:
```toml
name = "gemglasses-backend"
main = "src/index.ts"
compatibility_date = "2024-09-09"
compatibility_flags = ["nodejs_compat"]

[[d1_databases]]
binding = "DB"
database_name = "bobai"
# Local dev and tests ignore this id. Before the first deploy, run
# `npx wrangler d1 create bobai` and put the id it prints here.
database_id = "00000000-0000-0000-0000-000000000000"
migrations_dir = "migrations"

# Secrets (set with `wrangler secret put <NAME>`), never committed:
#   GEMINI_API_KEY        Google AI Studio API key
#   TOKEN_APP_SECRET      legacy shared secret the Android app sends in X-App-Secret
# Plain vars for production (set in the dashboard or with --var):
#   ACCESS_TEAM_DOMAIN    e.g. https://yourteam.cloudflareaccess.com
#   ACCESS_AUD            the Access application's audience tag
#   BOOTSTRAP_ADMIN_EMAIL first admin; used only while no admin exists
#
# Local dev: backend/.dev.vars (gitignored) holds GEMINI_API_KEY,
# TOKEN_APP_SECRET, ENVIRONMENT="dev" and BOOTSTRAP_ADMIN_EMAIL.
# ENVIRONMENT must never be set here.
```

- [ ] **Step 3: Create the first migration (tenants only; Task 2 adds the rest)**

`backend/migrations/0001_foundation.sql`:
```sql
CREATE TABLE tenants (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TEXT NOT NULL
);
INSERT INTO tenants (id, name, created_at)
VALUES ('t_default', 'Default', strftime('%Y-%m-%dT%H:%M:%fZ', 'now'));
```

- [ ] **Step 4: Vitest configuration and test scaffolding**

`backend/vitest.config.ts`:
```ts
import { fileURLToPath } from "node:url";
import { defineWorkersConfig, readD1Migrations } from "@cloudflare/vitest-pool-workers/config";

export default defineWorkersConfig(async () => {
  const migrations = await readD1Migrations(fileURLToPath(new URL("./migrations", import.meta.url)));
  return {
    test: {
      setupFiles: ["./test/apply-migrations.ts"],
      poolOptions: {
        workers: {
          singleWorker: true,
          wrangler: { configPath: "./wrangler.toml" },
          miniflare: {
            bindings: {
              TEST_MIGRATIONS: migrations,
              ENVIRONMENT: "dev",
              TOKEN_APP_SECRET: "test-secret",
              GEMINI_API_KEY: "test-key",
              BOOTSTRAP_ADMIN_EMAIL: "boss@example.com",
            },
          },
        },
      },
    },
  };
});
```
Storage is isolated per test (the pool's default `isolatedStorage: true`), so every test starts from the migrated, empty database.

`backend/test/tsconfig.json`:
```json
{
  "extends": "../tsconfig.json",
  "compilerOptions": {
    "types": ["@cloudflare/workers-types/experimental", "@cloudflare/vitest-pool-workers"]
  },
  "include": ["./**/*.ts", "../src/**/*.ts"]
}
```

`backend/test/env.d.ts`:
```ts
declare module "cloudflare:test" {
  interface ProvidedEnv extends import("../src/env").Env {
    TEST_MIGRATIONS: D1Migration[];
  }
}
```

`backend/test/apply-migrations.ts`:
```ts
import { applyD1Migrations, env } from "cloudflare:test";

await applyD1Migrations(env.DB, env.TEST_MIGRATIONS);
```

`backend/test/helpers.ts`:
```ts
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
```

- [ ] **Step 5: Write the failing legacy tests**

`backend/test/legacy.test.ts`:
```ts
import { fetchMock } from "cloudflare:test";
import { afterEach, beforeAll, describe, expect, it } from "vitest";
import { api } from "./helpers";

const GOOGLE = "https://generativelanguage.googleapis.com";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});
afterEach(() => fetchMock.assertNoPendingInterceptors());

describe("legacy /token", () => {
  it("refuses a request without credentials", async () => {
    const res = await api("/token", { method: "POST" });
    expect(res.status).toBe(401);
    expect(await res.json()).toEqual({ error: "unauthorized", message: "Not authorized." });
  });

  it("refuses a wrong app secret", async () => {
    const res = await api("/token", { method: "POST", headers: { "X-App-Secret": "nope" } });
    expect(res.status).toBe(401);
  });

  it("mints a token with the app secret", async () => {
    fetchMock.get(GOOGLE).intercept({ path: /\/v1beta\/auth_tokens/, method: "POST" })
      .reply(200, { name: "auth_tokens/abc", expireTime: "2026-11-20T12:00:00Z" });
    const res = await api("/token", { method: "POST", headers: { "X-App-Secret": "test-secret" } });
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ token: "auth_tokens/abc", expiresAt: "2026-11-20T12:00:00Z" });
  });

  it("does not leak upstream error text", async () => {
    fetchMock.get(GOOGLE).intercept({ path: /\/v1beta\/auth_tokens/, method: "POST" })
      .reply(403, "API key test-key is invalid; internal detail");
    const res = await api("/token", { method: "POST", headers: { "X-App-Secret": "test-secret" } });
    expect(res.status).toBe(502);
    const text = await res.text();
    expect(text).not.toContain("test-key");
    expect(JSON.parse(text)).toEqual({ error: "token_upstream", message: "The AI service refused the token request. Try again." });
  });

  it("returns JSON 404 for unknown paths", async () => {
    const res = await api("/nope");
    expect(res.status).toBe(404);
    expect(await res.json()).toEqual({ error: "not_found", message: "Not found." });
  });
});
```

- [ ] **Step 6: Run to verify they fail**

Run: `cd backend && npx vitest run test/legacy.test.ts`
Expected: FAIL (the old Worker answers GET `/nope` with `method_not_allowed` and returns upstream `detail` in the 502 body).

- [ ] **Step 7: Implement env, errors, crypto, legacy, index**

`backend/src/env.ts`:
```ts
export interface Env {
  DB: D1Database;
  /** Google AI Studio API key (secret). */
  GEMINI_API_KEY: string;
  /** Legacy shared secret the app sends in X-App-Secret (secret). */
  TOKEN_APP_SECRET: string;
  /** "dev" only in .dev.vars and tests; enables X-Dev-User-Email. */
  ENVIRONMENT?: string;
  /** e.g. https://yourteam.cloudflareaccess.com */
  ACCESS_TEAM_DOMAIN?: string;
  /** Access application audience tag. */
  ACCESS_AUD?: string;
  /** Becomes the first admin while no admin exists. */
  BOOTSTRAP_ADMIN_EMAIL?: string;
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
```

`backend/src/lib/errors.ts`:
```ts
import type { Context } from "hono";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function errorBody(code: string, message: string) {
  return { error: code, message };
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

/** Hono onError: ApiErrors become their JSON body; anything else is logged and hidden. */
export function handleError(err: unknown, _c: Context): Response {
  if (err instanceof ApiError) return jsonResponse(errorBody(err.code, err.message), err.status);
  console.error("unhandled error", err);
  return jsonResponse(errorBody("internal", "Something went wrong. Try again."), 500);
}

export async function readJson<T>(c: Context): Promise<T> {
  try {
    return (await c.req.json()) as T;
  } catch {
    throw new ApiError(400, "bad_json", "The request body is not valid JSON.");
  }
}
```

`backend/src/lib/crypto.ts`:
```ts
export async function sha256Hex(input: string | Uint8Array): Promise<string> {
  const data = typeof input === "string" ? new TextEncoder().encode(input) : input;
  const digest = await crypto.subtle.digest("SHA-256", data);
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, "0")).join("");
}

/** Random secret as base64url without padding. */
export function randomKey(bytes = 32): string {
  const raw = crypto.getRandomValues(new Uint8Array(bytes));
  let bin = "";
  for (const b of raw) bin += String.fromCharCode(b);
  return btoa(bin).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

/** Uniform 6-digit code (rejection sampling avoids modulo bias). */
export function sixDigitCode(): string {
  const limit = 4_294_000_000; // largest multiple of 1e6 below 2^32
  const buf = new Uint32Array(1);
  do crypto.getRandomValues(buf);
  while (buf[0] >= limit);
  return String(buf[0] % 1_000_000).padStart(6, "0");
}

/** Constant-time comparison for equal-length strings. */
export function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
```

`backend/src/auth/device.ts` (stub so `legacy.ts` compiles; Task 4 replaces it):
```ts
import type { MiddlewareHandler } from "hono";
import type { AppEnv } from "../env";
import { ApiError } from "../lib/errors";

// Replaced in Task 4.
export const deviceAuth: MiddlewareHandler<AppEnv> = async () => {
  throw new ApiError(401, "unauthorized", "Not authorized.");
};
```

`backend/src/legacy.ts` (moved from the old `index.ts`; only the auth and the error bodies change):
```ts
import type { MiddlewareHandler } from "hono";
import type { AppEnv, Env } from "./env";
import { ApiError, jsonResponse } from "./lib/errors";
import { timingSafeEqual } from "./lib/crypto";
import { deviceAuth } from "./auth/device";

const GEMINI_API = "https://generativelanguage.googleapis.com/v1beta";
// Keep in sync with Models.GEMINI_LIVE_MODEL in the app.
const LIVE_MODEL = "models/gemini-3.8-live";
const GROUNDING_MODEL = "gemini-2.5-flash";

/** Legacy routes accept the shared app secret or a paired device key. */
export const legacyAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const secret = c.req.header("X-App-Secret") ?? "";
  if (secret && timingSafeEqual(secret, c.env.TOKEN_APP_SECRET)) return next();
  if (c.req.header("Authorization")) return deviceAuth(c, next);
  throw new ApiError(401, "unauthorized", "Not authorized.");
};

/**
 * Exchanges the API key for an ephemeral auth token scoped to the Live API.
 * The token is single-session, short-TTL, and safe to hand to the client.
 */
export async function mintToken(env: Env): Promise<Response> {
  const now = Date.now();
  const body = {
    // ~30 min window to start a session; a single new session may be opened.
    expireTime: new Date(now + 30 * 60_000).toISOString(),
    newSessionExpireTime: new Date(now + 2 * 60_000).toISOString(),
    uses: 1,
    // REST name is bidiGenerateContentSetup ("liveConnectConstraints" is the
    // SDK name and is rejected by the REST API). Verified against the API's
    // own schema validation on 2026-11-18.
    bidiGenerateContentSetup: {
      model: LIVE_MODEL,
      generationConfig: { responseModalities: ["AUDIO"] },
    },
    // Without a mask the token's setup REPLACES the app's setup entirely,
    // dropping its system instruction, tools and voice. Lock only these.
    // sessionResumption stays out so the app's resume handle survives.
    fieldMask: "model,generationConfig.responseModalities",
  };

  const res = await fetch(`${GEMINI_API}/auth_tokens?key=${env.GEMINI_API_KEY}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    console.error("token upstream failed", res.status, await res.text());
    throw new ApiError(502, "token_upstream", "The AI service refused the token request. Try again.");
  }

  const data = (await res.json()) as { name?: string; token?: string; expireTime?: string };
  // The API returns the token as `name` (tokens/xxx) or `token` depending on version.
  const token = data.token ?? data.name;
  if (!token) throw new ApiError(502, "token_missing", "The AI service returned no token. Try again.");

  return jsonResponse({ token, expiresAt: data.expireTime ?? body.expireTime });
}

/**
 * Runs a grounded place search and returns a short spoken summary plus the
 * cited places (title + uri) the app must display per Maps ToS.
 */
export async function searchPlaces(request: Request, env: Env): Promise<Response> {
  let input: { query?: string; latitude?: number; longitude?: number };
  try {
    input = (await request.json()) as typeof input;
  } catch {
    throw new ApiError(400, "bad_json", "The request body is not valid JSON.");
  }
  const { query, latitude, longitude } = input;
  if (!query) throw new ApiError(400, "missing_query", "Say what to search for.");

  const payload: Record<string, unknown> = {
    contents: [{ role: "user", parts: [{ text: query }] }],
    tools: [{ googleMaps: {} }],
    systemInstruction: {
      parts: [
        {
          text:
            "Responda em português, curto, listando 2-4 lugares reais e próximos. " +
            "Não invente estabelecimentos.",
        },
      ],
    },
  };

  if (typeof latitude === "number" && typeof longitude === "number") {
    payload.toolConfig = { retrievalConfig: { latLng: { latitude, longitude } } };
  }

  const res = await fetch(
    `${GEMINI_API}/models/${GROUNDING_MODEL}:generateContent?key=${env.GEMINI_API_KEY}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
    },
  );

  if (!res.ok) {
    console.error("places upstream failed", res.status, await res.text());
    throw new ApiError(502, "places_upstream", "The place search failed. Try again.");
  }

  const data = (await res.json()) as GeminiResponse;
  const candidate = data.candidates?.[0];
  const summary =
    candidate?.content?.parts?.map((p) => p.text ?? "").join(" ").trim() || "Não encontrei lugares.";

  const places = (candidate?.groundingMetadata?.groundingChunks ?? [])
    .map((chunk) => chunk.maps ?? chunk.web)
    .filter((c): c is { title?: string; uri: string } => !!c?.uri)
    .map((c) => ({ title: c.title ?? c.uri, uri: c.uri }));

  return jsonResponse({ summary, places });
}

interface GeminiResponse {
  candidates?: Array<{
    content?: { parts?: Array<{ text?: string }> };
    groundingMetadata?: {
      groundingChunks?: Array<{
        maps?: { title?: string; uri: string };
        web?: { title?: string; uri: string };
      }>;
    };
  }>;
}
```

`backend/src/index.ts` (whole file):
```ts
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
import { legacyAuth, mintToken, searchPlaces } from "./legacy";

const app = new Hono<AppEnv>();

app.onError(handleError);
app.notFound(() => jsonResponse(errorBody("not_found", "Not found."), 404));

app.post("/token", legacyAuth, (c) => mintToken(c.env));
app.post("/places", legacyAuth, (c) => searchPlaces(c.req.raw, c.env));

export default app;
```

- [ ] **Step 8: Run tests and typecheck**

Run: `cd backend && npx vitest run && npm run typecheck`
Expected: 5 passed; typecheck exits 0.

- [ ] **Step 9: Commit**

```bash
git add backend/package.json backend/package-lock.json backend/wrangler.toml backend/vitest.config.ts backend/migrations backend/src backend/test
git commit -m "backend: Hono app, D1 binding, Workers test harness; stop leaking upstream errors"
```

---

### Task 2: Schema, IDs, audit log

**Files:**
- Modify: `backend/migrations/0001_foundation.sql`
- Create: `backend/src/lib/ids.ts`, `backend/src/lib/audit.ts`, `backend/test/lib.test.ts`

**Interfaces:**
- Consumes: `TENANT_ID`, `AdminUser` (src/env.ts); `sha256Hex`, `sixDigitCode`, `randomKey` (src/lib/crypto.ts)
- Produces: `ulid(now?: number): string`, `nowIso(): string`; `interface Actor { type: "user" | "device" | "system"; id: string | null; tenantId: string }`, `actorFromUser(u: AdminUser): Actor`, `SYSTEM_ACTOR: Actor`, `auditStmt(db, actor, action, entity, entityId, before, after): D1PreparedStatement`. Tables `users`, `devices`, `pairing_codes`, `audit_log`.

- [ ] **Step 1: Write the failing tests**

`backend/test/lib.test.ts`:
```ts
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { ulid, nowIso } from "../src/lib/ids";
import { randomKey, sha256Hex, sixDigitCode } from "../src/lib/crypto";
import { auditStmt, SYSTEM_ACTOR } from "../src/lib/audit";

describe("ids", () => {
  it("makes 26-char Crockford ULIDs that sort by time", () => {
    const a = ulid(1_700_000_000_000);
    const b = ulid(1_700_000_000_001);
    expect(a).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(a < b).toBe(true);
  });
  it("formats ISO UTC timestamps", () => {
    expect(nowIso()).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
  });
});

describe("crypto", () => {
  it("hashes with SHA-256", async () => {
    expect(await sha256Hex("abc")).toBe("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
  });
  it("makes 6-digit codes and 43-char keys", () => {
    for (let i = 0; i < 50; i++) expect(sixDigitCode()).toMatch(/^\d{6}$/);
    expect(randomKey()).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });
});

describe("audit", () => {
  it("records the change with before and after JSON", async () => {
    await auditStmt(env.DB, SYSTEM_ACTOR, "test.action", "thing", "id1", { a: 1 }, { a: 2 }).run();
    const row = await env.DB.prepare("SELECT * FROM audit_log").first<Record<string, string>>();
    expect(row).toMatchObject({
      tenant_id: "t_default",
      actor_type: "system",
      action: "test.action",
      entity: "thing",
      entity_id: "id1",
      before_json: '{"a":1}',
      after_json: '{"a":2}',
    });
  });

  it("schema has the foundation tables", async () => {
    const { results } = await env.DB.prepare(
      "SELECT name FROM sqlite_master WHERE type = 'table' AND name IN ('tenants','users','devices','pairing_codes','audit_log') ORDER BY name",
    ).all<{ name: string }>();
    expect(results.map((r) => r.name)).toEqual(["audit_log", "devices", "pairing_codes", "tenants", "users"]);
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && npx vitest run test/lib.test.ts`
Expected: FAIL (`../src/lib/ids` and `../src/lib/audit` not found).

- [ ] **Step 3: Implement the schema, ids and audit**

Append to `backend/migrations/0001_foundation.sql` (0001 has never been applied anywhere outside tests, so editing it is safe):
```sql
CREATE TABLE users (
  id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL REFERENCES tenants(id),
  email TEXT NOT NULL,
  name TEXT NOT NULL,
  role TEXT NOT NULL CHECK (role IN ('admin', 'reviewer', 'dispatcher', 'technician')),
  active INTEGER NOT NULL DEFAULT 1 CHECK (active IN (0, 1)),
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  UNIQUE (tenant_id, email)
);

CREATE TABLE devices (
  id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL REFERENCES tenants(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  label TEXT NOT NULL,
  key_hash TEXT NOT NULL UNIQUE,
  paired_at TEXT NOT NULL,
  last_seen_at TEXT,
  revoked_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);

CREATE TABLE pairing_codes (
  id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL REFERENCES tenants(id),
  user_id TEXT NOT NULL REFERENCES users(id),
  code_hash TEXT NOT NULL,
  expires_at TEXT NOT NULL,
  used_at TEXT,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL
);
CREATE INDEX pairing_codes_by_hash ON pairing_codes (tenant_id, code_hash);

CREATE TABLE audit_log (
  id TEXT PRIMARY KEY,
  tenant_id TEXT NOT NULL REFERENCES tenants(id),
  actor_type TEXT NOT NULL CHECK (actor_type IN ('user', 'device', 'system')),
  actor_id TEXT,
  action TEXT NOT NULL,
  entity TEXT NOT NULL,
  entity_id TEXT,
  before_json TEXT,
  after_json TEXT,
  at TEXT NOT NULL
);
CREATE INDEX audit_log_by_time ON audit_log (tenant_id, at);
CREATE INDEX audit_log_by_action ON audit_log (tenant_id, action, at);
```

`backend/src/lib/ids.ts`:
```ts
const ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

/** ULID: 10 chars of millisecond time + 16 random chars, Crockford base32. */
export function ulid(now: number = Date.now()): string {
  let time = "";
  let t = now;
  for (let i = 0; i < 10; i++) {
    time = ALPHABET[t % 32] + time;
    t = Math.floor(t / 32);
  }
  const rand = crypto.getRandomValues(new Uint8Array(16));
  let tail = "";
  for (const b of rand) tail += ALPHABET[b % 32]; // 256 is a multiple of 32: no bias
  return time + tail;
}

export function nowIso(): string {
  return new Date().toISOString();
}
```

`backend/src/lib/audit.ts`:
```ts
import { TENANT_ID, type AdminUser } from "../env";
import { nowIso, ulid } from "./ids";

export interface Actor {
  type: "user" | "device" | "system";
  id: string | null;
  tenantId: string;
}

export const SYSTEM_ACTOR: Actor = { type: "system", id: null, tenantId: TENANT_ID };

export function actorFromUser(u: AdminUser): Actor {
  return { type: "user", id: u.id, tenantId: u.tenantId };
}

/** Returns the insert so callers can batch it with the change it records. */
export function auditStmt(
  db: D1Database,
  actor: Actor,
  action: string,
  entity: string,
  entityId: string | null,
  before: unknown,
  after: unknown,
): D1PreparedStatement {
  return db
    .prepare(
      `INSERT INTO audit_log
         (id, tenant_id, actor_type, actor_id, action, entity, entity_id, before_json, after_json, at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
    )
    .bind(
      ulid(),
      actor.tenantId,
      actor.type,
      actor.id,
      action,
      entity,
      entityId,
      before == null ? null : JSON.stringify(before),
      after == null ? null : JSON.stringify(after),
      nowIso(),
    );
}
```

- [ ] **Step 4: Run tests and typecheck**

Run: `cd backend && npx vitest run && npm run typecheck`
Expected: 11 passed (legacy 5, lib 6); typecheck exits 0.

- [ ] **Step 5: Commit**

```bash
git add backend/migrations backend/src/lib backend/test/lib.test.ts
git commit -m "backend: foundation schema, ULIDs, audit log"
```

---

### Task 3: Admin authentication, roles, people API

**Files:**
- Create: `backend/src/auth/access-jwt.ts`, `backend/src/auth/admin.ts`, `backend/src/routes/admin-users.ts`, `backend/test/access-jwt.test.ts`, `backend/test/admin-users.test.ts`
- Modify: `backend/src/index.ts`
- Rewrite: `backend/test/helpers.ts`

**Interfaces:**
- Consumes: `ApiError`, `readJson` (errors.ts); `auditStmt`, `actorFromUser`, `SYSTEM_ACTOR` (audit.ts); `ulid`, `nowIso` (ids.ts); `sha256Hex`, `sixDigitCode` (crypto.ts); `AppEnv`, `Env`, `Role`, `isRole`, `TENANT_ID`, `AdminUser` (env.ts)
- Produces:
  - `verifyAccessJwt(token, keys, issuer, audience): Promise<string>` (lowercased email); `accessKeys(teamDomain)`
  - `identityEmail(req: Request, env: Env): Promise<string | null>`
  - `adminAuth: MiddlewareHandler<AppEnv>` (sets `c.get("user")`), `requireRole(...roles: Role[])`
  - HTTP: `GET /api/admin/me` → `{ id, email, name, role }`; `GET /api/admin/users` → `{ users: User[] }`; `POST /api/admin/users` `{ email, name, role }` → 201 `{ user }`; `PATCH /api/admin/users/:id` `{ role?, active?, updatedAt }` → `{ user }`; `POST /api/admin/users/:id/pairing-code` → 201 `{ code, expiresAt }`
  - `User` JSON: `{ id, email, name, role, active: boolean, createdAt, updatedAt }`
  - test helpers `seedUser(email, role, name?): Promise<string>`, `auditActions(): Promise<string[]>`

- [ ] **Step 1: Extend the test helpers**

Replace `backend/test/helpers.ts` with:
```ts
import { env, SELF } from "cloudflare:test";
import type { Role } from "../src/env";

export const BOSS = "boss@example.com";

/** Calls the Worker. `as` signs in through the dev-only identity header. */
export function api(path: string, init: RequestInit & { as?: string } = {}): Promise<Response> {
  const { as, ...rest } = init;
  const headers = new Headers(rest.headers);
  if (as) headers.set("X-Dev-User-Email", as);
  if (rest.body !== undefined && !headers.has("content-type")) headers.set("content-type", "application/json");
  return SELF.fetch(`https://bobai.test${path}`, { ...rest, headers });
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
  const { results } = await env.DB.prepare("SELECT action FROM audit_log ORDER BY at, id").all<{ action: string }>();
  return results.map((r) => r.action);
}
```

- [ ] **Step 2: Write the failing tests**

`backend/test/access-jwt.test.ts`:
```ts
import { describe, expect, it } from "vitest";
import { createLocalJWKSet, exportJWK, generateKeyPair, SignJWT } from "jose";
import { verifyAccessJwt } from "../src/auth/access-jwt";

const ISS = "https://team.cloudflareaccess.com";
const AUD = "aud-123";

async function setup() {
  const { publicKey, privateKey } = await generateKeyPair("RS256");
  const jwk = { ...(await exportJWK(publicKey)), kid: "k1", alg: "RS256" };
  const keys = createLocalJWKSet({ keys: [jwk] });
  const sign = (claims: Record<string, unknown>, aud = AUD) =>
    new SignJWT(claims).setProtectedHeader({ alg: "RS256", kid: "k1" })
      .setIssuer(ISS).setAudience(aud).setIssuedAt().setExpirationTime("5m").sign(privateKey);
  return { keys, sign };
}

describe("verifyAccessJwt", () => {
  it("returns the lowercased email from a valid token", async () => {
    const { keys, sign } = await setup();
    expect(await verifyAccessJwt(await sign({ email: "Tech@Example.com" }), keys, ISS, AUD)).toBe("tech@example.com");
  });
  it("rejects a token for another application", async () => {
    const { keys, sign } = await setup();
    await expect(verifyAccessJwt(await sign({ email: "a@b.co" }, "other"), keys, ISS, AUD)).rejects.toThrow();
  });
  it("rejects a token without an email", async () => {
    const { keys, sign } = await setup();
    await expect(verifyAccessJwt(await sign({}), keys, ISS, AUD)).rejects.toThrow("no email");
  });
});
```

`backend/test/admin-users.test.ts`:
```ts
import { env } from "cloudflare:test";
import { describe, expect, it } from "vitest";
import { identityEmail } from "../src/auth/admin";
import { api, auditActions, BOSS, seedUser } from "./helpers";

const json = (body: unknown) => JSON.stringify(body);

describe("admin identity", () => {
  it("refuses requests with no identity", async () => {
    const res = await api("/api/admin/me");
    expect(res.status).toBe(401);
    expect((await res.json()).error).toBe("unauthenticated");
  });

  it("refuses people who were not added", async () => {
    const res = await api("/api/admin/me", { as: "stranger@example.com" });
    expect(res.status).toBe(403);
    expect((await res.json()).error).toBe("not_a_user");
  });

  it("creates the bootstrap admin on first sign-in, once", async () => {
    const res = await api("/api/admin/me", { as: BOSS });
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ email: BOSS, role: "admin" });
    await api("/api/admin/me", { as: BOSS });
    const n = await env.DB.prepare("SELECT COUNT(*) AS n FROM users").first<{ n: number }>();
    expect(n?.n).toBe(1);
    expect(await auditActions()).toEqual(["user.bootstrapped"]);
  });

  it("does not bootstrap once an admin exists", async () => {
    await seedUser("first@example.com", "admin");
    const res = await api("/api/admin/me", { as: BOSS });
    expect(res.status).toBe(403);
  });

  it("ignores the dev header outside dev", async () => {
    const req = new Request("https://x/", { headers: { "X-Dev-User-Email": BOSS } });
    expect(await identityEmail(req, { ...env, ENVIRONMENT: undefined })).toBeNull();
    expect(await identityEmail(req, { ...env, ENVIRONMENT: "production" })).toBeNull();
    expect(await identityEmail(req, env)).toBe(BOSS);
  });

  it("refuses deactivated people", async () => {
    const id = await seedUser("gone@example.com", "admin");
    await env.DB.prepare("UPDATE users SET active = 0 WHERE id = ?").bind(id).run();
    expect((await api("/api/admin/me", { as: "gone@example.com" })).status).toBe(403);
  });
});

describe("people API", () => {
  it("only admins can list people", async () => {
    await seedUser("rev@example.com", "reviewer");
    const res = await api("/api/admin/users", { as: "rev@example.com" });
    expect(res.status).toBe(403);
    expect((await res.json()).error).toBe("forbidden");
  });

  it("adds a person, lowercases the email, and audits it", async () => {
    const res = await api("/api/admin/users", {
      as: BOSS, method: "POST", body: json({ email: " Tech@Example.com ", name: "Tia Tech", role: "technician" }),
    });
    expect(res.status).toBe(201);
    const { user } = await res.json();
    expect(user).toMatchObject({ email: "tech@example.com", name: "Tia Tech", role: "technician", active: true });
    const list = await (await api("/api/admin/users", { as: BOSS })).json();
    expect(list.users.map((u: { email: string }) => u.email).sort()).toEqual([BOSS, "tech@example.com"]);
    expect(await auditActions()).toEqual(["user.bootstrapped", "user.created"]);
  });

  it("validates new people", async () => {
    const post = (body: unknown) => api("/api/admin/users", { as: BOSS, method: "POST", body: json(body) });
    expect((await (await post({ email: "bad", name: "X", role: "admin" })).json()).error).toBe("invalid_email");
    expect((await (await post({ email: "a@b.co", name: " ", role: "admin" })).json()).error).toBe("invalid_name");
    expect((await (await post({ email: "a@b.co", name: "A", role: "owner" })).json()).error).toBe("invalid_role");
    await post({ email: "a@b.co", name: "A", role: "admin" });
    const dup = await post({ email: "A@B.CO", name: "A", role: "admin" });
    expect(dup.status).toBe(409);
    expect((await dup.json()).error).toBe("email_taken");
  });

  it("changes a role, rejects stale edits, and protects the caller's own admin access", async () => {
    const me = await (await api("/api/admin/me", { as: BOSS })).json();
    const created = await (await api("/api/admin/users", {
      as: BOSS, method: "POST", body: json({ email: "d@example.com", name: "Dee", role: "dispatcher" }),
    })).json();
    const patch = (id: string, body: unknown) => api(`/api/admin/users/${id}`, { as: BOSS, method: "PATCH", body: json(body) });

    const ok = await patch(created.user.id, { role: "reviewer", updatedAt: created.user.updatedAt });
    expect(ok.status).toBe(200);
    expect((await ok.json()).user.role).toBe("reviewer");

    const stale = await patch(created.user.id, { active: false, updatedAt: created.user.updatedAt });
    expect(stale.status).toBe(409);
    expect((await stale.json()).error).toBe("stale");

    const meRow = await env.DB.prepare("SELECT updated_at FROM users WHERE id = ?").bind(me.id).first<{ updated_at: string }>();
    const self = await patch(me.id, { role: "technician", updatedAt: meRow?.updated_at });
    expect(self.status).toBe(400);
    expect((await self.json()).error).toBe("cannot_change_self");

    expect((await patch("NOPE", { updatedAt: "x" })).status).toBe(404);
  });

  it("issues pairing codes only for active technicians and voids older codes", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const disp = await seedUser("d@example.com", "dispatcher");
    const issue = (id: string) => api(`/api/admin/users/${id}/pairing-code`, { as: BOSS, method: "POST" });

    const notTech = await issue(disp);
    expect(notTech.status).toBe(400);
    expect((await notTech.json()).error).toBe("not_a_technician");

    const first = await issue(tech);
    expect(first.status).toBe(201);
    const body = await first.json();
    expect(body.code).toMatch(/^\d{6}$/);
    expect(Date.parse(body.expiresAt) - Date.now()).toBeGreaterThan(9 * 60_000);

    await issue(tech);
    const open = await env.DB.prepare("SELECT COUNT(*) AS n FROM pairing_codes WHERE user_id = ? AND used_at IS NULL").bind(tech).first<{ n: number }>();
    expect(open?.n).toBe(1);
    const stored = await env.DB.prepare("SELECT code_hash FROM pairing_codes").all<{ code_hash: string }>();
    expect(stored.results.every((r) => r.code_hash !== body.code)).toBe(true);
    expect((await auditActions()).filter((a) => a === "pairing_code.issued")).toHaveLength(2);
  });
});
```

- [ ] **Step 3: Run to verify they fail**

Run: `cd backend && npx vitest run test/access-jwt.test.ts test/admin-users.test.ts`
Expected: FAIL (modules `../src/auth/access-jwt` and `../src/auth/admin` not found).

- [ ] **Step 4: Implement Access JWT verification**

`backend/src/auth/access-jwt.ts`:
```ts
import { createRemoteJWKSet, jwtVerify, type JWTVerifyGetKey } from "jose";

const keysByTeam = new Map<string, JWTVerifyGetKey>();

/** Cloudflare Access signing keys for a team domain, cached per isolate. */
export function accessKeys(teamDomain: string): JWTVerifyGetKey {
  let keys = keysByTeam.get(teamDomain);
  if (!keys) {
    keys = createRemoteJWKSet(new URL(`${teamDomain}/cdn-cgi/access/certs`));
    keysByTeam.set(teamDomain, keys);
  }
  return keys;
}

/** Verifies an Access JWT and returns its email claim, lowercased. Throws if invalid. */
export async function verifyAccessJwt(
  token: string,
  keys: JWTVerifyGetKey,
  issuer: string,
  audience: string,
): Promise<string> {
  const { payload } = await jwtVerify(token, keys, { issuer, audience });
  const email = payload.email;
  if (typeof email !== "string" || email.length === 0) throw new Error("no email claim");
  return email.toLowerCase();
}
```

- [ ] **Step 5: Implement admin auth and roles**

`backend/src/auth/admin.ts`:
```ts
import type { MiddlewareHandler } from "hono";
import { TENANT_ID, type AdminUser, type AppEnv, type Env, type Role } from "../env";
import { ApiError } from "../lib/errors";
import { auditStmt, SYSTEM_ACTOR } from "../lib/audit";
import { nowIso, ulid } from "../lib/ids";
import { accessKeys, verifyAccessJwt } from "./access-jwt";

/** Who is calling: the dev header (dev only) or a verified Cloudflare Access JWT. */
export async function identityEmail(req: Request, env: Env): Promise<string | null> {
  if (env.ENVIRONMENT === "dev") {
    const dev = req.headers.get("X-Dev-User-Email");
    if (dev) return dev.trim().toLowerCase();
  }
  const jwt = req.headers.get("Cf-Access-Jwt-Assertion");
  if (!jwt || !env.ACCESS_TEAM_DOMAIN || !env.ACCESS_AUD) return null;
  try {
    return await verifyAccessJwt(jwt, accessKeys(env.ACCESS_TEAM_DOMAIN), env.ACCESS_TEAM_DOMAIN, env.ACCESS_AUD);
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
```

- [ ] **Step 6: Implement the people routes**

`backend/src/routes/admin-users.ts`:
```ts
import { Hono } from "hono";
import { isRole, type AppEnv, type Role } from "../env";
import { requireRole } from "../auth/admin";
import { ApiError, readJson } from "../lib/errors";
import { actorFromUser, auditStmt } from "../lib/audit";
import { nowIso, ulid } from "../lib/ids";
import { sha256Hex, sixDigitCode } from "../lib/crypto";

interface UserRow {
  id: string;
  tenant_id: string;
  email: string;
  name: string;
  role: Role;
  active: number;
  created_at: string;
  updated_at: string;
}

export function userJson(r: UserRow) {
  return {
    id: r.id,
    email: r.email,
    name: r.name,
    role: r.role,
    active: r.active === 1,
    createdAt: r.created_at,
    updatedAt: r.updated_at,
  };
}

const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const PAIRING_TTL_MS = 10 * 60_000;

export const adminUsers = new Hono<AppEnv>();

adminUsers.get("/me", (c) => {
  const u = c.get("user");
  return c.json({ id: u.id, email: u.email, name: u.name, role: u.role });
});

adminUsers.get("/users", requireRole("admin"), async (c) => {
  const { results } = await c.env.DB.prepare("SELECT * FROM users WHERE tenant_id = ? ORDER BY name")
    .bind(c.get("user").tenantId).all<UserRow>();
  return c.json({ users: results.map(userJson) });
});

adminUsers.post("/users", requireRole("admin"), async (c) => {
  const actor = c.get("user");
  const body = await readJson<{ email?: string; name?: string; role?: string }>(c);
  const email = body.email?.trim().toLowerCase() ?? "";
  const name = body.name?.trim() ?? "";
  if (!EMAIL.test(email)) throw new ApiError(400, "invalid_email", "Enter a valid email address.");
  if (!name) throw new ApiError(400, "invalid_name", "Enter the person's name.");
  if (!isRole(body.role)) throw new ApiError(400, "invalid_role", "Pick a role: admin, reviewer, dispatcher or technician.");

  const taken = await c.env.DB.prepare("SELECT id FROM users WHERE tenant_id = ? AND email = ?")
    .bind(actor.tenantId, email).first();
  if (taken) throw new ApiError(409, "email_taken", "Someone with that email is already in BobAI.");

  const now = nowIso();
  const row: UserRow = { id: ulid(), tenant_id: actor.tenantId, email, name, role: body.role, active: 1, created_at: now, updated_at: now };
  await c.env.DB.batch([
    c.env.DB.prepare(
      "INSERT INTO users (id, tenant_id, email, name, role, active, created_at, updated_at) VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
    ).bind(row.id, row.tenant_id, row.email, row.name, row.role, now, now),
    auditStmt(c.env.DB, actorFromUser(actor), "user.created", "user", row.id, null, userJson(row)),
  ]);
  return c.json({ user: userJson(row) }, 201);
});

adminUsers.patch("/users/:id", requireRole("admin"), async (c) => {
  const actor = c.get("user");
  const id = c.req.param("id");
  const body = await readJson<{ role?: string; active?: unknown; updatedAt?: string }>(c);
  const row = await c.env.DB.prepare("SELECT * FROM users WHERE tenant_id = ? AND id = ?")
    .bind(actor.tenantId, id).first<UserRow>();
  if (!row) throw new ApiError(404, "not_found", "That person was not found.");
  if (body.updatedAt !== row.updated_at) {
    throw new ApiError(409, "stale", "Someone changed this person since you loaded the page. Reload and try again.");
  }
  if (body.role !== undefined && !isRole(body.role)) {
    throw new ApiError(400, "invalid_role", "Pick a role: admin, reviewer, dispatcher or technician.");
  }
  if (body.active !== undefined && typeof body.active !== "boolean") {
    throw new ApiError(400, "invalid_active", "Active must be true or false.");
  }
  if (id === actor.id && ((body.role !== undefined && body.role !== "admin") || body.active === false)) {
    throw new ApiError(400, "cannot_change_self", "You can't remove your own admin access.");
  }

  const next: UserRow = {
    ...row,
    role: (body.role as Role | undefined) ?? row.role,
    active: body.active === undefined ? row.active : body.active ? 1 : 0,
    updated_at: nowIso(),
  };
  await c.env.DB.batch([
    c.env.DB.prepare("UPDATE users SET role = ?, active = ?, updated_at = ? WHERE id = ? AND updated_at = ?")
      .bind(next.role, next.active, next.updated_at, id, row.updated_at),
    auditStmt(c.env.DB, actorFromUser(actor), "user.updated", "user", id, userJson(row), userJson(next)),
  ]);
  return c.json({ user: userJson(next) });
});

adminUsers.post("/users/:id/pairing-code", requireRole("admin"), async (c) => {
  const actor = c.get("user");
  const id = c.req.param("id");
  const row = await c.env.DB.prepare("SELECT * FROM users WHERE tenant_id = ? AND id = ?")
    .bind(actor.tenantId, id).first<UserRow>();
  if (!row) throw new ApiError(404, "not_found", "That person was not found.");
  if (row.active !== 1 || row.role !== "technician") {
    throw new ApiError(400, "not_a_technician", "Pairing codes are only for active technicians.");
  }

  const now = new Date();
  const nowS = now.toISOString();
  // Avoid handing out a code that is already live for someone else.
  let code = sixDigitCode();
  let hash = await sha256Hex(code);
  for (let i = 0; i < 5; i++) {
    const clash = await c.env.DB.prepare(
      "SELECT id FROM pairing_codes WHERE tenant_id = ? AND code_hash = ? AND used_at IS NULL AND expires_at > ?",
    ).bind(actor.tenantId, hash, nowS).first();
    if (!clash) break;
    code = sixDigitCode();
    hash = await sha256Hex(code);
  }
  const expiresAt = new Date(now.getTime() + PAIRING_TTL_MS).toISOString();

  await c.env.DB.batch([
    c.env.DB.prepare(
      "UPDATE pairing_codes SET used_at = ?, updated_at = ? WHERE tenant_id = ? AND user_id = ? AND used_at IS NULL",
    ).bind(nowS, nowS, actor.tenantId, id),
    c.env.DB.prepare(
      `INSERT INTO pairing_codes (id, tenant_id, user_id, code_hash, expires_at, used_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, NULL, ?, ?)`,
    ).bind(ulid(), actor.tenantId, id, hash, expiresAt, nowS, nowS),
    auditStmt(c.env.DB, actorFromUser(actor), "pairing_code.issued", "user", id, null, { expiresAt }),
  ]);
  return c.json({ code, expiresAt }, 201);
});
```

- [ ] **Step 7: Mount the admin routes**

In `backend/src/index.ts` add the imports:
```ts
import { adminAuth } from "./auth/admin";
import { adminUsers } from "./routes/admin-users";
```
and before `export default app;`:
```ts
app.use("/api/admin/*", adminAuth);
app.route("/api/admin", adminUsers);
```

- [ ] **Step 8: Run tests and typecheck**

Run: `cd backend && npx vitest run && npm run typecheck`
Expected: 25 passed (legacy 5, lib 6, access-jwt 3, admin-users 11); typecheck exits 0.

- [ ] **Step 9: Commit**

```bash
git add backend/src backend/test
git commit -m "backend: Access/dev sign-in, roles, bootstrap admin, people API, pairing codes"
```

---

### Task 4: Device pairing, device auth, revocation

**Files:**
- Rewrite: `backend/src/auth/device.ts`
- Create: `backend/src/routes/device.ts`, `backend/src/routes/admin-devices.ts`, `backend/test/devices.test.ts`
- Modify: `backend/src/index.ts`

**Interfaces:**
- Consumes: everything from Tasks 1–3; `POST /api/admin/users/:id/pairing-code`
- Produces:
  - `deviceFromBearer(req, db): Promise<{ ok: true; device: DeviceCtx } | { ok: false; code: "device_unknown" | "device_revoked" }>`, `deviceAuth` middleware (sets `c.get("device")`)
  - HTTP: `POST /api/device/pair` `{ code, label }` → 201 `{ deviceKey, deviceId, user: { name, email } }`; errors `invalid_code` (400), `too_many_attempts` (429)
  - `GET /api/device/me` → `{ deviceId, user: { name, email } }`; errors `device_unknown` / `device_revoked` (401)
  - `GET /api/admin/devices` → `{ devices: Device[] }`, `Device = { id, label, pairedAt, lastSeenAt, revokedAt, user: { id, name, email } }`
  - `POST /api/admin/devices/:id/revoke` → `{ device }`
  - `/token` accepts `Authorization: Bearer <deviceKey>`

- [ ] **Step 1: Write the failing tests**

`backend/test/devices.test.ts`:
```ts
import { env, fetchMock } from "cloudflare:test";
import { beforeAll, describe, expect, it } from "vitest";
import { api, auditActions, BOSS, seedUser } from "./helpers";

beforeAll(() => {
  fetchMock.activate();
  fetchMock.disableNetConnect();
});

async function issueCode(userId: string): Promise<string> {
  await api("/api/admin/me", { as: BOSS }); // bootstrap admin
  const res = await api(`/api/admin/users/${userId}/pairing-code`, { as: BOSS, method: "POST" });
  return (await res.json()).code;
}

const pair = (code: string, label = "SM-F966U") =>
  api("/api/device/pair", { method: "POST", body: JSON.stringify({ code, label }) });

describe("device pairing", () => {
  it("pairs with a valid code, stores only a hash, and authenticates the phone", async () => {
    const tech = await seedUser("tia@example.com", "technician", "Tia");
    const res = await pair(await issueCode(tech));
    expect(res.status).toBe(201);
    const body = await res.json();
    expect(body.user).toEqual({ name: "Tia", email: "tia@example.com" });
    expect(body.deviceKey).toMatch(/^[A-Za-z0-9_-]{43}$/);

    const stored = await env.DB.prepare("SELECT key_hash FROM devices WHERE id = ?").bind(body.deviceId).first<{ key_hash: string }>();
    expect(stored?.key_hash).not.toBe(body.deviceKey);

    const me = await api("/api/device/me", { headers: { Authorization: `Bearer ${body.deviceKey}` } });
    expect(me.status).toBe(200);
    expect(await me.json()).toEqual({ deviceId: body.deviceId, user: { name: "Tia", email: "tia@example.com" } });
    expect(await auditActions()).toContain("device.paired");
  });

  it("refuses a code twice", async () => {
    const code = await issueCode(await seedUser("t@example.com", "technician"));
    expect((await pair(code)).status).toBe(201);
    const again = await pair(code);
    expect(again.status).toBe(400);
    expect((await again.json()).error).toBe("invalid_code");
  });

  it("refuses expired codes", async () => {
    const code = await issueCode(await seedUser("t@example.com", "technician"));
    await env.DB.prepare("UPDATE pairing_codes SET expires_at = '2000-01-01T00:00:00.000Z'").run();
    expect((await (await pair(code)).json()).error).toBe("invalid_code");
  });

  it("logs wrong codes and stops after 20 in 10 minutes", async () => {
    const wrong = await pair("000000");
    expect(wrong.status).toBe(400);
    expect(await auditActions()).toEqual(["device.pair_failed"]);
    for (let i = 0; i < 19; i++) await pair("000000");
    const blocked = await pair("123456");
    expect(blocked.status).toBe(429);
    expect((await blocked.json()).error).toBe("too_many_attempts");
  });

  it("refuses unknown keys and revoked phones", async () => {
    const unknown = await api("/api/device/me", { headers: { Authorization: "Bearer nope" } });
    expect(unknown.status).toBe(401);
    expect((await unknown.json()).error).toBe("device_unknown");

    const tech = await seedUser("t@example.com", "technician");
    const { deviceKey, deviceId } = await (await pair(await issueCode(tech))).json();
    const list = await (await api("/api/admin/devices", { as: BOSS })).json();
    expect(list.devices).toHaveLength(1);
    expect(list.devices[0]).toMatchObject({ id: deviceId, label: "SM-F966U", revokedAt: null });

    const revoke = await api(`/api/admin/devices/${deviceId}/revoke`, { as: BOSS, method: "POST" });
    expect(revoke.status).toBe(200);
    expect((await revoke.json()).device.revokedAt).not.toBeNull();

    const me = await api("/api/device/me", { headers: { Authorization: `Bearer ${deviceKey}` } });
    expect(me.status).toBe(401);
    expect(await me.json()).toEqual({ error: "device_revoked", message: "This phone was removed. Ask your admin to pair it again." });
    expect(await auditActions()).toContain("device.revoked");
  });

  it("treats a deactivated technician's phone as revoked", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const { deviceKey } = await (await pair(await issueCode(tech))).json();
    await env.DB.prepare("UPDATE users SET active = 0 WHERE id = ?").bind(tech).run();
    const me = await api("/api/device/me", { headers: { Authorization: `Bearer ${deviceKey}` } });
    expect((await me.json()).error).toBe("device_revoked");
  });

  it("only admins manage devices", async () => {
    await seedUser("disp@example.com", "dispatcher");
    expect((await api("/api/admin/devices", { as: "disp@example.com" })).status).toBe(403);
  });

  it("lets a paired phone mint a Gemini token", async () => {
    const tech = await seedUser("t@example.com", "technician");
    const { deviceKey } = await (await pair(await issueCode(tech))).json();
    fetchMock.get("https://generativelanguage.googleapis.com")
      .intercept({ path: /\/v1beta\/auth_tokens/, method: "POST" })
      .reply(200, { name: "auth_tokens/xyz", expireTime: "2026-11-20T12:00:00Z" });
    const res = await api("/token", { method: "POST", headers: { Authorization: `Bearer ${deviceKey}` } });
    expect(res.status).toBe(200);
    expect((await res.json()).token).toBe("auth_tokens/xyz");
  });
});
```

- [ ] **Step 2: Run to verify they fail**

Run: `cd backend && npx vitest run test/devices.test.ts`
Expected: FAIL (`/api/device/pair` returns 404).

- [ ] **Step 3: Implement device auth**

Replace `backend/src/auth/device.ts`:
```ts
import type { MiddlewareHandler } from "hono";
import type { AppEnv, DeviceCtx } from "../env";
import { ApiError } from "../lib/errors";
import { sha256Hex } from "../lib/crypto";
import { nowIso } from "../lib/ids";

type DeviceCheck = { ok: true; device: DeviceCtx } | { ok: false; code: "device_unknown" | "device_revoked" };

export async function deviceFromBearer(req: Request, db: D1Database): Promise<DeviceCheck> {
  const header = req.headers.get("Authorization") ?? "";
  const key = header.startsWith("Bearer ") ? header.slice(7).trim() : "";
  if (!key) return { ok: false, code: "device_unknown" };
  const row = await db.prepare(
    `SELECT d.id, d.tenant_id, d.user_id, d.revoked_at, u.active
       FROM devices d JOIN users u ON u.id = d.user_id
      WHERE d.key_hash = ?`,
  ).bind(await sha256Hex(key)).first<{ id: string; tenant_id: string; user_id: string; revoked_at: string | null; active: number }>();
  if (!row) return { ok: false, code: "device_unknown" };
  if (row.revoked_at || row.active !== 1) return { ok: false, code: "device_revoked" };
  await db.prepare("UPDATE devices SET last_seen_at = ? WHERE id = ?").bind(nowIso(), row.id).run();
  return { ok: true, device: { id: row.id, tenantId: row.tenant_id, userId: row.user_id } };
}

export const deviceAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const check = await deviceFromBearer(c.req.raw, c.env.DB);
  if (!check.ok) {
    throw check.code === "device_revoked"
      ? new ApiError(401, "device_revoked", "This phone was removed. Ask your admin to pair it again.")
      : new ApiError(401, "device_unknown", "This phone is not paired with BobAI.");
  }
  c.set("device", check.device);
  await next();
};
```

- [ ] **Step 4: Implement the device routes**

`backend/src/routes/device.ts`:
```ts
import { Hono } from "hono";
import { TENANT_ID, type AppEnv } from "../env";
import { deviceAuth } from "../auth/device";
import { ApiError, readJson } from "../lib/errors";
import { auditStmt, SYSTEM_ACTOR } from "../lib/audit";
import { nowIso, ulid } from "../lib/ids";
import { randomKey, sha256Hex } from "../lib/crypto";

const FAILURE_WINDOW_MS = 10 * 60_000;
const MAX_FAILURES = 20;

const invalidCode = () =>
  new ApiError(400, "invalid_code", "That code is wrong or expired. Ask your admin for a new one.");

export const deviceApi = new Hono<AppEnv>();

// Registered before the auth middleware: pairing is how a phone gets a key.
deviceApi.post("/pair", async (c) => {
  const db = c.env.DB;
  const body = await readJson<{ code?: string; label?: string }>(c);
  const code = (body.code ?? "").replace(/\s/g, "");
  const label = (body.label ?? "").trim().slice(0, 80) || "Phone";

  const since = new Date(Date.now() - FAILURE_WINDOW_MS).toISOString();
  const failures = await db.prepare(
    "SELECT COUNT(*) AS n FROM audit_log WHERE tenant_id = ? AND action = 'device.pair_failed' AND at > ?",
  ).bind(TENANT_ID, since).first<{ n: number }>();
  if ((failures?.n ?? 0) >= MAX_FAILURES) {
    throw new ApiError(429, "too_many_attempts", "Too many wrong codes. Wait 10 minutes and try again.");
  }

  const now = nowIso();
  const match = /^\d{6}$/.test(code)
    ? await db.prepare(
        `SELECT p.id, p.user_id, u.name, u.email
           FROM pairing_codes p JOIN users u ON u.id = p.user_id
          WHERE p.tenant_id = ? AND p.code_hash = ? AND p.used_at IS NULL AND p.expires_at > ?
            AND u.active = 1 AND u.role = 'technician'`,
      ).bind(TENANT_ID, await sha256Hex(code), now).first<{ id: string; user_id: string; name: string; email: string }>()
    : null;
  if (!match) {
    await auditStmt(db, SYSTEM_ACTOR, "device.pair_failed", "pairing_code", null, null, null).run();
    throw invalidCode();
  }

  const claimed = await db.prepare("UPDATE pairing_codes SET used_at = ?, updated_at = ? WHERE id = ? AND used_at IS NULL")
    .bind(now, now, match.id).run();
  if (claimed.meta.changes !== 1) throw invalidCode();

  const deviceKey = randomKey();
  const deviceId = ulid();
  await db.batch([
    db.prepare(
      `INSERT INTO devices (id, tenant_id, user_id, label, key_hash, paired_at, last_seen_at, revoked_at, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, NULL, ?, ?)`,
    ).bind(deviceId, TENANT_ID, match.user_id, label, await sha256Hex(deviceKey), now, now, now, now),
    auditStmt(db, { type: "device", id: deviceId, tenantId: TENANT_ID }, "device.paired", "device", deviceId, null, {
      label,
      userId: match.user_id,
    }),
  ]);
  return c.json({ deviceKey, deviceId, user: { name: match.name, email: match.email } }, 201);
});

deviceApi.use("*", deviceAuth);

deviceApi.get("/me", async (c) => {
  const device = c.get("device");
  const user = await c.env.DB.prepare("SELECT name, email FROM users WHERE id = ?")
    .bind(device.userId).first<{ name: string; email: string }>();
  return c.json({ deviceId: device.id, user });
});
```

`backend/src/routes/admin-devices.ts`:
```ts
import { Hono } from "hono";
import type { AppEnv } from "../env";
import { requireRole } from "../auth/admin";
import { ApiError } from "../lib/errors";
import { actorFromUser, auditStmt } from "../lib/audit";
import { nowIso } from "../lib/ids";

interface DeviceRow {
  id: string;
  user_id: string;
  label: string;
  paired_at: string;
  last_seen_at: string | null;
  revoked_at: string | null;
  user_name: string;
  user_email: string;
}

const SELECT_DEVICES = `
  SELECT d.id, d.user_id, d.label, d.paired_at, d.last_seen_at, d.revoked_at,
         u.name AS user_name, u.email AS user_email
    FROM devices d JOIN users u ON u.id = d.user_id
   WHERE d.tenant_id = ?`;

function deviceJson(r: DeviceRow) {
  return {
    id: r.id,
    label: r.label,
    pairedAt: r.paired_at,
    lastSeenAt: r.last_seen_at,
    revokedAt: r.revoked_at,
    user: { id: r.user_id, name: r.user_name, email: r.user_email },
  };
}

export const adminDevices = new Hono<AppEnv>();

adminDevices.get("/devices", requireRole("admin"), async (c) => {
  const { results } = await c.env.DB.prepare(`${SELECT_DEVICES} ORDER BY d.paired_at DESC`)
    .bind(c.get("user").tenantId).all<DeviceRow>();
  return c.json({ devices: results.map(deviceJson) });
});

adminDevices.post("/devices/:id/revoke", requireRole("admin"), async (c) => {
  const actor = c.get("user");
  const id = c.req.param("id");
  const row = await c.env.DB.prepare(`${SELECT_DEVICES} AND d.id = ?`).bind(actor.tenantId, id).first<DeviceRow>();
  if (!row) throw new ApiError(404, "not_found", "That phone was not found.");
  if (row.revoked_at) return c.json({ device: deviceJson(row) });

  const now = nowIso();
  const next: DeviceRow = { ...row, revoked_at: now };
  await c.env.DB.batch([
    c.env.DB.prepare("UPDATE devices SET revoked_at = ?, updated_at = ? WHERE id = ?").bind(now, now, id),
    auditStmt(c.env.DB, actorFromUser(actor), "device.revoked", "device", id, deviceJson(row), deviceJson(next)),
  ]);
  return c.json({ device: deviceJson(next) });
});
```

- [ ] **Step 5: Mount the routes**

In `backend/src/index.ts` add imports:
```ts
import { adminDevices } from "./routes/admin-devices";
import { deviceApi } from "./routes/device";
```
and after `app.route("/api/admin", adminUsers);`:
```ts
app.route("/api/admin", adminDevices);
app.route("/api/device", deviceApi);
```

- [ ] **Step 6: Run tests and typecheck**

Run: `cd backend && npx vitest run && npm run typecheck`
Expected: 33 passed (legacy 5, lib 6, access-jwt 3, admin-users 11, devices 8); typecheck exits 0.

- [ ] **Step 7: Commit**

```bash
git add backend/src backend/test
git commit -m "backend: phone pairing, per-device keys, revocation; /token accepts device keys"
```

---

### Task 5: Console app with the People page

**Files:**
- Create: `console/package.json`, `console/tsconfig.json`, `console/vite.config.ts`, `console/index.html`, `console/src/main.tsx`, `console/src/App.tsx`, `console/src/styles.css`, `console/src/api/client.ts`, `console/src/api/types.ts`, `console/src/api/hooks.ts`, `console/src/pages/PeoplePage.tsx`, `console/src/pages/PeoplePage.test.tsx`, `console/src/test/setup.ts`
- Modify: root `.gitignore`

**Interfaces:**
- Consumes: HTTP API from Tasks 3–4 (`/api/admin/me`, `/users`, `/users/:id/pairing-code`, `/devices`, `/devices/:id/revoke`)
- Produces: `apiFetch<T>(path, { method?, body? })` and `class ApiError { status, code, message }` (client.ts); hooks `useMe`, `useUsers`, `useDevices`, `useCreateUser`, `useIssuePairingCode`, `useRevokeDevice` (hooks.ts). Later plans add pages under `src/pages/` and links in `App.tsx`.

- [ ] **Step 1: Scaffold the project**

`console/package.json`:
```json
{
  "name": "bobai-console",
  "private": true,
  "version": "0.1.0",
  "type": "module",
  "scripts": {
    "dev": "vite",
    "build": "tsc --noEmit && vite build",
    "test": "vitest run"
  },
  "dependencies": {
    "@tanstack/react-query": "5.62.0",
    "react": "18.3.1",
    "react-dom": "18.3.1",
    "react-router-dom": "6.28.0"
  },
  "devDependencies": {
    "@testing-library/dom": "10.4.0",
    "@testing-library/jest-dom": "6.6.3",
    "@testing-library/react": "16.1.0",
    "@testing-library/user-event": "14.5.2",
    "@types/react": "18.3.12",
    "@types/react-dom": "18.3.1",
    "@vitejs/plugin-react": "4.3.4",
    "jsdom": "25.0.1",
    "typescript": "5.6.3",
    "vite": "5.4.11",
    "vitest": "2.1.8"
  }
}
```
Run: `cd console && npm install`

`console/tsconfig.json`:
```json
{
  "compilerOptions": {
    "target": "ES2022",
    "lib": ["ES2022", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "moduleResolution": "Bundler",
    "jsx": "react-jsx",
    "strict": true,
    "noUnusedLocals": true,
    "noUnusedParameters": true,
    "skipLibCheck": true,
    "noEmit": true,
    "types": ["vite/client", "@testing-library/jest-dom"]
  },
  "include": ["src", "vite.config.ts"]
}
```

`console/vite.config.ts`:
```ts
import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// In dev, /api goes to `wrangler dev`. DEV_USER_EMAIL signs you in through the
// Worker's dev-only identity header; production uses Cloudflare Access instead.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://127.0.0.1:8787",
        configure: (proxy) => {
          proxy.on("proxyReq", (proxyReq) => {
            const email = process.env.DEV_USER_EMAIL;
            if (email) proxyReq.setHeader("X-Dev-User-Email", email);
          });
        },
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: ["./src/test/setup.ts"],
  },
});
```

`console/index.html`:
```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <title>BobAI Console</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>
```

`console/src/test/setup.ts`:
```ts
import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach, vi } from "vitest";

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
```

Append to the repository root `.gitignore`:
```
console/node_modules/
console/dist/
```

- [ ] **Step 2: API client, types and hooks**

`console/src/api/types.ts`:
```ts
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
```

`console/src/api/client.ts`:
```ts
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** Calls the console API. Errors carry the server's plain-language message. */
export async function apiFetch<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const hasBody = init.body !== undefined;
  const res = await fetch(`/api/admin${path}`, {
    method: init.method ?? "GET",
    headers: hasBody ? { "content-type": "application/json" } : undefined,
    body: hasBody ? JSON.stringify(init.body) : undefined,
    credentials: "same-origin",
  });
  const text = await res.text();
  let data: unknown = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  if (!res.ok) {
    const err = (data ?? {}) as { error?: string; message?: string };
    throw new ApiError(res.status, err.error ?? "http_error", err.message ?? `The request failed (${res.status}). Try again.`);
  }
  return data as T;
}
```

`console/src/api/hooks.ts`:
```ts
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiFetch } from "./client";
import type { Device, Me, PairingCode, Role, User } from "./types";

export const useMe = () => useQuery({ queryKey: ["me"], queryFn: () => apiFetch<Me>("/me") });

export const useUsers = (enabled: boolean) =>
  useQuery({ queryKey: ["users"], queryFn: () => apiFetch<{ users: User[] }>("/users"), enabled });

export const useDevices = (enabled: boolean) =>
  useQuery({ queryKey: ["devices"], queryFn: () => apiFetch<{ devices: Device[] }>("/devices"), enabled });

export function useCreateUser() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (input: { email: string; name: string; role: Role }) =>
      apiFetch<{ user: User }>("/users", { method: "POST", body: input }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["users"] }),
  });
}

export const useIssuePairingCode = () =>
  useMutation({
    mutationFn: (userId: string) => apiFetch<PairingCode>(`/users/${userId}/pairing-code`, { method: "POST" }),
  });

export function useRevokeDevice() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (deviceId: string) => apiFetch<{ device: Device }>(`/devices/${deviceId}/revoke`, { method: "POST" }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["devices"] }),
  });
}
```

- [ ] **Step 3: Write the failing page tests**

`console/src/pages/PeoplePage.test.tsx`:
```tsx
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PeoplePage } from "./PeoplePage";

type Handler = (url: string, init?: RequestInit) => { status: number; body: unknown };

function mockApi(handler: Handler) {
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    const { status, body } = handler(url, init);
    return new Response(JSON.stringify(body), { status });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <PeoplePage />
    </QueryClientProvider>,
  );
}

const admin = { id: "A1", email: "boss@example.com", name: "Boss", role: "admin" };
const tech = { id: "T1", email: "tia@example.com", name: "Tia", role: "technician", active: true, createdAt: "", updatedAt: "" };
const phone = { id: "D1", label: "SM-F966U", pairedAt: "2026-11-20T11:00:00.000Z", lastSeenAt: null, revokedAt: null, user: { id: "T1", name: "Tia", email: "tia@example.com" } };

describe("PeoplePage", () => {
  let users: unknown[];
  beforeEach(() => {
    users = [{ ...admin, active: true, createdAt: "", updatedAt: "" }, tech];
  });

  const handler: Handler = (url, init) => {
    const method = init?.method ?? "GET";
    if (url.endsWith("/me")) return { status: 200, body: admin };
    if (url.endsWith("/users") && method === "GET") return { status: 200, body: { users } };
    if (url.endsWith("/users") && method === "POST") {
      const input = JSON.parse(String(init?.body));
      const user = { id: "N1", active: true, createdAt: "", updatedAt: "", ...input };
      users = [...users, user];
      return { status: 201, body: { user } };
    }
    if (url.endsWith("/users/T1/pairing-code")) return { status: 201, body: { code: "482913", expiresAt: "2026-11-20T12:10:00.000Z" } };
    if (url.endsWith("/devices")) return { status: 200, body: { devices: [phone] } };
    return { status: 404, body: { error: "not_found", message: "Not found." } };
  };

  it("lists people and their phones", async () => {
    mockApi(handler);
    renderPage();
    expect(await screen.findByText("Signed in as boss@example.com (admin)")).toBeInTheDocument();
    expect(await screen.findByRole("cell", { name: "tia@example.com" })).toBeInTheDocument();
    expect(await screen.findByRole("cell", { name: "SM-F966U" })).toBeInTheDocument();
  });

  it("adds a person", async () => {
    const fetchMock = mockApi(handler);
    renderPage();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Email"), "new@example.com");
    await user.type(screen.getByLabelText("Name"), "Nia");
    await user.selectOptions(screen.getByLabelText("Role"), "dispatcher");
    await user.click(screen.getByRole("button", { name: "Add person" }));
    expect(await screen.findByRole("cell", { name: "new@example.com" })).toBeInTheDocument();
    const post = fetchMock.mock.calls.find(([, init]) => init?.method === "POST");
    expect(JSON.parse(String(post?.[1]?.body))).toEqual({ email: "new@example.com", name: "Nia", role: "dispatcher" });
  });

  it("shows a pairing code for a technician", async () => {
    mockApi(handler);
    renderPage();
    const user = userEvent.setup();
    const row = (await screen.findByRole("cell", { name: "tia@example.com" })).closest("tr")!;
    await user.click(within(row).getByRole("button", { name: "Pairing code" }));
    expect(await screen.findByText("482913")).toBeInTheDocument();
    expect(screen.getByText(/Enter it on Tia's phone/)).toBeInTheDocument();
  });

  it("explains when the signed-in person is not an admin", async () => {
    mockApi((url) =>
      url.endsWith("/me")
        ? { status: 200, body: { ...admin, role: "reviewer" } }
        : { status: 403, body: { error: "forbidden", message: "You don't have permission to do that." } },
    );
    renderPage();
    expect(await screen.findByText("Only admins can manage people and phones.")).toBeInTheDocument();
  });

  it("shows the server's message when sign-in is refused", async () => {
    mockApi(() => ({ status: 403, body: { error: "not_a_user", message: "Your account is not active in BobAI. Ask an admin to add you." } }));
    renderPage();
    expect(await screen.findByText("Your account is not active in BobAI. Ask an admin to add you.")).toBeInTheDocument();
  });
});
```

- [ ] **Step 4: Run to verify they fail**

Run: `cd console && npx vitest run`
Expected: FAIL (`./PeoplePage` not found).

- [ ] **Step 5: Implement the page, app shell and styles**

`console/src/pages/PeoplePage.tsx`:
```tsx
import { useState, type FormEvent } from "react";
import { useCreateUser, useDevices, useIssuePairingCode, useMe, useRevokeDevice, useUsers } from "../api/hooks";
import { ROLES, type Device, type PairingCode, type Role, type User } from "../api/types";

const message = (err: unknown) => (err instanceof Error ? err.message : "Something went wrong. Try again.");
const when = (iso: string | null) => (iso ? new Date(iso).toLocaleString() : "Never");

export function PeoplePage() {
  const me = useMe();
  const isAdmin = me.data?.role === "admin";
  const users = useUsers(isAdmin);
  const devices = useDevices(isAdmin);

  if (me.isPending) return <p>Loading…</p>;
  if (me.error) return <p role="alert">{message(me.error)}</p>;

  return (
    <section>
      <h1>People and phones</h1>
      <p className="muted">Signed in as {me.data.email} ({me.data.role})</p>
      {!isAdmin ? (
        <p role="alert">Only admins can manage people and phones.</p>
      ) : (
        <>
          <AddPersonForm />
          <h2>People</h2>
          {users.error ? <p role="alert">{message(users.error)}</p> : <PeopleTable users={users.data?.users ?? []} />}
          <h2>Paired phones</h2>
          {devices.error ? <p role="alert">{message(devices.error)}</p> : <DevicesTable devices={devices.data?.devices ?? []} />}
        </>
      )}
    </section>
  );
}

function AddPersonForm() {
  const create = useCreateUser();
  const [email, setEmail] = useState("");
  const [name, setName] = useState("");
  const [role, setRole] = useState<Role>("technician");

  const submit = (e: FormEvent) => {
    e.preventDefault();
    create.mutate({ email, name, role }, { onSuccess: () => { setEmail(""); setName(""); } });
  };

  return (
    <form className="card row" onSubmit={submit}>
      <div className="field">
        <label htmlFor="new-email">Email</label>
        <input id="new-email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="new-name">Name</label>
        <input id="new-name" value={name} onChange={(e) => setName(e.target.value)} required />
      </div>
      <div className="field">
        <label htmlFor="new-role">Role</label>
        <select id="new-role" value={role} onChange={(e) => setRole(e.target.value as Role)}>
          {ROLES.map((r) => <option key={r} value={r}>{r}</option>)}
        </select>
      </div>
      <button type="submit" disabled={create.isPending}>Add person</button>
      {create.error && <p role="alert">{message(create.error)}</p>}
    </form>
  );
}

function PeopleTable({ users }: { users: User[] }) {
  const issue = useIssuePairingCode();
  const [shown, setShown] = useState<{ user: User; code: PairingCode } | null>(null);

  return (
    <>
      {shown && (
        <div className="card notice" role="status">
          <strong className="code">{shown.code.code}</strong>
          <span>Enter it on {shown.user.name}'s phone under Settings → Office console. It works once and expires at {new Date(shown.code.expiresAt).toLocaleTimeString()}.</span>
          <button type="button" onClick={() => setShown(null)}>Done</button>
        </div>
      )}
      {issue.error && <p role="alert">{message(issue.error)}</p>}
      <table>
        <thead><tr><th>Name</th><th>Email</th><th>Role</th><th>Status</th><th /></tr></thead>
        <tbody>
          {users.map((u) => (
            <tr key={u.id}>
              <td>{u.name}</td>
              <td>{u.email}</td>
              <td>{u.role}</td>
              <td>{u.active ? "Active" : "Inactive"}</td>
              <td>
                {u.role === "technician" && u.active && (
                  <button type="button" disabled={issue.isPending}
                    onClick={() => issue.mutate(u.id, { onSuccess: (code) => setShown({ user: u, code }) })}>
                    Pairing code
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}

function DevicesTable({ devices }: { devices: Device[] }) {
  const revoke = useRevokeDevice();
  if (devices.length === 0) return <p className="muted">No phones paired yet.</p>;
  return (
    <>
      {revoke.error && <p role="alert">{message(revoke.error)}</p>}
      <table>
        <thead><tr><th>Phone</th><th>Technician</th><th>Paired</th><th>Last seen</th><th /></tr></thead>
        <tbody>
          {devices.map((d) => (
            <tr key={d.id}>
              <td>{d.label}</td>
              <td>{d.user.name}</td>
              <td>{when(d.pairedAt)}</td>
              <td>{when(d.lastSeenAt)}</td>
              <td>
                {d.revokedAt ? "Removed" : (
                  <button type="button" disabled={revoke.isPending}
                    onClick={() => { if (window.confirm(`Remove ${d.label}? It will stop working until paired again.`)) revoke.mutate(d.id); }}>
                    Remove phone
                  </button>
                )}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </>
  );
}
```

`console/src/App.tsx`:
```tsx
import { NavLink, Route, Routes } from "react-router-dom";
import { PeoplePage } from "./pages/PeoplePage";

export function App() {
  return (
    <div className="shell">
      <header>
        <strong>BobAI Console</strong>
        <nav>
          <NavLink to="/">People</NavLink>
        </nav>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<PeoplePage />} />
        </Routes>
      </main>
    </div>
  );
}
```

`console/src/main.tsx`:
```tsx
import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { BrowserRouter } from "react-router-dom";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { App } from "./App";
import "./styles.css";

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } } });

createRoot(document.getElementById("root")!).render(
  <StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </StrictMode>,
);
```

`console/src/styles.css`:
```css
:root { font-family: system-ui, sans-serif; color: #1c2430; background: #f4f6f8; }
body { margin: 0; }
.shell header { display: flex; gap: 24px; align-items: center; padding: 12px 24px; background: #1c2430; color: #fff; }
.shell header a { color: #c9d4e0; text-decoration: none; }
.shell header a.active { color: #fff; font-weight: 600; }
main { padding: 24px; max-width: 1100px; }
.muted { color: #5b6778; }
.card { background: #fff; border: 1px solid #d8dee6; border-radius: 8px; padding: 16px; margin: 16px 0; }
.row { display: flex; gap: 12px; align-items: end; flex-wrap: wrap; }
.field { display: flex; flex-direction: column; gap: 4px; font-size: 14px; }
input, select, button { font: inherit; padding: 6px 10px; }
table { border-collapse: collapse; width: 100%; background: #fff; }
th, td { text-align: left; padding: 8px; border-bottom: 1px solid #e3e8ee; }
.notice { display: flex; gap: 16px; align-items: center; }
.code { font-size: 28px; letter-spacing: 4px; font-family: ui-monospace, monospace; }
[role="alert"] { color: #a4262c; }
```

- [ ] **Step 6: Run tests and build**

Run: `cd console && npx vitest run && npm run build`
Expected: 5 passed; build writes `console/dist/` without type errors.

- [ ] **Step 7: Commit**

```bash
git add .gitignore console/package.json console/package-lock.json console/tsconfig.json console/vite.config.ts console/index.html console/src
git commit -m "console: React app with People page (people, pairing codes, phones)"
```

---

### Task 6: Phone pairing and device-key tokens (Android)

All Kotlin paths below are under `app/src/main/java/com/lpecom/gemglasses/` (main) or `app/src/test/java/com/lpecom/gemglasses/` (test).

**Files:**
- Modify: `gradle/libs.versions.toml`, `app/build.gradle.kts`
- Create (main): `bobai/sync/DeviceKeyStore.kt`, `bobai/sync/KeystoreDeviceKeyStore.kt`, `bobai/sync/PairingClient.kt`
- Create (test): `bobai/sync/PairingClientTest.kt`, `gemini/TokenProviderTest.kt`
- Modify (main): `gemini/TokenProvider.kt`, `di/AppModule.kt` (`BindsModule`), `bobai/ui/BobAiViewModel.kt`, `bobai/ui/Screens.kt`

**Interfaces:**
- Consumes: `POST /api/device/pair` → `{ deviceKey, deviceId, user: { name, email } }`, error body `{ error, message }`; `/token` with `Authorization: Bearer <deviceKey>`
- Produces: `interface DeviceKeyStore { fun get(): DevicePairing?; fun save(pairing: DevicePairing); fun clear() }`, `@Serializable data class DevicePairing(val deviceId: String, val deviceKey: String, val userName: String)`; `sealed interface PairResult { Paired(userName); Rejected(code, message); Failed(message) }`; `PairingClient.pair(code, label): PairResult`; `BobAiViewModel.pairing: StateFlow<PairingUi>`, `pairPhone(code)`, `unpairPhone()`. Plans 2–3 reuse `DeviceKeyStore` for sync calls.

- [ ] **Step 1: Add MockWebServer for tests**

In `gradle/libs.versions.toml` under `[libraries]`, after the `okhttp-logging` line, add:
```toml
okhttp-mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }
```
In `app/build.gradle.kts`, after `testImplementation(libs.turbine)`, add:
```kotlin
testImplementation(libs.okhttp.mockwebserver)
```
(`kotlinx-coroutines-test` is already a test dependency.)

- [ ] **Step 2: Write the failing tests**

`app/src/test/java/com/lpecom/gemglasses/bobai/sync/PairingClientTest.kt`:
```kotlin
package com.lpecom.gemglasses.bobai.sync

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class InMemoryDeviceKeyStore(var stored: DevicePairing? = null) : DeviceKeyStore {
    override fun get() = stored
    override fun save(pairing: DevicePairing) { stored = pairing }
    override fun clear() { stored = null }
}

class PairingClientTest {
    private val server = MockWebServer()
    private val store = InMemoryDeviceKeyStore()
    private lateinit var client: PairingClient

    @Before fun setUp() {
        server.start()
        client = PairingClient(OkHttpClient(), Json { ignoreUnknownKeys = true }, store, server.url("/").toString())
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `pairs and stores the device key`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"deviceKey":"secret-key","deviceId":"D1","user":{"name":"Tia","email":"tia@example.com"}}""",
        ))
        val result = client.pair(" 482913 ", "SM-F966U")
        assertEquals(PairResult.Paired("Tia"), result)
        assertEquals(DevicePairing("D1", "secret-key", "Tia"), store.stored)
        val request = server.takeRequest()
        assertEquals("/api/device/pair", request.path)
        assertEquals("""{"code":"482913","label":"SM-F966U"}""", request.body.readUtf8())
    }

    @Test fun `shows the server's message for a wrong code and stores nothing`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400).setBody(
            """{"error":"invalid_code","message":"That code is wrong or expired. Ask your admin for a new one."}""",
        ))
        val result = client.pair("000000", "SM-F966U")
        assertEquals(PairResult.Rejected("invalid_code", "That code is wrong or expired. Ask your admin for a new one."), result)
        assertNull(store.stored)
    }

    @Test fun `server errors are a generic failure`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
        val result = client.pair("482913", "SM-F966U")
        assertTrue(result is PairResult.Failed)
        assertNull(store.stored)
    }

    @Test fun `no connection is a failure, not a crash`() = runBlocking {
        server.shutdown()
        val result = client.pair("482913", "SM-F966U")
        assertEquals(PairResult.Failed("Couldn't reach the office console. Check the connection and try again."), result)
    }
}
```

`app/src/test/java/com/lpecom/gemglasses/gemini/TokenProviderTest.kt`:
```kotlin
package com.lpecom.gemglasses.gemini

import com.lpecom.gemglasses.bobai.sync.DevicePairing
import com.lpecom.gemglasses.bobai.sync.InMemoryDeviceKeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class TokenProviderTest {
    private val server = MockWebServer()

    @Before fun setUp() = server.start()
    @After fun tearDown() = server.shutdown()

    private fun provider(store: InMemoryDeviceKeyStore) =
        TokenProvider(OkHttpClient(), Json { ignoreUnknownKeys = true }, store, server.url("/").toString(), "app-secret")

    @Test fun `a paired phone sends its device key, not the app secret`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"t1"}"""))
        val token = provider(InMemoryDeviceKeyStore(DevicePairing("D1", "dev-key", "Tia"))).fetchEphemeralToken()
        assertEquals("t1", token)
        val req = server.takeRequest()
        assertEquals("Bearer dev-key", req.getHeader("Authorization"))
        assertNull(req.getHeader("X-App-Secret"))
    }

    @Test fun `an unpaired phone falls back to the app secret`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"token":"t2"}"""))
        provider(InMemoryDeviceKeyStore()).fetchEphemeralToken()
        val req = server.takeRequest()
        assertEquals("app-secret", req.getHeader("X-App-Secret"))
        assertNull(req.getHeader("Authorization"))
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run from the repo root: `GEMGLASSES_BACKEND_URL=http://localhost:8787 ./gradlew -q -Pgemglasses.useRealGlasses=true testDebugUnitTest --tests '*PairingClientTest' --tests '*TokenProviderTest'`
Expected: FAIL to compile (`DeviceKeyStore`, `PairingClient`, and the 5-argument `TokenProvider` constructor do not exist).

- [ ] **Step 4: Implement the key store, pairing client and token change**

`bobai/sync/DeviceKeyStore.kt`:
```kotlin
package com.lpecom.gemglasses.bobai.sync

import kotlinx.serialization.Serializable

/** This phone's pairing with the office console. The key is a secret: never log it. */
@Serializable
data class DevicePairing(val deviceId: String, val deviceKey: String, val userName: String)

interface DeviceKeyStore {
    fun get(): DevicePairing?
    fun save(pairing: DevicePairing)
    fun clear()
}
```

`bobai/sync/KeystoreDeviceKeyStore.kt`:
```kotlin
package com.lpecom.gemglasses.bobai.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keeps the pairing in app-private preferences, encrypted with an AES-GCM key
 * that lives in the Android Keystore and never leaves it.
 */
@Singleton
class KeystoreDeviceKeyStore @Inject constructor(
    @ApplicationContext context: Context,
    private val json: Json,
) : DeviceKeyStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun get(): DevicePairing? {
        val blob = prefs.getString(KEY_BLOB, null) ?: return null
        return runCatching {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes, 0, IV_LENGTH))
            val plain = cipher.doFinal(bytes, IV_LENGTH, bytes.size - IV_LENGTH)
            json.decodeFromString(DevicePairing.serializer(), plain.decodeToString())
        }.onFailure {
            Log.w(TAG, "stored pairing unreadable; clearing it")
            clear()
        }.getOrNull()
    }

    override fun save(pairing: DevicePairing) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.doFinal(json.encodeToString(DevicePairing.serializer(), pairing).encodeToByteArray())
        prefs.edit().putString(KEY_BLOB, Base64.encodeToString(cipher.iv + sealed, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_BLOB).apply()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PREFS = "bobai_device"
        const val KEY_BLOB = "pairing"
        const val ALIAS = "bobai_device_pairing"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG = "DeviceKeyStore"
    }
}
```

`bobai/sync/PairingClient.kt`:
```kotlin
package com.lpecom.gemglasses.bobai.sync

import com.lpecom.gemglasses.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PairResult {
    data class Paired(val userName: String) : PairResult
    /** The console refused (wrong/expired code, too many attempts); message is for the technician. */
    data class Rejected(val code: String, val message: String) : PairResult
    data class Failed(val message: String) : PairResult
}

/** Exchanges a pairing code from the office console for this phone's device key. */
@Singleton
class PairingClient internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val keyStore: DeviceKeyStore,
    private val baseUrl: String,
) {
    @Inject constructor(client: OkHttpClient, json: Json, keyStore: DeviceKeyStore) :
        this(client, json, keyStore, BuildConfig.BACKEND_URL)

    @Serializable private data class PairRequest(val code: String, val label: String)
    @Serializable private data class PairUser(val name: String, val email: String)
    @Serializable private data class PairResponse(val deviceKey: String, val deviceId: String, val user: PairUser)
    @Serializable private data class ErrorBody(val error: String = "", val message: String = "")

    suspend fun pair(code: String, label: String): PairResult = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(PairRequest.serializer(), PairRequest(code.trim(), label))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/device/pair")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val paired = json.decodeFromString(PairResponse.serializer(), text)
                    keyStore.save(DevicePairing(paired.deviceId, paired.deviceKey, paired.user.name))
                    PairResult.Paired(paired.user.name)
                } else {
                    val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
                    if (response.code in 400..499 && err != null && err.message.isNotBlank()) {
                        PairResult.Rejected(err.error, err.message)
                    } else {
                        PairResult.Failed("The office console returned an error (${response.code}). Try again.")
                    }
                }
            }
        } catch (e: IOException) {
            PairResult.Failed("Couldn't reach the office console. Check the connection and try again.")
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
```

In `gemini/TokenProvider.kt`, add `import com.lpecom.gemglasses.bobai.sync.DeviceKeyStore`, change the KDoc above the class to "Fetches an ephemeral Gemini Live token from the backend Worker, authenticating with this phone's device key when paired, else the shared app secret.", and replace the class with:
```kotlin
@Singleton
class TokenProvider internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val keyStore: DeviceKeyStore,
    private val baseUrl: String,
    private val appSecret: String,
) {
    @Inject constructor(client: OkHttpClient, json: Json, keyStore: DeviceKeyStore) :
        this(client, json, keyStore, BuildConfig.BACKEND_URL, BuildConfig.APP_SECRET)

    @Serializable
    private data class TokenResponse(val token: String, val expiresAt: String? = null)

    /** Returns a fresh ephemeral token, or throws on transport/auth failure. */
    suspend fun fetchEphemeralToken(): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/token")
            .post(ByteArray(0).toRequestBody(JSON_MEDIA))
        // A paired phone uses its own key; the shared secret is the fallback
        // until every phone is paired.
        val pairing = keyStore.get()
        if (pairing != null) builder.header("Authorization", "Bearer ${pairing.deviceKey}")
        else builder.header("X-App-Secret", appSecret)

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            check(response.isSuccessful) {
                "Token endpoint returned ${response.code}: ${body.take(200)}"
            }
            json.decodeFromString(TokenResponse.serializer(), body).token
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
```
Keep the file's existing imports (`BuildConfig`, `Dispatchers`, `withContext`, `Serializable`, `Json`, OkHttp `Request`/`toRequestBody`/`toMediaType`, `Inject`, `Singleton`); remove any that the compiler reports unused.

In `di/AppModule.kt`, inside `abstract class BindsModule`, add:
```kotlin
@Binds
abstract fun deviceKeyStore(impl: com.lpecom.gemglasses.bobai.sync.KeystoreDeviceKeyStore): com.lpecom.gemglasses.bobai.sync.DeviceKeyStore
```

- [ ] **Step 5: Run the tests**

Run: `GEMGLASSES_BACKEND_URL=http://localhost:8787 ./gradlew -q -Pgemglasses.useRealGlasses=true testDebugUnitTest --tests '*PairingClientTest' --tests '*TokenProviderTest'`
Expected: 6 passed.

- [ ] **Step 6: Pairing state in the view model**

In `bobai/ui/BobAiViewModel.kt`, add constructor parameters right after `val offline: com.lpecom.gemglasses.bobai.offline.OfflineAssistant,`:
```kotlin
    private val pairingClient: com.lpecom.gemglasses.bobai.sync.PairingClient,
    private val deviceKeys: com.lpecom.gemglasses.bobai.sync.DeviceKeyStore,
```
Add above the class:
```kotlin
/** Office console pairing as shown in Settings. */
data class PairingUi(
    val paired: Boolean = false,
    val userName: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)
```
Add inside the class, after the existing `_online` state:
```kotlin
    private val _pairing = MutableStateFlow(deviceKeys.get().let { PairingUi(paired = it != null, userName = it?.userName) })
    val pairing: StateFlow<PairingUi> = _pairing

    /** Pairs this phone with the office console using a 6-digit code from an admin. */
    fun pairPhone(code: String) {
        if (_pairing.value.busy) return
        _pairing.value = _pairing.value.copy(busy = true, message = null)
        viewModelScope.launch {
            _pairing.value = when (val r = pairingClient.pair(code, android.os.Build.MODEL)) {
                is com.lpecom.gemglasses.bobai.sync.PairResult.Paired ->
                    PairingUi(paired = true, userName = r.userName, message = "Paired to ${r.userName}.")
                is com.lpecom.gemglasses.bobai.sync.PairResult.Rejected -> PairingUi(message = r.message)
                is com.lpecom.gemglasses.bobai.sync.PairResult.Failed -> PairingUi(message = r.message)
            }
        }
    }

    /** Forgets the device key on this phone. An admin can also remove the phone in the console. */
    fun unpairPhone() {
        deviceKeys.clear()
        _pairing.value = PairingUi(message = "This phone is unpaired. Your admin can also remove it in the console.")
    }
```
(`MutableStateFlow`, `StateFlow`, `viewModelScope` and `launch` are already imported in this file.)

- [ ] **Step 7: Office console card in Settings**

In `bobai/ui/Screens.kt`, in `SettingsScreen(vm, nav)`, add `OfficeConsoleCard(vm)` on the line after `KnowledgeSyncCard()`. Add `import androidx.compose.ui.text.input.KeyboardType` (the file already imports `KeyboardOptions`, `OutlinedTextField`, `mutableStateOf`, `rememberSaveable`, `collectAsStateWithLifecycle`). Add this composable right after `SettingsScreen`:
```kotlin
@Composable
private fun OfficeConsoleCard(vm: BobAiViewModel) {
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    var code by rememberSaveable { mutableStateOf("") }
    SectionCard(
        title = "Office console",
        accentTone = if (pairing.paired) Tone.OK else Tone.WARN,
        trailingHeader = { TechnicalBadge("SYNC", if (pairing.paired) "PAIRED" else "NOT PAIRED") },
    ) {
        if (pairing.paired) {
            StatusChip("Paired to ${pairing.userName}", Tone.OK)
            Text(
                "This phone signs in to BobAI with its own key. Jobs and manuals will come from the office console.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SecondaryAction("Unpair this phone", { vm.unpairPhone() })
        } else {
            Text(
                "Ask your admin for a 6-digit pairing code from the BobAI console, then enter it here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter(Char::isDigit).take(6) },
                label = { Text("Pairing code") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            )
            SecondaryAction(
                if (pairing.busy) "Pairing…" else "Pair this phone",
                { vm.pairPhone(code) },
                enabled = !pairing.busy && code.length == 6,
            )
        }
        pairing.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
```

- [ ] **Step 8: Build and run all phone tests**

Run: `GEMGLASSES_BACKEND_URL=http://localhost:8787 ./gradlew -q -Pgemglasses.useRealGlasses=true assembleDebug testDebugUnitTest`
Then count results:
```bash
python3 -I -c "
import glob,xml.etree.ElementTree as E
t=f=0
for p in glob.glob('app/build/test-results/testDebugUnitTest/*.xml'):
  r=E.parse(p).getroot(); t+=int(r.get('tests')); f+=int(r.get('failures'))+int(r.get('errors'))
print('tests',t,'failures',f)"
```
Expected: `tests 102 failures 0` (96 existing + 6 new).

- [ ] **Step 9: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/lpecom/gemglasses/bobai/sync app/src/main/java/com/lpecom/gemglasses/gemini/TokenProvider.kt app/src/main/java/com/lpecom/gemglasses/di/AppModule.kt app/src/main/java/com/lpecom/gemglasses/bobai/ui/BobAiViewModel.kt app/src/main/java/com/lpecom/gemglasses/bobai/ui/Screens.kt app/src/test/java/com/lpecom/gemglasses/bobai/sync app/src/test/java/com/lpecom/gemglasses/gemini/TokenProviderTest.kt
git commit -m "app: pair with the office console; use the device key for Gemini tokens"
```

---

### Task 7: Local end-to-end check on the Fold, and docs

**Files:**
- Modify: `backend/README.md`
- Create: `console/README.md`

**Interfaces:**
- Consumes: everything above.

- [ ] **Step 1: Local dev settings**

`backend/.dev.vars` holds the owner's Gemini key; never print or rewrite it. Ask the owner which email to use as the bootstrap admin, then append the two dev-only lines without reading the file (substitute that email):
```bash
cd backend
grep -q '^ENVIRONMENT=' .dev.vars || printf '\nENVIRONMENT="dev"\n' >> .dev.vars
grep -q '^BOOTSTRAP_ADMIN_EMAIL=' .dev.vars || printf 'BOOTSTRAP_ADMIN_EMAIL="owner@example.com"\n' >> .dev.vars
```

- [ ] **Step 2: Start the API with the schema applied**

Stop the running `wrangler dev` (the old token service on port 8787) first, then:
```bash
cd backend && npm run db:migrate:local && npx wrangler dev --port 8787 --ip 127.0.0.1
```
Expected: `0001_foundation.sql` applied; server listening on 127.0.0.1:8787.

- [ ] **Step 3: Start the console**

```bash
cd console && DEV_USER_EMAIL="owner@example.com" npm run dev
```
Open http://localhost:5173. Expected: "Signed in as owner@example.com (admin)".

- [ ] **Step 4: Pair the Fold**

1. In the console, add a technician (role technician).
2. Click "Pairing code" on that row; note the 6 digits.
3. Install the new APK and forward the port: `adb -s RFCY81PZCTH install -r app/build/outputs/apk/debug/app-debug.apk` and `adb -s RFCY81PZCTH reverse tcp:8787 tcp:8787`.
4. On the phone: Settings → Office console → enter the code → Pair this phone.

Expected: the card shows "Paired to <name>"; after a reload the console's Paired phones table shows `SM-F966U` with a recent Last seen.

- [ ] **Step 5: Voice still works with the device key**

Start voice on the Assistant screen and ask Bob a question.
Expected: Bob answers; `wrangler dev` output shows `POST /token` 200; `adb logcat -d -s 'BobVoice:*'` shows no token error.

- [ ] **Step 6: Revocation works**

In the console, Remove phone → confirm. On the phone, end voice and start it again.
Expected: voice fails to connect and the log shows the token endpoint returned 401 `device_revoked`. Issue a new code and pair again to restore it.

- [ ] **Step 7: Docs**

In `backend/README.md`, add a "Routes" section listing the three route groups (legacy `/token` + `/places`; `/api/admin/*` with Access or the dev header; `/api/device/*` with a device key), a "Local development" section with Steps 1–2 above and `npm test`, and a "Before the first deploy" section: run `npx wrangler d1 create bobai` and put the id in `wrangler.toml`; run `npx wrangler d1 migrations apply bobai --remote`; put a Cloudflare Access application in front of the console and set `ACCESS_TEAM_DOMAIN`, `ACCESS_AUD` and `BOOTSTRAP_ADMIN_EMAIL`; never set `ENVIRONMENT` in production; deploy only with the owner's approval.

`console/README.md`:
```markdown
# BobAI Console

Office web app for managing BobAI: people, phones, and (in later steps)
manuals, reference packs, jobs, equipment and session review.

## Local development

1. Start the API: `cd ../backend && npm run db:migrate:local && npx wrangler dev --port 8787 --ip 127.0.0.1`
   (`backend/.dev.vars` must contain `ENVIRONMENT="dev"` and `BOOTSTRAP_ADMIN_EMAIL`).
2. Start the console: `DEV_USER_EMAIL="you@example.com" npm run dev` and open http://localhost:5173.
   The dev server adds `X-Dev-User-Email` to API calls; the API accepts it only when `ENVIRONMENT` is `dev`.

## Tests

`npm test`

## Production

Served by Cloudflare Pages behind a Cloudflare Access application. Access
signs people in; the API checks each person's role. Deploy only when the
owner approves.
```

- [ ] **Step 8: Run every test suite**

```bash
(cd backend && npm test && npm run typecheck)
(cd console && npm test && npm run build)
GEMGLASSES_BACKEND_URL=http://localhost:8787 ./gradlew -q -Pgemglasses.useRealGlasses=true testDebugUnitTest
```
Expected: backend 33 passed, console 5 passed, phone 102 passed; no type errors.

- [ ] **Step 9: Commit**

```bash
git add backend/README.md console/README.md
git commit -m "docs: console foundation local setup and deploy prerequisites"
```

---

## Out of this plan (later plans)

- Plan 2: manuals → reference packs (R2, PDF text extraction, pack build/publish, phone pack sync through the existing verified installs in `bobai/sync/PackSync.kt`).
- Plan 3: jobs and phone job sync.
- Plan 4: equipment screens; full people & devices management (deactivate and role-change UI on top of the PATCH API built here).
- Plan 5: session review, record upload, audit log screen, dashboard.
- Retiring `TOKEN_APP_SECRET` once every phone is paired. Until then, removing a phone in the console does not stop an app build that still holds the shared secret.
