package com.lpecom.gemglasses.bobai.workmode

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * "Hey Bob" with Android's on-device speech recognizer.
 *
 * Only [SpeechRecognizer.createOnDeviceSpeechRecognizer] is used: if the phone
 * has no on-device recognizer, Work Mode reports "Hey Bob unavailable" and
 * never falls back to the cloud recognizer. Recognised text is only checked
 * against [WakePhrase] and dropped; no audio or transcript is stored, uploaded
 * or logged (WAKE-02). The log carries "wake detected" and error codes only.
 *
 * Every recognizer call runs on the main thread. [stop] is synchronous: when
 * it returns the recognizer has been cancelled and destroyed, so the caller
 * can hand the microphone to the Gemini Live session.
 */
class SpeechWakeWordEngine(private val context: Context) : WakeWordEngine {

    override val displayName = "Android on-device speech recognizer"

    private val main = Handler(Looper.getMainLooper())
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // All of the below are touched on the main thread only.
    private var recognizer: SpeechRecognizer? = null
    private var onWake: (() -> Unit)? = null
    /** Bumped on every start/stop so callbacks from an old recognizer are ignored. */
    private var generation = 0
    private var setCommunicationDevice = false
    private var modelDownloadRequested = false
    private val recentErrors = ArrayDeque<Long>()
    private val restartToken = Any()

    override fun start(route: MicRoute, onWake: () -> Unit): EngineStart = onMain {
        stopNow()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return@onMain EngineStart.Failed(NO_RECOGNIZER)
        }
        val gen = ++generation
        val created = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrElse {
            Log.w(TAG, "recognizer could not be created: ${it.javaClass.simpleName}")
            return@onMain EngineStart.Failed(NO_RECOGNIZER)
        }
        created.setRecognitionListener(Listener(gen))
        recognizer = created
        this.onWake = onWake
        recentErrors.clear()
        if (route.kind == RouteKind.BLUETOOTH_HEADSET) selectHeadsetMic()
        listen()
        EngineStart.Started
    }

    override fun stop() = onMain { stopNow() }

    private fun stopNow() {
        generation++
        main.removeCallbacksAndMessages(restartToken)
        recognizer?.let { r ->
            runCatching { r.cancel() }
            runCatching { r.destroy() }
        }
        recognizer = null
        onWake = null
        if (setCommunicationDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { audio.clearCommunicationDevice() }
        }
        setCommunicationDevice = false
    }

    private fun listen() {
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        runCatching { r.startListening(intent) }.onFailure {
            Log.w(TAG, "startListening failed: ${it.javaClass.simpleName}")
            scheduleRestart(afterError = true)
        }
    }

    private fun scheduleRestart(afterError: Boolean) {
        main.removeCallbacksAndMessages(restartToken)
        var delayMs = RESTART_MS
        if (afterError) {
            val now = SystemClock.elapsedRealtime()
            recentErrors.addLast(now)
            while (recentErrors.isNotEmpty() && now - recentErrors.first() > ERROR_WINDOW_MS) recentErrors.removeFirst()
            if (recentErrors.size >= MAX_ERRORS) {
                recentErrors.clear()
                delayMs = BACKOFF_MS
            }
        }
        val gen = generation
        main.postAtTime({ if (gen == generation) listen() }, restartToken, SystemClock.uptimeMillis() + delayMs)
    }

    /** The hand-off stops this engine from inside the callback, so it fires at most once. */
    private fun checkForWake(results: Bundle?): Boolean {
        val candidates = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION) ?: return false
        if (candidates.none(WakePhrase::matches)) return false
        val callback = onWake ?: return true
        onWake = null
        Log.i(TAG, "wake detected")
        callback()
        return true
    }

    /** Points the recognizer's capture at the glasses' Bluetooth microphone. */
    private fun selectHeadsetMic() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val headset = audio.availableCommunicationDevices.firstOrNull {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        } ?: return
        setCommunicationDevice = runCatching { audio.setCommunicationDevice(headset) }.getOrDefault(false)
        if (!setCommunicationDevice) Log.w(TAG, "could not select the headset microphone")
    }

    private inner class Listener(private val gen: Int) : RecognitionListener {
        private val current get() = gen == generation && recognizer != null

        override fun onPartialResults(partialResults: Bundle?) {
            if (current) checkForWake(partialResults)
        }

        override fun onResults(results: Bundle?) {
            if (!current) return
            if (!checkForWake(results)) scheduleRestart(afterError = false)
        }

        override fun onError(error: Int) {
            if (!current) return
            Log.i(TAG, "recognizer error $error")
            when (error) {
                // Silence or speech that wasn't recognised: the normal end of an
                // idle listening window, not a fault, so no backoff.
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                    scheduleRestart(afterError = false)
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> {
                    // The recognizer service went away; this instance is dead.
                    recreate()
                    scheduleRestart(afterError = true)
                }
                else -> {
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) requestModelDownload()
                    scheduleRestart(afterError = true)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }

    private fun recreate() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        recognizer?.let { runCatching { it.destroy() } }
        recognizer = runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(context) }.getOrNull()
            ?.also { it.setRecognitionListener(Listener(generation)) }
    }

    /** Asks the system once to fetch the on-device English model if it is missing. */
    private fun requestModelDownload() {
        if (modelDownloadRequested || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        modelDownloadRequested = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, LANGUAGE)
        runCatching { recognizer?.triggerModelDownload(intent) }
    }

    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        main.post(task)
        return task.get(MAIN_TIMEOUT_S, TimeUnit.SECONDS)
    }

    companion object {
        const val NO_RECOGNIZER = "This phone has no on-device speech recognizer, so \"Hey Bob\" can't listen. Use Start assistance."
        private const val TAG = "HeyBob"
        private const val LANGUAGE = "en-US"
        private const val RESTART_MS = 250L
        private const val BACKOFF_MS = 5_000L
        private const val ERROR_WINDOW_MS = 30_000L
        private const val MAX_ERRORS = 5
        private const val MAIN_TIMEOUT_S = 5L
    }
}
