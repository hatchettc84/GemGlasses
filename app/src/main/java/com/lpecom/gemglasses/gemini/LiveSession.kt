package com.lpecom.gemglasses.gemini

import android.util.Base64
import android.util.Log
import com.lpecom.gemglasses.gemini.protocol.Blob
import com.lpecom.gemglasses.gemini.protocol.ClientContent
import com.lpecom.gemglasses.gemini.protocol.ClientMessage
import com.lpecom.gemglasses.gemini.protocol.Content
import com.lpecom.gemglasses.gemini.protocol.FunctionResponse
import com.lpecom.gemglasses.gemini.protocol.GenerationConfig
import com.lpecom.gemglasses.gemini.protocol.Part
import com.lpecom.gemglasses.gemini.protocol.PrebuiltVoiceConfig
import com.lpecom.gemglasses.gemini.protocol.RealtimeInput
import com.lpecom.gemglasses.gemini.protocol.RealtimeInputConfig
import com.lpecom.gemglasses.gemini.protocol.ServerMessage
import com.lpecom.gemglasses.gemini.protocol.SessionResumptionConfig
import com.lpecom.gemglasses.gemini.protocol.Setup
import com.lpecom.gemglasses.gemini.protocol.SpeechConfig
import com.lpecom.gemglasses.gemini.protocol.ToolResponse
import com.lpecom.gemglasses.gemini.protocol.VoiceConfig
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * A single Gemini Live WebSocket connection. Owns the socket lifecycle, sends
 * the setup handshake, streams audio/video, and parses server frames into a
 * cold [Flow] of [SessionEvent].
 *
 * This class is intentionally "dumb" about reconnection — [SessionKeeper] wraps
 * it and re-opens with a resumption handle when the socket drops or the server
 * sends `goAway`.
 */
