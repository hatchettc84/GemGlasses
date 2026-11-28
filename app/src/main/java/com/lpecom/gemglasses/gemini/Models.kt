package com.lpecom.gemglasses.gemini

/**
 * Single source of truth for Gemini model identifiers.
 *
 * Live model IDs churn frequently as Google promotes previews to GA. Keep the
 * value here and nowhere else — never inline a model string in another file.
 *
 * Checked against Google's Live API docs on 2026-11-18 (ephemeral-token and
 * WebSocket guides use `gemini-3.8-live`). If a session fails to start with
 * 404/NOT_FOUND the ID has rotated: update it here AND in backend/src/index.ts,
 * because the backend locks tokens to this model.
 */
object Models {
    /** Native-audio Live model: speech-to-speech, VAD, barge-in in one socket. */
    const val GEMINI_LIVE_MODEL = "gemini-3.8-live"

    /** REST model used for `buscar_lugares` (generateContent + Maps grounding). */
    const val GEMINI_GROUNDING_MODEL = "gemini-2.5-flash"

    /**
     * Live WebSocket endpoint for ephemeral tokens. Tokens must use the
     * "Constrained" variant; the plain BidiGenerateContent endpoint is for raw
     * API keys, which never ship in the app.
     */
    const val LIVE_WS_HOST =
        "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContentConstrained"
}
