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
