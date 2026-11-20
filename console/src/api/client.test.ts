import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiFetch } from "./client";

afterEach(() => vi.unstubAllGlobals());

describe("apiFetch", () => {
  it("returns parsed JSON", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response('{"a":1}', { status: 200 })));
    expect(await apiFetch("/x")).toEqual({ a: 1 });
  });

  it("rejects an empty 200 with bad_response", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 200 })));
    await expect(apiFetch("/x")).rejects.toMatchObject({ code: "bad_response" });
  });

  it("rejects a non-JSON 200 with bad_response", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html></html>", { status: 200 })));
    const err = await apiFetch<never>("/x").catch((e: ApiError) => e);
    expect(err).toBeInstanceOf(ApiError);
    expect(err.code).toBe("bad_response");
    expect(err.message).toBe("The server sent a reply the console couldn't read. Refresh the page and sign in again.");
  });

  it("maps a fetch rejection to network_error", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("Failed to fetch"); }));
    const err = await apiFetch<never>("/x").catch((e: ApiError) => e);
    expect(err).toMatchObject({ status: 0, code: "network_error", message: "Couldn't reach the server. Check your connection and try again." });
  });

  it("uses the server's message for error statuses", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response('{"error":"email_taken","message":"Taken."}', { status: 409 })));
    await expect(apiFetch("/x")).rejects.toMatchObject({ status: 409, code: "email_taken", message: "Taken." });
  });
});
