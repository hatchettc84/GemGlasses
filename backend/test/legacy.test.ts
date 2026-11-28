import { fetchMock } from "cloudflare:test";
import { afterEach, beforeAll, describe, expect, it } from "vitest";
import { env } from "cloudflare:test";
import app from "../src/index";
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

  it("answers 401, not 500, when TOKEN_APP_SECRET is unset", async () => {
    const req = new Request("http://localhost/token", { method: "POST", headers: { "X-App-Secret": "anything" } });
    const res = await app.fetch(req, { ...env, TOKEN_APP_SECRET: undefined });
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
