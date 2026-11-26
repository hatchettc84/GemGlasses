import { describe, expect, it } from "vitest";
import { extractPdfPages } from "../src/lib/pdf-text";
import { EMPTY_PDF, TWO_PAGES_PDF } from "./fixtures/pdfs";

const bytes = (b64: string) => Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));

describe("extractPdfPages", () => {
  it("returns one entry per page with its text", async () => {
    const pages = await extractPdfPages(bytes(TWO_PAGES_PDF));
    expect(pages.map((p) => p.number)).toEqual([1, 2]);
    expect(pages[0].body).toContain("torque 12 Nm");
    expect(pages[1].body).toContain("reset the breaker");
  });
  it("returns no pages for a PDF without a text layer", async () => {
    expect(await extractPdfPages(bytes(EMPTY_PDF))).toEqual([]);
  });
});
