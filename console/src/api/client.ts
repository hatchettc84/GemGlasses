export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

/** Calls the console API. Errors carry the server's plain-language message. */
export async function apiFetch<T>(path: string, init: { method?: string; body?: unknown } = {}): Promise<T> {
  const method = init.method ?? "GET";
  const mutating = method !== "GET" && method !== "HEAD";
  // Mutating calls always declare JSON (body "{}" when empty): the Worker's CSRF guard requires it.
  // FormData is sent as-is: the browser sets the multipart Content-Type (with its boundary).
  const isForm = init.body instanceof FormData;
  const hasBody = init.body !== undefined;
  let res: Response;
  try {
    res = await fetch(`/api/admin${path}`, {
      method,
      headers: isForm ? undefined : hasBody || mutating ? { "content-type": "application/json" } : undefined,
      body: isForm ? (init.body as FormData) : hasBody ? JSON.stringify(init.body) : mutating ? "{}" : undefined,
      credentials: "same-origin",
    });
  } catch {
    throw new ApiError(0, "network_error", "Couldn't reach the server. Check your connection and try again.");
  }
  const text = await res.text().catch(() => "");
  let data: unknown = null;
  let parsed = false;
  try {
    data = text ? JSON.parse(text) : null;
    parsed = text !== "";
  } catch {
    data = null;
  }
  if (!res.ok) {
    const err = (data ?? {}) as { error?: string; message?: string };
    throw new ApiError(res.status, err.error ?? "http_error", err.message ?? `The request failed (${res.status}). Try again.`);
  }
  if (!parsed) {
    throw new ApiError(res.status, "bad_response", "The server sent a reply the console couldn't read. Refresh the page and sign in again.");
  }
  return data as T;
}

export async function apiUpload<T>(path: string, form: FormData): Promise<T> {
  return apiFetch<T>(path, { method: "POST", body: form });
}
