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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
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
 * the voice session is started by whoever observes that state. A manual
 * start stops the engine the same way. The engine is only started again by
 * [endAssistance], after the voice session is off.
 *
 * Work Mode state is in memory only, so it never survives process death — a
 * killed app can't leave a stale "armed" indicator behind.
 */
@Singleton
class WorkModeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: WakeWordEngine,
) {
    private val handoff = WakeHandoff(engine, ::dispatch)
    private val _state = MutableStateFlow(WorkModeState())
    val state: StateFlow<WorkModeState> = _state.asStateFlow()

    val engineName: String get() = engine.displayName

    fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Call after the RECORD_AUDIO request has been answered. */
    fun enable() {
        dispatch(WorkEvent.Enable)
        // A microphone foreground service needs the permission when it starts.
        if (hasMicPermission()) WakeListenService.start(context)
        arm()
    }

    fun disable() {
        engine.stop()
        WakeListenService.stop(context)
        dispatch(WorkEvent.Disable)
    }

    /** Manual fallback; always available while Work Mode is on. Frees the microphone first. */
    fun startAssistance(): Boolean {
        val s = _state.value
        if (s.canStartManually || s.phase == WorkPhase.WAKE_DETECTED) engine.stop()
        dispatch(WorkEvent.ManualActivate)
        return _state.value.isActive
    }

    /** Bob is answering the wake word: WAKE_DETECTED -> ACTIVE. */
    fun acknowledge() = dispatch(WorkEvent.Acknowledged)

    /** Call only once the voice session is off, so Bob's own voice can't wake the detector. */
    fun endAssistance() {
        dispatch(WorkEvent.SessionEnded)
        if (_state.value.phase == WorkPhase.IDLE_TIMEOUT) arm()
    }

    /** Re-check permission and route when the app returns to the foreground. */
    fun recheck() {
        val s = _state.value
        if (!s.workModeOn) return
        if (!hasMicPermission()) {
            engine.stop()
            // Nothing is listening, so don't leave the "listening" notification up.
            WakeListenService.stop(context)
            dispatch(WorkEvent.ResourceLost(NO_PERMISSION))
        } else if (s.phase == WorkPhase.UNAVAILABLE && s.unavailableReason == NO_PERMISSION) {
            dispatch(WorkEvent.Disable)
            enable()
        } else if (s.phase == WorkPhase.UNAVAILABLE) {
            // Refresh the reported route without changing anything else.
            _state.update { it.copy(route = probeRoute()) }
        }
    }

    private fun arm() {
        if (!hasMicPermission()) {
            dispatch(WorkEvent.EngineFailed(NO_PERMISSION))
            return
        }
        val route = probeRoute()
        if (route.kind == RouteKind.NONE) {
            dispatch(WorkEvent.EngineFailed("No microphone is available", route))
            return
        }
        when (val r = handoff.arm(route)) {
            EngineStart.Started -> dispatch(WorkEvent.EngineReady(route))
            is EngineStart.Failed -> dispatch(WorkEvent.EngineFailed(r.reason, route))
        }
    }

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

    private fun dispatch(e: WorkEvent) {
        _state.update { WorkModeMachine.reduce(it, e) }
        Log.i(TAG, "$e -> ${_state.value.phase}")
    }

    companion object {
        const val NO_PERMISSION = "Microphone permission not granted"
        private const val TAG = "WorkMode"
    }
}
