import type { MiddlewareHandler } from "hono";
import type { AppEnv, Env } from "./env";
import { ApiError, jsonResponse } from "./lib/errors";
import { timingSafeEqual } from "./lib/crypto";
import { deviceAuth } from "./auth/device";

const GEMINI_API = "https://generativelanguage.googleapis.com/v1beta";
// Keep in sync with Models.GEMINI_LIVE_MODEL in the app.
const LIVE_MODEL = "models/gemini-3.8-live";
const GROUNDING_MODEL = "gemini-2.5-flash";

/** Legacy routes accept the shared app secret or a paired device key. */
export const legacyAuth: MiddlewareHandler<AppEnv> = async (c, next) => {
  const secret = c.req.header("X-App-Secret") ?? "";
  if (secret && timingSafeEqual(secret, c.env.TOKEN_APP_SECRET)) return next();
  if (c.req.header("Authorization")) return deviceAuth(c, next);
  throw new ApiError(401, "unauthorized", "Not authorized.");
};

/**
 * Exchanges the API key for an ephemeral auth token scoped to the Live API.
 * The token is single-session, short-TTL, and safe to hand to the client.
 */
export async function mintToken(env: Env): Promise<Response> {
  const now = Date.now();
  const body = {
    // ~30 min window to start a session; a single new session may be opened.
    expireTime: new Date(now + 30 * 60_000).toISOString(),
    newSessionExpireTime: new Date(now + 2 * 60_000).toISOString(),
    uses: 1,
    // REST name is bidiGenerateContentSetup ("liveConnectConstraints" is the
    // SDK name and is rejected by the REST API). Verified against the API's
    // own schema validation on 2026-11-18.
    bidiGenerateContentSetup: {
      model: LIVE_MODEL,
      generationConfig: { responseModalities: ["AUDIO"] },
    },
    // Without a mask the token's setup REPLACES the app's setup entirely,
    // dropping its system instruction, tools and voice. Lock only these.
    // sessionResumption stays out so the app's resume handle survives.
    fieldMask: "model,generationConfig.responseModalities",
  };

  const res = await fetch(`${GEMINI_API}/auth_tokens?key=${env.GEMINI_API_KEY}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });

  if (!res.ok) {
    console.error("token upstream failed", res.status, await res.text());
    throw new ApiError(502, "token_upstream", "The AI service refused the token request. Try again.");
  }

  const data = (await res.json()) as { name?: string; token?: string; expireTime?: string };
  // The API returns the token as `name` (tokens/xxx) or `token` depending on version.
  const token = data.token ?? data.name;
  if (!token) throw new ApiError(502, "token_missing", "The AI service returned no token. Try again.");

  return jsonResponse({ token, expiresAt: data.expireTime ?? body.expireTime });
}

/**
 * Runs a grounded place search and returns a short spoken summary plus the
 * cited places (title + uri) the app must display per Maps ToS.
 */
export async function searchPlaces(request: Request, env: Env): Promise<Response> {
  let input: { query?: string; latitude?: number; longitude?: number };
  try {
    input = (await request.json()) as typeof input;
  } catch {
    throw new ApiError(400, "bad_json", "The request body is not valid JSON.");
  }
  const { query, latitude, longitude } = input;
  if (!query) throw new ApiError(400, "missing_query", "Say what to search for.");

  const payload: Record<string, unknown> = {
    contents: [{ role: "user", parts: [{ text: query }] }],
    tools: [{ googleMaps: {} }],
    systemInstruction: {
      parts: [
        {
          text:
            "Responda em português, curto, listando 2-4 lugares reais e próximos. " +
            "Não invente estabelecimentos.",
        },
      ],
    },
  };

  if (typeof latitude === "number" && typeof longitude === "number") {
    payload.toolConfig = { retrievalConfig: { latLng: { latitude, longitude } } };
  }

  const res = await fetch(
    `${GEMINI_API}/models/${GROUNDING_MODEL}:generateContent?key=${env.GEMINI_API_KEY}`,
    {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload),
    },
  );

  if (!res.ok) {
    console.error("places upstream failed", res.status, await res.text());
    throw new ApiError(502, "places_upstream", "The place search failed. Try again.");
  }

  const data = (await res.json()) as GeminiResponse;
  const candidate = data.candidates?.[0];
  const summary =
    candidate?.content?.parts?.map((p) => p.text ?? "").join(" ").trim() || "Não encontrei lugares.";

  const places = (candidate?.groundingMetadata?.groundingChunks ?? [])
    .map((chunk) => chunk.maps ?? chunk.web)
    .filter((c): c is { title?: string; uri: string } => !!c?.uri)
    .map((c) => ({ title: c.title ?? c.uri, uri: c.uri }));

  return jsonResponse({ summary, places });
}

interface GeminiResponse {
  candidates?: Array<{
    content?: { parts?: Array<{ text?: string }> };
    groundingMetadata?: {
      groundingChunks?: Array<{
        maps?: { title?: string; uri: string };
        web?: { title?: string; uri: string };
      }>;
    };
  }>;
}
