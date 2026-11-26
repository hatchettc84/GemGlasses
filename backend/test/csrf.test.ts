import { describe, expect, it } from "vitest";
import { SELF } from "cloudflare:test";
import { BOSS, seedUser } from "./helpers";

const raw = (method: string, headers: Record<string, string>, body?: string) =>
  SELF.fetch("http://localhost/api/admin/users", {
    method,
    headers: { "X-Dev-User-Email": BOSS, ...headers },
    body,
  });

const newUser = JSON.stringify({ email: "new@example.com", name: "New", role: "technician" });

describe("admin CSRF guard", () => {
  it("refuses a mutating request with no Origin and a non-JSON content type", async () => {
    await seedUser(BOSS, "admin");
    const res = await raw("POST", { "content-type": "text/plain" }, newUser);
    expect(res.status).toBe(403);
    expect(((await res.json()) as { error: string }).error).toBe("bad_origin");
    expect((await raw("POST", {})).status).toBe(403);
  });

  it("refuses a cross-site Origin even with JSON", async () => {
    await seedUser(BOSS, "admin");
    const res = await raw("POST", { "content-type": "application/json", Origin: "https://evil.example" }, newUser);
    expect(res.status).toBe(403);
    expect(((await res.json()) as { error: string }).error).toBe("bad_origin");
  });

  it("accepts a same-origin Origin", async () => {
    await seedUser(BOSS, "admin");
    const res = await raw("POST", { "content-type": "text/plain", Origin: "http://localhost" }, newUser);
    expect(res.status).not.toBe(403);
  });

  it("accepts JSON without an Origin header", async () => {
    await seedUser(BOSS, "admin");
    const res = await raw("POST", { "content-type": "application/json; charset=utf-8" }, newUser);
    expect(res.status).toBe(201);
  });

  it("does not restrict reads", async () => {
    await seedUser(BOSS, "admin");
    expect((await raw("GET", {})).status).toBe(200);
  });
});
