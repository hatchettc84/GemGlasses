package com.lpecom.gemglasses.bobai.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.util.Log
import androidx.annotation.RequiresPermission
import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.agent.AgentController
import com.lpecom.gemglasses.audio.BluetoothAudioRouter
import com.lpecom.gemglasses.audio.MicStreamer
import com.lpecom.gemglasses.audio.SpeakerSink
import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import com.lpecom.gemglasses.gemini.SessionConfig
import com.lpecom.gemglasses.gemini.SessionEvent
import com.lpecom.gemglasses.gemini.SessionKeeper
import com.lpecom.gemglasses.gemini.protocol.FunctionCall
import com.lpecom.gemglasses.gemini.protocol.FunctionResponse
import com.lpecom.gemglasses.glasses.GlassesCameraSource
import com.lpecom.gemglasses.service.AgentForegroundService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import com.lpecom.gemglasses.audio.DeadAudioRouteException
import javax.inject.Inject
import javax.inject.Singleton

enum class VoiceStatus(val label: String) {
    OFF("Off"),
    CONNECTING("Connecting to Gemini Live…"),
    LISTENING("Listening"),
    SPEAKING("Bob is speaking"),
    PAUSED("Paused — microphone not sent"),
    RECONNECTING("Reconnecting…"),
}

data class TranscriptLine(val fromTechnician: Boolean, val text: String)

data class BobVoiceState(
    val status: VoiceStatus = VoiceStatus.OFF,
    val media: MediaStatus = MediaStatus.CAMERA_OFF,
    val transcript: List<TranscriptLine> = emptyList(),
    val lastError: String? = null,
    val framesSent: Int = 0,
) {
    val active: Boolean get() = status != VoiceStatus.OFF
}

/**
 * BobAI's Gemini Live session (addendum §5): voice assistance, plus an
 * explicitly started, time-limited live view from the glasses camera.
 *
 * - Audio goes to Google only while a session is on; Pause stops sending the
 *   microphone. Nothing is recorded.
 * - Live view sends at most one fresh frame per second through [FrameSampler]
 *   and stops by itself after [LIVE_VIEW_MAX_MS].
 * - The model gets two read-only tools ([BobLiveTools]); it cannot change the
 *   procedure.
 * - Shares [SessionKeeper] with the legacy Live voice screen, so starting
 *   this stops that one.
 * - While on, a microphone foreground service (with its ongoing
 *   notification) keeps it running with the screen locked.
 * - The transcript is a conversation log only; it is never saved as a
 *   session observation.
 */
