import type { MiddlewareHandler } from "hono";
import type { AppEnv } from "../env";
import { ApiError } from "../lib/errors";

// Replaced in Task 4.
export const deviceAuth: MiddlewareHandler<AppEnv> = async () => {
  throw new ApiError(401, "unauthorized", "Not authorized.");
};
