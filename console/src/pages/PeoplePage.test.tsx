import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
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

  afterEach(() => vi.unstubAllGlobals());

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
    const fetchMock = mockApi(handler);
    renderPage();
    const user = userEvent.setup();
    const row = (await screen.findByRole("cell", { name: "tia@example.com" })).closest("tr")!;
    await user.click(within(row).getByRole("button", { name: "Pairing code" }));
    expect(await screen.findByText("482913")).toBeInTheDocument();
    expect(fetchMock.mock.calls.some(([u, i]) => String(u).endsWith("/users/T1/pairing-code") && i?.method === "POST")).toBe(true);
    expect(screen.getByText(/Enter it on Tia's phone/)).toBeInTheDocument();
  });

  it("explains when the signed-in person is not an admin", async () => {
    const fetchMock = mockApi((url) =>
      url.endsWith("/me")
        ? { status: 200, body: { ...admin, role: "reviewer" } }
        : { status: 403, body: { error: "forbidden", message: "You don't have permission to do that." } },
    );
    renderPage();
    expect(await screen.findByText("Only admins can manage people and phones. Ask an admin if you need access.")).toBeInTheDocument();
    const urls = fetchMock.mock.calls.map(([u]) => String(u));
    expect(urls.some((u) => u.endsWith("/users") || u.endsWith("/devices"))).toBe(false);
  });

  it("shows the server's message when sign-in is refused", async () => {
    mockApi(() => ({ status: 403, body: { error: "not_a_user", message: "Your account is not active in BobAI. Ask an admin to add you." } }));
    renderPage();
    expect(await screen.findByText("Your account is not active in BobAI. Ask an admin to add you.")).toBeInTheDocument();
  });

  const revokeHandler: Handler = (url, init) =>
    url.endsWith("/devices/D1/revoke") ? { status: 200, body: { ok: true } } : handler(url, init);
  const revokePosts = (fetchMock: ReturnType<typeof mockApi>) =>
    fetchMock.mock.calls.filter(([u, i]) => String(u).endsWith("/devices/D1/revoke") && i?.method === "POST");

  it("removes a phone when the confirmation is accepted", async () => {
    const fetchMock = mockApi(revokeHandler);
    vi.stubGlobal("confirm", vi.fn(() => true));
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Remove phone" }));
    await waitFor(() => expect(revokePosts(fetchMock)).toHaveLength(1));
  });

  it("does not remove a phone when the confirmation is declined", async () => {
    const fetchMock = mockApi(revokeHandler);
    const confirm = vi.fn(() => false);
    vi.stubGlobal("confirm", confirm);
    renderPage();
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Remove phone" }));
    expect(confirm).toHaveBeenCalled();
    expect(revokePosts(fetchMock)).toHaveLength(0);
  });

  it("shows the server's message when adding a person fails", async () => {
    mockApi((url, init) =>
      url.endsWith("/users") && init?.method === "POST"
        ? { status: 409, body: { error: "email_taken", message: "Someone with that email already exists." } }
        : handler(url, init),
    );
    renderPage();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Email"), "tia@example.com");
    await user.type(screen.getByLabelText("Name"), "Tia");
    await user.click(screen.getByRole("button", { name: "Add person" }));
    expect(await screen.findByText("Someone with that email already exists.")).toBeInTheDocument();
  });

  it("shows the server's message when a pairing code can't be issued", async () => {
    mockApi((url, init) =>
      url.endsWith("/users/T1/pairing-code")
        ? { status: 429, body: { error: "too_many_phones", message: "Tia already has the maximum number of phones." } }
        : handler(url, init),
    );
    renderPage();
    const user = userEvent.setup();
    const row = (await screen.findByRole("cell", { name: "tia@example.com" })).closest("tr")!;
    await user.click(within(row).getByRole("button", { name: "Pairing code" }));
    expect(await screen.findByText("Tia already has the maximum number of phones.")).toBeInTheDocument();
  });

  it("shows a readable message when the server reply isn't JSON", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response("<html>Sign in</html>", { status: 200 })));
    renderPage();
    expect(await screen.findByText("The server sent a reply the console couldn't read. Refresh the page and sign in again.")).toBeInTheDocument();
  });

  it("shows a readable message when the network is down", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => { throw new TypeError("Failed to fetch"); }));
    renderPage();
    expect(await screen.findByText("Couldn't reach the server. Check your connection and try again.")).toBeInTheDocument();
  });

  it("shows loading text while people and phones load", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (String(url).endsWith("/me")) return new Response(JSON.stringify(admin), { status: 200 });
      return new Promise<Response>(() => {});
    }));
    renderPage();
    await screen.findByText("Signed in as boss@example.com (admin)");
    expect(screen.getAllByText("Loading…")).toHaveLength(2);
    expect(screen.queryByText("No phones paired yet.")).not.toBeInTheDocument();
  });
});
