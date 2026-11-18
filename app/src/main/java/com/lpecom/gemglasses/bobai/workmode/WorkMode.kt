package com.lpecom.gemglasses.bobai.workmode

/*
 * Work Mode and "Hey Bob" lifecycle (addendum §4). Pure Kotlin so the state
 * machine can be unit tested on the JVM; the Android side lives in
 * WorkModeController.
 *
 * DISABLED -> ARMING -> ARMED -> WAKE_DETECTED -> ACTIVE -> IDLE_TIMEOUT -> ARMED
 *
 * Any failure (no engine, permission loss, microphone loss, disconnect, OS
 * termination) lands in UNAVAILABLE with a reason. "Hey Bob armed" is only
 * ever reported in ARMED, i.e. while a detector is actually running.
 */

enum class WorkPhase {
    DISABLED,
    ARMING,
    ARMED,
    WAKE_DETECTED,
    ACTIVE,
    IDLE_TIMEOUT,
    UNAVAILABLE,
}

enum class RouteKind { BLUETOOTH_HEADSET, PHONE, NONE }

/**
 * The microphone the app expects to use. [verified] is false until audio has
 * actually been captured on that route — availability alone proves nothing.
 */
data class MicRoute(
    val kind: RouteKind,
    val deviceName: String? = null,
    val verified: Boolean = false,
) {
    fun label(): String {
        val base = when (kind) {
            RouteKind.BLUETOOTH_HEADSET -> "Bluetooth headset" + (deviceName?.let { " ($it)" } ?: "")
            RouteKind.PHONE -> "Phone microphone"
            RouteKind.NONE -> "No microphone available"
        }
        return if (kind == RouteKind.NONE || verified) base else "$base — not yet verified"
    }
}

enum class ActivationTrigger { WAKE_WORD, MANUAL }

data class WorkModeState(
    val phase: WorkPhase = WorkPhase.DISABLED,
    val route: MicRoute? = null,
    /** True only while a wake-word detector has started successfully. */
    val wakeReady: Boolean = false,
    val unavailableReason: String? = null,
    val trigger: ActivationTrigger? = null,
) {
    val workModeOn: Boolean get() = phase != WorkPhase.DISABLED

    /** The only state in which the UI may say "Hey Bob armed". */
    val heyBobArmed: Boolean get() = phase == WorkPhase.ARMED && wakeReady

    /**
     * Whether the detector should be fed audio. Off while Bob is active or
     * speaking, so Bob's own responses cannot re-trigger it.
     */
    val detectorShouldListen: Boolean get() = heyBobArmed

    /** Manual "Start assistance" is the fallback whenever Work Mode is on. */
    val canStartManually: Boolean
        get() = phase in setOf(WorkPhase.ARMED, WorkPhase.UNAVAILABLE, WorkPhase.IDLE_TIMEOUT)

    val isActive: Boolean get() = phase == WorkPhase.ACTIVE
}

sealed interface WorkEvent {
    data object Enable : WorkEvent
    data object Disable : WorkEvent
    data class EngineReady(val route: MicRoute) : WorkEvent
    data class EngineFailed(val reason: String, val route: MicRoute? = null) : WorkEvent
    data object WakeDetected : WorkEvent

    /** Bob has acknowledged the wake phrase; the assistance session begins. */
    data object Acknowledged : WorkEvent
    data object ManualActivate : WorkEvent
    data object IdleTimeout : WorkEvent
    data object SessionEnded : WorkEvent

    /** Permission revoked, microphone lost, disconnect, or OS termination. */
    data class ResourceLost(val reason: String) : WorkEvent
}

object WorkModeMachine {

    fun reduce(s: WorkModeState, e: WorkEvent): WorkModeState = when (e) {
        WorkEvent.Disable -> WorkModeState()

        WorkEvent.Enable ->
            if (s.phase == WorkPhase.DISABLED) WorkModeState(phase = WorkPhase.ARMING) else s

        is WorkEvent.EngineReady ->
            if (s.phase == WorkPhase.ARMING || s.phase == WorkPhase.IDLE_TIMEOUT) {
                WorkModeState(phase = WorkPhase.ARMED, route = e.route, wakeReady = true)
            } else {
                s
            }

        is WorkEvent.EngineFailed ->
            if (s.phase == WorkPhase.DISABLED) {
                s
            } else {
                WorkModeState(
                    phase = WorkPhase.UNAVAILABLE,
                    route = e.route ?: s.route,
                    wakeReady = false,
                    unavailableReason = e.reason,
                )
            }

        WorkEvent.WakeDetected ->
            // Ignored outside ARMED — in particular while ACTIVE, so Bob's own
            // speech can't restart the session.
            if (s.heyBobArmed) s.copy(phase = WorkPhase.WAKE_DETECTED) else s

        WorkEvent.Acknowledged ->
            if (s.phase == WorkPhase.WAKE_DETECTED) {
                s.copy(phase = WorkPhase.ACTIVE, trigger = ActivationTrigger.WAKE_WORD)
            } else {
                s
            }

        WorkEvent.ManualActivate ->
            if (s.canStartManually || s.phase == WorkPhase.WAKE_DETECTED) {
                s.copy(phase = WorkPhase.ACTIVE, trigger = ActivationTrigger.MANUAL)
            } else {
                s
            }

        WorkEvent.IdleTimeout, WorkEvent.SessionEnded ->
            // The controller re-checks the detector from here and sends
            // EngineReady (back to ARMED) or EngineFailed (UNAVAILABLE).
            if (s.phase == WorkPhase.ACTIVE || s.phase == WorkPhase.WAKE_DETECTED) {
                s.copy(phase = WorkPhase.IDLE_TIMEOUT, trigger = null, wakeReady = false)
            } else {
                s
            }

        is WorkEvent.ResourceLost ->
            if (s.phase == WorkPhase.DISABLED) {
                s
            } else {
                s.copy(
                    phase = WorkPhase.UNAVAILABLE,
                    wakeReady = false,
                    unavailableReason = e.reason,
                    trigger = null,
                )
            }
    }
}

/**
 * Local keyword spotting for a custom phrase. Implementations must run fully
 * on-device, keep any audio buffer transient, and never store or upload idle
 * audio (WAKE-02).
 */
interface WakeWordEngine {
    val displayName: String

    fun start(route: MicRoute, onWake: () -> Unit): EngineStart

    fun stop()
}

sealed interface EngineStart {
    data object Started : EngineStart
    data class Failed(val reason: String) : EngineStart
}

/**
 * Used until a custom-phrase engine has been evaluated and selected (licence,
 * offline start-up, false activations, latency, battery). It never claims to
 * listen, so Work Mode reports "Hey Bob unavailable" and offers manual start.
 */
object NoWakeWordEngine : WakeWordEngine {
    const val REASON = "No \"Hey Bob\" wake-word engine is installed yet. Use Start assistance."

    override val displayName = "None selected"

    override fun start(route: MicRoute, onWake: () -> Unit): EngineStart = EngineStart.Failed(REASON)

    override fun stop() = Unit
}
