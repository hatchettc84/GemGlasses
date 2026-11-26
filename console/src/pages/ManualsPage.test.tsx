import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ManualsPage } from "./ManualsPage";

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
      <ManualsPage />
    </QueryClientProvider>,
  );
}

const admin = { id: "A1", email: "boss@example.com", name: "Boss", role: "admin" };
const calls = (f: ReturnType<typeof mockApi>, method: string, suffix: string) =>
  f.mock.calls.filter(([u, i]) => String(u).endsWith(suffix) && i?.method === method);
afterEach(() => vi.unstubAllGlobals());

const draft = { id: "D1", title: "Boiler manual", revision: "B", filename: "b.pdf", models: ["X1", "X2"], status: "draft", version: 2, size_bytes: 10 };
const approved = { ...draft, id: "D2", title: "Pump manual", status: "approved", version: 5 };
const revoked = { ...draft, id: "D3", title: "Old manual", status: "revoked" };

describe("ManualsPage", () => {
  const handler: Handler = (url, init) => {
    const method = init?.method ?? "GET";
    if (url.endsWith("/me")) return { status: 200, body: admin };
    if (url.endsWith("/documents") && method === "GET") return { status: 200, body: { documents: [draft, approved, revoked] } };
    if (url.endsWith("/documents") && method === "POST") return { status: 201, body: { id: "N1", status: "draft" } };
    if (url.includes("/documents/") && method === "PATCH") return { status: 200, body: { ok: true } };
    return { status: 404, body: { error: "not_found", message: "Not found." } };
  };
  const rowOf = async (title: string) => (await screen.findByRole("cell", { name: title })).closest("tr")!;
  const fill = async (user: ReturnType<typeof userEvent.setup>) => {
    await user.upload(await screen.findByLabelText("File (PDF or text)"), new File(["hello"], "m.txt", { type: "text/plain" }));
    await user.type(screen.getByLabelText("Title"), "Igniter guide");
    await user.type(screen.getByLabelText("Revision"), "C");
    await user.type(screen.getByLabelText("Models (comma separated)"), "X1, X2");
    await user.click(screen.getByRole("button", { name: "Upload manual" }));
  };

  it("lists manuals with status and only offers valid actions", async () => {
    mockApi(handler);
    renderPage();
    const d = within(await rowOf("Boiler manual"));
    expect(d.getByText("Draft")).toBeInTheDocument();
    expect(d.getByRole("button", { name: "Approve" })).toBeInTheDocument();
    expect(d.getByRole("button", { name: "Revoke" })).toBeInTheDocument();
    const a = within(await rowOf("Pump manual"));
    expect(a.queryByRole("button", { name: "Approve" })).not.toBeInTheDocument();
    expect(a.getByRole("button", { name: "Revoke" })).toBeInTheDocument();
    expect(within(await rowOf("Old manual")).queryByRole("button")).not.toBeInTheDocument();
  });

  it("uploads a manual as multipart form data", async () => {
    const f = mockApi(handler);
    renderPage();
    await fill(userEvent.setup());
    await waitFor(() => expect(calls(f, "POST", "/documents")).toHaveLength(1));
    const init = calls(f, "POST", "/documents")[0][1]!;
    expect(init.body).toBeInstanceOf(FormData);
    expect(init.headers).toBeUndefined();
    const form = init.body as FormData;
    expect((form.get("file") as File).name).toBe("m.txt");
    expect(form.get("title")).toBe("Igniter guide");
    expect(form.get("revision")).toBe("C");
    expect(form.get("models")).toBe("X1, X2");
  });

  it("shows the server's message when the PDF has no text", async () => {
    mockApi((url, init) =>
      url.endsWith("/documents") && init?.method === "POST"
        ? { status: 422, body: { error: "no_text_layer", message: "This PDF has no text (it may be a scan)." } }
        : handler(url, init),
    );
    renderPage();
    await fill(userEvent.setup());
    expect(await screen.findByText("This PDF has no text (it may be a scan).")).toBeInTheDocument();
  });

  it("approves a draft with its version", async () => {
    const f = mockApi(handler);
    renderPage();
    await userEvent.setup().click(within(await rowOf("Boiler manual")).getByRole("button", { name: "Approve" }));
    await waitFor(() => expect(calls(f, "PATCH", "/documents/D1")).toHaveLength(1));
    expect(JSON.parse(String(calls(f, "PATCH", "/documents/D1")[0][1]?.body))).toEqual({ status: "approved", version: 2 });
  });

  it("asks on the page before revoking, then revokes", async () => {
    const f = mockApi(handler);
    renderPage();
    const user = userEvent.setup();
    await user.click(within(await rowOf("Pump manual")).getByRole("button", { name: "Revoke" }));
    expect(calls(f, "PATCH", "/documents/D2")).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "Yes, revoke" }));
    await waitFor(() => expect(calls(f, "PATCH", "/documents/D2")).toHaveLength(1));
    expect(JSON.parse(String(calls(f, "PATCH", "/documents/D2")[0][1]?.body))).toEqual({ status: "revoked", version: 5 });
  });

  it("does not revoke when cancelled", async () => {
    const f = mockApi(handler);
    renderPage();
    const user = userEvent.setup();
    await user.click(within(await rowOf("Pump manual")).getByRole("button", { name: "Revoke" }));
    await user.click(screen.getByRole("button", { name: "Cancel" }));
    expect(calls(f, "PATCH", "/documents/D2")).toHaveLength(0);
  });
});
