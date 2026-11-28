package com.lpecom.gemglasses.bobai.workmode

/** Recognises "Hey Bob" (and "hi/okay/ok Bob") in recognizer text. Pure, so it is JVM tested. */
object WakePhrase {
    private val pattern = Regex("""\b(?:hey|hi|okay|ok)[\s,]+bob\b""", RegexOption.IGNORE_CASE)

    fun matches(text: String): Boolean = pattern.containsMatchIn(text)
}

/**
 * The wake hand-off, kept free of Android so it runs on the JVM.
 *
 * On a wake the engine is stopped first — synchronously, releasing the
 * recognizer's microphone — and only then is [WorkEvent.WakeDetected]
 * dispatched. Everything that opens the Gemini Live microphone reacts to that
 * event, so it always runs after the detector has let go of the mic, and the
 * detector stays off until the controller re-arms it after the session ends.
 */
class WakeHandoff(private val engine: WakeWordEngine, private val dispatch: (WorkEvent) -> Unit) {
    fun arm(route: MicRoute): EngineStart = engine.start(
        route,
        onWake = {
            engine.stop()
            dispatch(WorkEvent.WakeDetected)
        },
        onFailed = { reason ->
            engine.stop()
            dispatch(WorkEvent.EngineFailed(reason, route))
        },
    )
}
