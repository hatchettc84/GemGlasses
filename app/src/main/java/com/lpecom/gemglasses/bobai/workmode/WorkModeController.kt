package com.lpecom.gemglasses.bobai.workmode

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import com.lpecom.gemglasses.bobai.live.BobVoiceAssistant
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Runs the Work Mode state machine on the phone.
 *
 * "Hey Bob" listens with [engine] (Android's on-device speech recognizer in
 * the app, see [SpeechWakeWordEngine]). While Work Mode is on,
 * [WakeListenService] — a microphone foreground service started from the
 * enable tap — keeps listening possible with the screen locked.
 *
 * Microphone hand-off: the detector and the Gemini Live session never hold
 * the microphone together. On a wake, [WakeHandoff] stops the engine
 * synchronously (on the main thread) before WakeDetected is dispatched, and
 * [WakeSessionCoordinator] (process-scoped) starts the voice session. A manual
 * start stops the engine the same way. The engine is only started again by
 * [endAssistance], and [WorkModeCore] refuses to start it while voice is on.
 * The decisions live in [WorkModeCore] so they are JVM tested.
 *
 * Work Mode state is in memory only, so it never survives process death — a
 * killed app can't leave a stale "armed" indicator behind.
 */
@Singleton
class WorkModeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: WakeWordEngine,
    voiceProvider: Provider<BobVoiceAssistant>,
) {
    private val core = WorkModeCore(
        engine = engine,
        platform = object : WorkModePlatform {
            override fun hasMicPermission() = this@WorkModeController.hasMicPermission()
            override fun probeRoute() = this@WorkModeController.probeRoute()
            override fun startListenService() = WakeListenService.start(context)
            override fun stopListenService() = WakeListenService.stop(context)
        },
        voice = object : VoiceLink {
            override val active get() = voiceProvider.get().state.value.active
            override fun stop() = voiceProvider.get().stop()
        },
        log = { Log.i(TAG, it) },
    )

    val state: StateFlow<WorkModeState> = core.state

    val engineName: String get() = engine.displayName

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Call from the user's tap, after the RECORD_AUDIO request has been answered. */
    fun enable() = core.enable()

    /** Also ends a voice session the wake word started, which relied on Work Mode's service. */
    fun disable() = core.disable()

    /** Manual fallback; always available while Work Mode is on. Frees the microphone first. */
    fun startAssistance(): Boolean = core.startAssistance()

    /** Bob is answering the wake word: WAKE_DETECTED -> ACTIVE. */
    fun acknowledge() = core.acknowledge()

    /** Call only once the voice session is off, so Bob's own voice can't wake the detector. */
    fun endAssistance() = core.endAssistance()

    /** A wake-word session ended for inactivity (voice already off): re-arm. */
    fun idleTimeout() = core.idleTimeout()

    /** Re-check permission and route when the app returns to the foreground. */
    fun recheck() = core.recheck()

    /**
     * Which microphone audio would come from, without opening it. A Bluetooth
     * headset here is usually the glasses, but this is not verified until audio
     * is actually captured from it.
     */
    private fun probeRoute(): MicRoute {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        // A hands-free (HFP) headset's mic only appears as an input while its
        // audio link is open, so also look at the communication devices.
        val bt = inputs.firstOrNull { it.isBluetoothMic() }
            ?: if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.availableCommunicationDevices.firstOrNull { it.isBluetoothMic() }
            } else {
                null
            }
        return when {
            bt != null -> MicRoute(RouteKind.BLUETOOTH_HEADSET, bt.productName?.toString()?.takeIf { it.isNotBlank() })
            inputs.any { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC } -> MicRoute(RouteKind.PHONE)
            else -> MicRoute(RouteKind.NONE)
        }
    }

    private fun AudioDeviceInfo.isBluetoothMic() =
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET)

    companion object {
        const val NO_PERMISSION = WorkModeCore.NO_PERMISSION
        private const val TAG = "WorkMode"
    }
}