@Singleton
class BobVoiceAssistant @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessionKeeper: SessionKeeper,
    private val micStreamer: MicStreamer,
    private val speaker: SpeakerSink,
    private val router: BluetoothAudioRouter,
    private val cameraSource: GlassesCameraSource,
    private val controller: BobAiController,
    private val legacyAgent: AgentController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var liveViewJob: Job? = null
    private var micJob: Job? = null

    @Volatile private var micPaused = false

    /** Call audio should be on the glasses (false after the silent-mic fallback). */
    @Volatile private var glassesAudioWanted = false

    /** App notices waiting for the Live session to be ready; guarded by itself. */
    private val pendingNotices = mutableListOf<String>()
    private var sessionReady = false

    private val _state = MutableStateFlow(BobVoiceState())
    val state: StateFlow<BobVoiceState> = _state.asStateFlow()

    /** Whether a token service address was configured at build time. */
    val configured: Boolean get() = BuildConfig.LIVE_BACKEND_CONFIGURED

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() {
        if (_state.value.active) return
        if (legacyAgent.running) legacyAgent.stop()
        micPaused = false
        synchronized(pendingNotices) {
            pendingNotices.clear()
            sessionReady = false
        }
        _state.value = BobVoiceState(status = VoiceStatus.CONNECTING)
        // Microphone-type foreground service: keeps the mic and socket alive
        // when the screen locks or the app is backgrounded. Android only lets
        // it start while the app is visible (a tap). A "Hey Bob" start can
        // happen with the screen locked; then Work Mode's own microphone
        // service (WakeListenService, started from a tap) is already running
        // and keeps the session alive instead.
        if (appVisible()) {
            runCatching { AgentForegroundService.start(context) }
                .onFailure { Log.w(TAG, "voice foreground service not started: ${it.javaClass.simpleName}") }
        } else {
            Log.i(TAG, "started in the background; relying on Work Mode's microphone service")
        }
        router.routeToGlasses()
        glassesAudioWanted = router.routedToBluetooth
        speaker.open()
        scope.launch { collectEvents() }
        restartMic()
        sessionKeeper.start(
            scope,
            SessionConfig(
                systemInstruction = BobLiveTools.systemInstruction(controller.activeSession()?.equipment, controller.state.value.jobs),
                voiceName = VOICE,
                languageCode = LANGUAGE,
                tools = BobLiveTools.declarations,
            ),
        )
    }

    /** Ends everything: live view, socket, mic, playback, audio routing. */
    fun stop() {
        if (!_state.value.active) return
        // First, so a live view ending during shutdown doesn't re-route audio.
        glassesAudioWanted = false
        sessionKeeper.stop()
        scope.coroutineContext.cancelChildren()
        liveViewJob = null
        speaker.close()
        router.restore()
        runCatching { AgentForegroundService.stop(context) }
            .onFailure { Log.w(TAG, "voice foreground service stop failed: ${it.javaClass.simpleName}") }
        synchronized(pendingNotices) {
            pendingNotices.clear()
            sessionReady = false
        }
        _state.value = BobVoiceState(transcript = _state.value.transcript)
    }

    /**
     * Tells Bob about something the app did (not something the technician
     * said), as a client text turn "[App notice] …". Queued until the Live
     * session is ready; dropped if voice is off.
     */
    fun sendAppNotice(text: String) {
        if (!_state.value.active) return
        val message = "[App notice] $text"
        val sendNow = synchronized(pendingNotices) {
            if (!sessionReady) pendingNotices += message
            sessionReady
        }
        if (sendNow) sessionKeeper.sendText(message)
    }

    private fun appVisible(): Boolean {
        val info = ActivityManager.RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(info)
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE &&
            info.importance != ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
    }

    fun setPaused(paused: Boolean) {
        if (!_state.value.active) return
        micPaused = paused
        if (paused) speaker.flush()
        _state.update { it.copy(status = if (paused) VoiceStatus.PAUSED else VoiceStatus.LISTENING) }
    }

    /**
     * Live view, started by a tap or when the technician asks Bob to look:
     * streams fresh glasses frames to Gemini for a limited time.
     *
     * The glasses can't carry call audio (SCO) and the camera stream at once:
     * on the Fold7 the camera got no frames and the glasses' link dropped. So
     * call audio moves to the phone while the camera runs, and goes back to
     * the glasses afterwards.
     */
    fun startLiveView(maxMs: Long = LIVE_VIEW_MAX_MS): LiveViewResult {
        if (!_state.value.active) return LiveViewResult.UNAVAILABLE
        if (liveViewJob?.isActive == true) return LiveViewResult.ALREADY_ON
        val sampler = FrameSampler(minIntervalMs = SEND_INTERVAL_MS, maxAgeMs = MAX_FRAME_AGE_MS)
        _state.update { it.copy(media = MediaStatus.CLOUD_TRANSMISSION, framesSent = 0) }
        liveViewJob = scope.launch {
            val handedOff = router.routedToBluetooth
            if (handedOff) {
                Log.i(TAG, "live view: moving call audio to the phone while the camera runs")
                router.restore()
                delay(AUDIO_HANDOFF_SETTLE_MS)
                // A stream opened on the old route reads silence after the
                // switch, which would trip the dead-mic check; reopen it.
                restartMic()
            }
            val sender = launch {
                while (isActive) {
                    sampler.nextForUpload(System.currentTimeMillis())?.let { frame ->
                        sessionKeeper.sendFrame(frame.jpeg)
                        _state.update { it.copy(framesSent = it.framesSent + 1) }
                    }
                    delay(TICK_MS)
                }
            }
            try {
                val frames = cameraSource.runBurst(maxDurationMs = maxMs, maxFrames = Int.MAX_VALUE) { jpeg ->
                    sampler.offer(TimedFrame(jpeg, System.currentTimeMillis()))
                }
                // Ended on its own (timeout or the glasses refused). The model
                // can't see that, so tell it; a user stop is reported by the tool.
                Log.i(TAG, "live view ended by itself after $frames frame(s); notifying the model")
                sessionKeeper.sendText(if (frames == 0) BobLiveTools.CAMERA_FAILED_NOTE else BobLiveTools.CAMERA_OFF_NOTE)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "live view failed", e)
                _state.update { it.copy(lastError = "Glasses camera: ${e.message ?: e.javaClass.simpleName}") }
                sessionKeeper.sendText(BobLiveTools.CAMERA_FAILED_NOTE)
            } finally {
                sender.cancel()
                _state.update { it.copy(media = MediaStatus.CAMERA_OFF) }
                if (handedOff && glassesAudioWanted && _state.value.active) {
                    Log.i(TAG, "live view ended: call audio back to the glasses")
                    router.routeToGlasses()
                    restartMic()
                }
            }
        }
        return LiveViewResult.STARTED
    }

    fun stopLiveView(): LiveViewResult {
        liveViewJob?.cancel()
        liveViewJob = null
        _state.update { it.copy(media = MediaStatus.CAMERA_OFF) }
        return LiveViewResult.STOPPED
    }

    private suspend fun collectEvents() {
        sessionKeeper.events.collect { event ->
            when (event) {
                SessionEvent.Ready -> {
                    _state.update {
                        it.copy(status = if (micPaused) VoiceStatus.PAUSED else VoiceStatus.LISTENING, lastError = null)
                    }
                    val queued = synchronized(pendingNotices) {
                        sessionReady = true
                        pendingNotices.toList().also { pendingNotices.clear() }
                    }
                    queued.forEach(sessionKeeper::sendText)
                }
                is SessionEvent.AudioChunk -> {
                    if (!micPaused) speaker.write(event.pcm)
                    _state.update { if (it.status == VoiceStatus.LISTENING) it.copy(status = VoiceStatus.SPEAKING) else it }
                }
                SessionEvent.Interrupted -> speaker.flush()
                SessionEvent.TurnComplete -> _state.update {
                    if (it.status == VoiceStatus.SPEAKING) it.copy(status = VoiceStatus.LISTENING) else it
                }
                is SessionEvent.Transcript -> appendTranscript(event.fromUser, event.text)
                is SessionEvent.ToolInvocation -> answerTools(event.calls)
                is SessionEvent.ToolCancelled -> Log.i(TAG, "tool calls cancelled: ${event.ids}")
                is SessionEvent.GoingAway -> {
                    synchronized(pendingNotices) { sessionReady = false }
                    _state.update { it.copy(status = VoiceStatus.RECONNECTING) }
                }
                is SessionEvent.Closed -> {
                    synchronized(pendingNotices) { sessionReady = false }
                    Log.w(TAG, "session closed: ${event.error?.message}")
                    if (event.error is DeviceRemovedException) {
                        // The session loop has ended for good: release mic, speaker and routing, go idle,
                        // and keep the message so the UI explains why and start() works again later.
                        stop()
                        _state.update { it.copy(lastError = event.error.message) }
                    } else {
                        _state.update { it.copy(status = VoiceStatus.RECONNECTING, lastError = describe(event.error)) }
                    }
                }
            }
        }
    }

    private fun answerTools(calls: List<FunctionCall>) {
        val responses = calls.map { call ->
            val result = runCatching {
                when (call.name) {
                    BobLiveTools.SEARCH -> {
                        val question = (call.args["question"] as? JsonPrimitive)?.content.orEmpty()
                        BobLiveTools.answerJson(
                            controller.knowledge.answer(question, controller.activeSession()?.equipment),
                        )
                    }
                    BobLiveTools.CURRENT_STEP -> {
                        val session = controller.activeSession()
                        val ps = session?.procedureState
                        val procedure = controller.procedureFor(session)
                        val node = ps?.let { controller.engine(session)?.current(it) }
                        BobLiveTools.currentStepJson(procedure, node, ps?.paused == true)
                    }
                    BobLiveTools.LIST_JOBS -> BobLiveTools.jobsJson(controller.state.value.jobs)
                    // Only on the technician's spoken request (system rule 5); the
                    // camera chip and frame count show on screen while it runs.
                    BobLiveTools.START_LIVE_VIEW -> BobLiveTools.liveViewJson(startLiveView())
                    BobLiveTools.STOP_LIVE_VIEW -> BobLiveTools.liveViewJson(stopLiveView())
                    else -> BobLiveTools.unknownTool(call.name)
                }
            }.getOrElse { e ->
                Log.e(TAG, "tool ${call.name} failed", e)
                BobLiveTools.unknownTool(call.name)
            }
            Log.i(TAG, "tool ${call.name} -> ${result["status"]}")
            FunctionResponse(id = call.id, name = call.name, response = result)
        }
        sessionKeeper.sendToolResponses(responses)
    }

    @SuppressLint("MissingPermission") // start() requires RECORD_AUDIO
    /** (Re)opens the mic on the current route; the dead-mic check applies only to the glasses. */
    private fun restartMic() {
        micJob?.cancel()
        micJob = scope.launch { pumpMic() }
    }

    private suspend fun pumpMic() {
        val send: suspend (ByteArray) -> Unit = { chunk -> if (!micPaused) sessionKeeper.sendAudio(chunk) }
        try {
            micStreamer.stream(detectDeadRoute = router.routedToBluetooth).collect(send)
        } catch (e: DeadAudioRouteException) {
            // The glasses were picked as the call device but their mic sends
            // nothing (seen with RB Meta over SCO). Drop back to the phone's
            // mic and speaker rather than leaving Bob deaf.
            Log.w(TAG, "glasses mic silent; falling back to the phone microphone")
            glassesAudioWanted = false
            router.restore()
            _state.update { it.copy(lastError = "The glasses microphone sent no sound, so Bob is using the phone's microphone and speaker.") }
            micStreamer.stream().collect(send)
        }
    }

    private fun appendTranscript(fromTechnician: Boolean, text: String) = _state.update { s ->
        val lines = s.transcript.toMutableList()
        val last = lines.lastOrNull()
        // Transcripts arrive in fragments; merge consecutive ones from the same speaker.
        if (last != null && last.fromTechnician == fromTechnician) {
            lines[lines.lastIndex] = last.copy(text = last.text + text)
        } else {
            lines += TranscriptLine(fromTechnician, text.trimStart())
        }
        s.copy(transcript = lines.takeLast(MAX_TRANSCRIPT_LINES))
    }

    private fun describe(error: Throwable?): String? {
        val msg = error?.message ?: return null
        return when {
            !configured -> "No token service is configured in this build (GEMGLASSES_BACKEND_URL)."
            // The token service reached Google, which refused its API key.
            "token_upstream" in msg && ("PERMISSION_DENIED" in msg || "API_KEY" in msg || "API key" in msg) ->
                "Google refused the token service's API key. Check GEMINI_API_KEY on the token service."
            "token_upstream" in msg -> "The token service couldn't get a token from Google: ${msg.take(120)}"
            "401" in msg || "403" in msg -> "The token service rejected this app or key: ${msg.take(120)}"
            "Failed to connect" in msg || "Unable to resolve host" in msg || "CLEARTEXT" in msg ->
                "Can't reach the token service. Is the backend running?"
            else -> msg.take(160)
        }
    }

    companion object {
        private const val TAG = "BobVoice"
        const val VOICE = "Puck"
        const val LANGUAGE = "en-US"
        const val LIVE_VIEW_MAX_MS = 60_000L
        /** Lets the glasses' Bluetooth link leave call mode before the camera stream opens. */
        const val AUDIO_HANDOFF_SETTLE_MS = 1_000L
        const val SEND_INTERVAL_MS = 1_000L
        const val MAX_FRAME_AGE_MS = 3_000L
        private const val TICK_MS = 200L
        private const val MAX_TRANSCRIPT_LINES = 40
    }
}
