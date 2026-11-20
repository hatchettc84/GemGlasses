import type { Context } from "hono";

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
  ) {
    super(message);
  }
}

export function errorBody(code: string, message: string) {
  return { error: code, message };
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json" },
  });
}

/** Hono onError: ApiErrors become their JSON body; anything else is logged and hidden. */
export function handleError(err: unknown, _c: Context): Response {
  if (err instanceof ApiError) return jsonResponse(errorBody(err.code, err.message), err.status);
  console.error("unhandled error", err);
  return jsonResponse(errorBody("internal", "Something went wrong. Try again."), 500);
}

export async function readJson<T>(c: Context): Promise<T> {
  try {
    return (await c.req.json()) as T;
  } catch {
    throw new ApiError(400, "bad_json", "The request body is not valid JSON.");
  }
}
