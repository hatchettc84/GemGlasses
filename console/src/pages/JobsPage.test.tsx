import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import { JobsPage } from "./JobsPage";

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
      <JobsPage />
    </QueryClientProvider>,
  );
}

const admin = { id: "A1", email: "boss@example.com", name: "Boss", role: "admin" };
const calls = (f: ReturnType<typeof mockApi>, method: string, suffix: string) =>
  f.mock.calls.filter(([u, i]) => String(u).endsWith(suffix) && i?.method === method);
afterEach(() => vi.unstubAllGlobals());

const job = { id: "J1", title: "Fix boiler", site: "Main St", description: "", assigned_user_id: null, assigned_name: null, status: "open", revision: 3, due_at: null };
const task = { id: "K1", job_id: "J1", title: "Check valve", completed: 0, revision: 1 };

describe("JobsPage", () => {
  const handler: Handler = (url, init) => {
    const method = init?.method ?? "GET";
    if (url.endsWith("/me")) return { status: 200, body: admin };
    if (url.endsWith("/team")) return { status: 200, body: { technicians: [{ id: "T1", name: "Tia", email: "tia@example.com" }] } };
    if (url.endsWith("/jobs") && method === "GET") return { status: 200, body: { jobs: [job], tasks: [task] } };
    if (url.endsWith("/jobs") && method === "POST") return { status: 201, body: { job } };
    if (url.endsWith("/jobs/J1/tasks")) return { status: 201, body: { id: "K2" } };
    if (url.endsWith("/jobs/J1") && method === "PATCH") return { status: 200, body: { job } };
    return { status: 404, body: { error: "not_found", message: "Not found." } };
  };

  it("lists jobs with their tasks", async () => {
    mockApi(handler);
    renderPage();
    expect(await screen.findByRole("heading", { name: "Fix boiler" })).toBeInTheDocument();
    expect(screen.getByText(/Main St · Unassigned/)).toBeInTheDocument();
    expect(screen.getByLabelText("Check valve")).not.toBeChecked();
  });

  it("creates a job assigned to a technician", async () => {
    const f = mockApi(handler);
    renderPage();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("Job title"), "Replace igniter");
    await user.type(screen.getByLabelText("Site"), "Riverside Clinic");
    await user.selectOptions(await screen.findByLabelText("Assigned to"), "Tia");
    await user.click(screen.getByRole("button", { name: "Create job" }));
    await waitFor(() => expect(calls(f, "POST", "/jobs")).toHaveLength(1));
    expect(JSON.parse(String(calls(f, "POST", "/jobs")[0][1]?.body))).toEqual({
      title: "Replace igniter", site: "Riverside Clinic", description: "", assignedUserId: "T1", dueAt: null,
    });
  });

  it("adds a task to a job", async () => {
    const f = mockApi(handler);
    renderPage();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("New task for Fix boiler"), "Test pump");
    await user.click(screen.getByRole("button", { name: "Add task" }));
    await waitFor(() => expect(calls(f, "POST", "/jobs/J1/tasks")).toHaveLength(1));
    expect(JSON.parse(String(calls(f, "POST", "/jobs/J1/tasks")[0][1]?.body))).toEqual({ title: "Test pump" });
  });

  it("sends the revision when changing status", async () => {
    const f = mockApi(handler);
    renderPage();
    const user = userEvent.setup();
    await user.selectOptions(await screen.findByLabelText("Status"), "completed");
    await waitFor(() => expect(calls(f, "PATCH", "/jobs/J1")).toHaveLength(1));
    expect(JSON.parse(String(calls(f, "PATCH", "/jobs/J1")[0][1]?.body))).toEqual({ revision: 3, status: "completed" });
  });

  it("shows the server's message on a 409", async () => {
    mockApi((url, init) =>
      url.endsWith("/jobs/J1/tasks")
        ? { status: 409, body: { error: "stale", message: "This job changed. Refresh before saving." } }
        : handler(url, init),
    );
    renderPage();
    const user = userEvent.setup();
    await user.type(await screen.findByLabelText("New task for Fix boiler"), "Test pump");
    await user.click(screen.getByRole("button", { name: "Add task" }));
    expect(await screen.findByText("This job changed. Refresh before saving.")).toBeInTheDocument();
  });

  it("tells a technician to use their phone", async () => {
    mockApi((url) => (url.endsWith("/me") ? { status: 200, body: { ...admin, role: "technician" } } : { status: 403, body: { error: "forbidden", message: "No." } }));
    renderPage();
    expect(await screen.findByText(/Use your paired phone/)).toBeInTheDocument();
  });
});