class LiveSession(
    private val client: OkHttpClient,
    private val json: Json,
    private val ephemeralToken: String,
    private val systemInstruction: String,
    private val voiceName: String,
    private val languageCode: String,
    private val liveTools: List<com.lpecom.gemglasses.gemini.protocol.Tool>,
    private val resumeHandle: String?,
) {
    @Volatile private var socket: WebSocket? = null

    /**
     * Opens the socket and emits events until it closes. Collecting starts the
     * connection; cancelling the collector closes it cleanly.
     */
    fun connect(): Flow<SessionEvent> = callbackFlow {
        val url = "${Models.LIVE_WS_HOST}?access_token=$ephemeralToken"
        val request = Request.Builder().url(url).build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                // Setup must be the server's first message. Only expose the
                // socket to send() afterwards, or mic chunks queued while
                // connecting go out first and the server closes with 1007.
                webSocket.send(json.encodeToString(ClientMessage.serializer(), buildSetup()))
                socket = webSocket
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                handleFrame(bytes.utf8()).forEach { trySend(it) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleFrame(text).forEach { trySend(it) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "server closing: code=$code reason=${reason.take(200)}")
                webSocket.close(NORMAL_CLOSURE, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                // A non-normal close code carries the server's reason (bad model,
                // expired token, quota); surface it instead of failing silently.
                val error = if (code == NORMAL_CLOSURE) null else IllegalStateException("Live API closed ($code): ${reason.take(200)}")
                trySend(SessionEvent.Closed(error))
                close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "socket failure: ${t.message}")
                trySend(SessionEvent.Closed(t))
                close()
            }
        }

        val ws = client.newWebSocket(request, listener)

        awaitClose {
            ws.close(NORMAL_CLOSURE, "client closing")
            socket = null
        }
    }

    /** Streams a mic chunk (PCM 16-bit, 16 kHz, mono) to the model. */
    fun sendAudio(pcm: ByteArray) {
        val msg = ClientMessage(
            realtimeInput = RealtimeInput(
                audio = Blob(mimeType = "audio/pcm;rate=16000", data = pcm.b64()),
            ),
        )
        send(msg)
    }

    /** Streams a single JPEG frame as realtime video input. */
    fun sendFrame(jpeg: ByteArray) {
        val msg = ClientMessage(
            realtimeInput = RealtimeInput(
                video = Blob(mimeType = "image/jpeg", data = jpeg.b64()),
            ),
        )
        send(msg)
    }

    /** Returns the results of one or more tool calls to the model. */
    fun sendToolResponses(responses: List<FunctionResponse>) {
        send(ClientMessage(toolResponse = ToolResponse(functionResponses = responses)))
    }

    /** Injects a text turn (used for typed input / debugging). */
    fun sendText(text: String) {
        send(
            ClientMessage(
                clientContent = ClientContent(
                    turns = listOf(Content(role = "user", parts = listOf(Part(text = text)))),
                    turnComplete = true,
                ),
            ),
        )
    }

    fun close() {
        socket?.close(NORMAL_CLOSURE, null)
        socket = null
    }

    // --- internals -------------------------------------------------------

    private fun send(message: ClientMessage) {
        val ws = socket ?: return
        ws.send(json.encodeToString(ClientMessage.serializer(), message))
    }

    private fun buildSetup() = ClientMessage(
        setup = Setup(
            model = "models/${Models.GEMINI_LIVE_MODEL}",
            generationConfig = GenerationConfig(
                responseModalities = listOf("AUDIO"),
                speechConfig = SpeechConfig(
                    voiceConfig = VoiceConfig(PrebuiltVoiceConfig(voiceName)),
                    languageCode = languageCode,
                ),
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemInstruction))),
            tools = liveTools,
            realtimeInputConfig = RealtimeInputConfig(
                // Low start sensitivity: shop noise (fans, compressors, other
                // voices) shouldn't count as the technician talking. A longer
                // end-of-speech pause lets them finish a sentence while working.
                automaticActivityDetection = buildJsonObject {
                    put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                    put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                    put("prefixPaddingMs", 200)
                    put("silenceDurationMs", 800)
                },
                activityHandling = "START_OF_ACTIVITY_INTERRUPTS",
            ),
            // Empty JSON objects => enable transcription of both directions.
            inputAudioTranscription = EMPTY_OBJECT,
            outputAudioTranscription = EMPTY_OBJECT,
            // Empty handle => start resumable; non-null => resume prior session.
            sessionResumption = SessionResumptionConfig(handle = resumeHandle),
        ),
    )

    /**
     * One server frame can carry several things at once (e.g. a transcript
     * fragment AND an audio chunk), so every part becomes its own event.
     * Returning only the first dropped audio whenever a transcript rode along.
     */
    private fun handleFrame(raw: String): List<SessionEvent> {
        val msg = runCatching { json.decodeFromString(ServerMessage.serializer(), raw) }
            .getOrElse {
                Log.w(TAG, "unparsed frame: ${raw.take(120)}")
                return emptyList()
            }
        val events = mutableListOf<SessionEvent>()

        msg.setupComplete?.let { events += SessionEvent.Ready }
        msg.goAway?.let { events += SessionEvent.GoingAway(it.timeLeft) }
        msg.toolCallCancellation?.let { events += SessionEvent.ToolCancelled(it.ids) }
        msg.toolCall?.let {
            if (it.functionCalls.isNotEmpty()) events += SessionEvent.ToolInvocation(it.functionCalls)
        }
        msg.sessionResumptionUpdate?.let { update ->
            if (update.resumable && update.newHandle != null) {
                // Handle is stored by SessionKeeper via this hook, kept out of
                // the sealed event set to avoid leaking protocol detail.
                resumeCallback?.invoke(update.newHandle)
            }
        }

        msg.serverContent?.let { sc ->
            if (sc.interrupted == true) events += SessionEvent.Interrupted
            sc.inputTranscription?.text?.let { events += SessionEvent.Transcript(it, fromUser = true) }
            sc.modelTurn?.parts?.forEach { part ->
                part.inlineData?.let { blob ->
                    events += SessionEvent.AudioChunk(Base64.decode(blob.data, Base64.NO_WRAP))
                }
            }
            sc.outputTranscription?.text?.let { events += SessionEvent.Transcript(it, fromUser = false) }
            if (sc.turnComplete == true) events += SessionEvent.TurnComplete
        }
        return events
    }

    /** SessionKeeper registers here to capture resumption handles. */
    @Volatile var resumeCallback: ((String) -> Unit)? = null

    private fun ByteArray.b64(): String = Base64.encodeToString(this, Base64.NO_WRAP)

    private companion object {
        const val TAG = "LiveSession"
        const val NORMAL_CLOSURE = 1000
        val EMPTY_OBJECT = kotlinx.serialization.json.JsonObject(emptyMap())
    }
}
