import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { NotesPage } from "./NotesPage";

type Handler = (url: string) => { status: number; body: unknown };

function mockApi(handler: Handler) {
  const fetchMock = vi.fn(async (url: string) => {
    const { status, body } = handler(url);
    return new Response(JSON.stringify(body), { status });
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <NotesPage />
    </QueryClientProvider>,
  );
}

afterEach(() => vi.unstubAllGlobals());

const admin = { id: "A1", email: "boss@example.com", name: "Boss", role: "admin" };
const note = { id: "n1", kind: "note", title: "Order spare igniter", text: "Order the spare igniter.", jobTitle: "Replace igniter on the ABC-123 boiler", createdAt: "2026-11-27T14:53:02.000Z", done: true, updatedAt: "2026-11-27T14:53:03.000Z", technician: { id: "T1", name: "Example Tech" } };
const draft = { ...note, id: "d1", kind: "draft", title: "Email to supplier", text: "Hi,\nPlease send a quote.", jobTitle: null, done: false };

describe("NotesPage", () => {
  it("lists notes and drafts with details", async () => {
    mockApi((url) => (url.endsWith("/me") ? { status: 200, body: admin } : { status: 200, body: { stickies: [draft, note] } }));
    renderPage();
    expect(await screen.findByText("Order spare igniter")).toBeInTheDocument();
    expect(screen.getByText("Email to supplier")).toBeInTheDocument();
    expect(screen.getByText("Draft")).toBeInTheDocument();
    expect(screen.getByText("Note")).toBeInTheDocument();
    expect(screen.getAllByText(/Example Tech/)).toHaveLength(2);
    expect(screen.getByText(/Replace igniter on the ABC-123 boiler/)).toBeInTheDocument();
    expect(screen.getAllByText("Done")).toHaveLength(1);
    expect(screen.getByText(/Please send a quote/)).toHaveStyle({ whiteSpace: "pre-wrap" });
    expect(screen.getAllByRole("button", { name: "Copy" })).toHaveLength(1);
  });

  it("copies a draft's text", async () => {
    const user = userEvent.setup();
    mockApi((url) => (url.endsWith("/me") ? { status: 200, body: admin } : { status: 200, body: { stickies: [draft] } }));
    const writeText = vi.spyOn(navigator.clipboard, "writeText").mockResolvedValue();
    renderPage();
    await user.click(await screen.findByRole("button", { name: "Copy" }));
    expect(writeText).toHaveBeenCalledWith("Hi,\nPlease send a quote.");
  });

  it("filters through the API", async () => {
    const user = userEvent.setup();
    const f = mockApi((url) => (url.endsWith("/me") ? { status: 200, body: admin } : { status: 200, body: { stickies: url.includes("kind=draft") ? [draft] : [draft, note] } }));
    renderPage();
    await screen.findByText("Order spare igniter");
    await user.click(screen.getByRole("button", { name: "Drafts" }));
    await screen.findByText("Email to supplier");
    expect(screen.queryByText("Order spare igniter")).not.toBeInTheDocument();
    expect(f.mock.calls.some(([u]) => String(u).endsWith("/stickies?kind=draft"))).toBe(true);
  });

  it("shows the empty state", async () => {
    mockApi((url) => (url.endsWith("/me") ? { status: 200, body: admin } : { status: 200, body: { stickies: [] } }));
    renderPage();
    expect(await screen.findByText("No notes yet. Notes and drafts Bob saves on technicians' phones appear here.")).toBeInTheDocument();
  });

  it("blocks technicians", async () => {
    mockApi(() => ({ status: 200, body: { ...admin, role: "technician" } }));
    renderPage();
    expect(await screen.findByRole("alert")).toBeInTheDocument();
  });
});
