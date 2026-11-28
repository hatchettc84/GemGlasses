import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { App } from "./App";

function renderAt(path: string) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <App />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe("App shell", () => {
  beforeEach(() => {
    // Pages load their data on mount; an empty 403 keeps them inert.
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({}), { status: 403 })));
  });

  it("shows the BobAI Admin header with nav and a link back to BobAI", () => {
    renderAt("/");
    expect(screen.getByText("BobAI Admin")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Phones & people" })).toHaveAttribute("href", "/");
    expect(screen.getByRole("link", { name: "Manuals" })).toHaveAttribute("href", "/manuals");
    expect(screen.getByRole("link", { name: "Open BobAI →" })).toHaveAttribute("href", "http://127.0.0.1:8095");
    expect(screen.queryByRole("link", { name: "Jobs" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Notes" })).not.toBeInTheDocument();
  });

  it.each(["/jobs", "/notes", "/nonsense"])("redirects %s to the People page", (path) => {
    renderAt(path);
    expect(screen.getByRole("link", { name: "Phones & people" })).toHaveClass("active");
  });

  it("keeps /manuals on the Manuals page", () => {
    renderAt("/manuals");
    expect(screen.getByRole("link", { name: "Manuals" })).toHaveClass("active");
  });
});
