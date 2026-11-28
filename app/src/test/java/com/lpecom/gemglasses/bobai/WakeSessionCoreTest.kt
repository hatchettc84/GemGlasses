package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.workmode.ActivationTrigger
import com.lpecom.gemglasses.bobai.workmode.EngineStart
import com.lpecom.gemglasses.bobai.workmode.MicRoute
import com.lpecom.gemglasses.bobai.workmode.RouteKind
import com.lpecom.gemglasses.bobai.workmode.VoiceLink
import com.lpecom.gemglasses.bobai.workmode.WakeSessionCore
import com.lpecom.gemglasses.bobai.workmode.WakeVoice
import com.lpecom.gemglasses.bobai.workmode.WakeWordEngine
import com.lpecom.gemglasses.bobai.workmode.WakeWorkMode
import com.lpecom.gemglasses.bobai.workmode.WorkModeCore
import com.lpecom.gemglasses.bobai.workmode.WorkModePlatform
import com.lpecom.gemglasses.bobai.workmode.WorkModeState
import com.lpecom.gemglasses.bobai.workmode.WorkPhase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real Work Mode core and the process-scoped wake hand-off, on a virtual
 * clock. No ViewModel exists here: this is the hand-off that keeps working
 * after the Activity is destroyed while the listening service runs.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeSessionCoreTest {

    private class FakeEngine : WakeWordEngine {
        override val displayName = "Fake"
        var starts = 0
        var running = false
        private var onWake: (() -> Unit)? = null
        override fun start(route: MicRoute, onWake: () -> Unit, onFailed: (String) -> Unit): EngineStart {
            starts++; running = true; this.onWake = onWake
            return EngineStart.Started
        }
        override fun stop() { running = false; onWake = null }
        fun fire() = onWake?.invoke()
    }

    private class FakePlatform : WorkModePlatform {
        var mic = true
        override fun hasMicPermission() = mic
        override fun probeRoute() = MicRoute(RouteKind.PHONE)
        override fun startListenService() = true
        override fun stopListenService() = Unit
    }

    /** The Live session: what Work Mode sees ([VoiceLink]) and what the hand-off drives ([WakeVoice]). */
    private class FakeVoice(private val clock: () -> Long) : WakeVoice, VoiceLink {
        val activeState = MutableStateFlow(false)
        override val active get() = activeState.value
        override val activeFlow = activeState
        var lastActivity = 0L
        var starts = 0
        var stops = 0
        var greets = 0
        var failStart = false

        /** stop() does nothing (e.g. the session is already shutting down another way). */
        var ignoreStop = false
        override fun lastActivityAt() = lastActivity
        override fun prepare() = Unit
        override fun start() {
            if (failStart) throw IllegalStateException("no token")
            starts++; activeState.value = true
        }
        override fun stop() { stops++; if (!ignoreStop) activeState.value = false }
        override fun greet() { greets++ }
        fun speech() { lastActivity = clock() }
    }

    private class Rig(scope: TestScope) {
        val engine = FakeEngine()
        val platform = FakePlatform()
        val voice = FakeVoice { scope.testScheduler.currentTime }
        val events = mutableListOf<String>()
        val core = WorkModeCore(engine, platform, voice, log = { events += it })
        val session = WakeSessionCore(
            scope = scope.backgroundScope,
            workMode = object : WakeWorkMode {
                override val state: StateFlow<WorkModeState> get() = core.state
                override fun hasMicPermission() = platform.hasMicPermission()
                override fun acknowledge() = core.acknowledge()
                override fun endAssistance() = core.endAssistance()
                override fun idleTimeout() = core.idleTimeout()
            },
            voice = voice,
            clock = { scope.testScheduler.currentTime },
            settleMs = 300,
            idleMs = 45_000,
            maxMs = 600_000,
        )
        val phase get() = core.state.value.phase
    }

    private fun TestScope.armed(): Rig = Rig(this).also {
        it.session.start()
        it.core.enable()
        runCurrent()
        assertTrue(it.core.state.value.heyBobArmed)
    }

    /** "Hey Bob": the hand-off acknowledges, waits for the mic to settle, then starts voice. */
    private fun TestScope.wake(r: Rig) {
        r.engine.fire()
        runCurrent()
        assertEquals(WorkPhase.ACTIVE, r.phase)
        assertEquals(ActivationTrigger.WAKE_WORD, r.core.state.value.trigger)
        advanceTimeBy(301); runCurrent()
        assertTrue(r.voice.active)
    }

    @Test fun `a wake starts voice and Bob greets, with no screen involved`() = runTest {
        val r = armed()
        wake(r)
        assertEquals(1, r.voice.starts)
        assertEquals(1, r.voice.greets)
        assertFalse(r.engine.running)
    }

    @Test fun `a silent wake session ends after 45 s and Hey Bob re-arms via IdleTimeout`() = runTest {
        val r = armed()
        wake(r)
        advanceTimeBy(44_000); runCurrent()
        assertTrue("still on before 45 s", r.voice.active)
        advanceTimeBy(1_100); runCurrent() // 45 s after voice started
        assertFalse("voice stopped: nothing more is uploaded", r.voice.active)
        assertEquals(1, r.voice.stops)
        // Bob's audio must be off before the detector reopens the mic.
        assertFalse(r.engine.running)
        advanceTimeBy(301); runCurrent()
        assertTrue(r.events.any { it.startsWith("IdleTimeout") && it.endsWith("IDLE_TIMEOUT") })
        assertTrue(r.core.state.value.heyBobArmed)
        assertTrue(r.engine.running)
        assertEquals(2, r.engine.starts)
    }

    @Test fun `speech or Bob audio keeps the wake session open`() = runTest {
        val r = armed()
        wake(r)
        advanceTimeBy(30_000); r.voice.speech()
        advanceTimeBy(30_000); runCurrent()
        assertTrue("30 s after the last speech", r.voice.active)
        r.voice.speech()
        advanceTimeBy(44_000); runCurrent()
        assertTrue(r.voice.active)
        advanceTimeBy(1_500); runCurrent()
        assertFalse(r.voice.active)
        advanceTimeBy(301); runCurrent()
        assertTrue(r.core.state.value.heyBobArmed)
    }

    @Test fun `a wake session ended by Bob or the user re-arms without IdleTimeout`() = runTest {
        val r = armed()
        wake(r)
        r.voice.stop() // e.g. end_conversation after the goodbye
        runCurrent()
        advanceTimeBy(301); runCurrent()
        assertTrue(r.core.state.value.heyBobArmed)
        assertTrue(r.events.none { it.startsWith("IdleTimeout") })
        assertTrue(r.events.any { it.startsWith("SessionEnded") })
        // The idle timer died with the session: nothing fires later.
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, r.voice.stops)
        assertTrue(r.core.state.value.heyBobArmed)
    }

    @Test fun `a session started from the button is not ended for inactivity`() = runTest {
        val r = armed()
        assertTrue(r.core.startAssistance())
        r.voice.start()
        runCurrent()
        advanceTimeBy(10 * 60_000L); runCurrent()
        assertTrue(r.voice.active)
        assertEquals(WorkPhase.ACTIVE, r.phase)
    }

    @Test fun `if voice cannot start after a wake, Hey Bob listens again`() = runTest {
        val r = armed()
        r.voice.failStart = true
        r.engine.fire()
        runCurrent()
        advanceTimeBy(301); runCurrent()
        assertFalse(r.voice.active)
        assertTrue(r.core.state.value.heyBobArmed)
        assertEquals(0, r.voice.greets)
    }

    @Test fun `without the microphone permission a wake goes back to listening without voice`() = runTest {
        val r = armed()
        r.platform.mic = false
        r.engine.fire()
        runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(0, r.voice.starts)
        assertFalse(r.core.state.value.isActive)
    }

    @Test fun `a busy wake session still ends at the 10 minute maximum and re-arms via IdleTimeout`() = runTest {
        val r = armed()
        wake(r)
        // Someone talks every 30 s (last at 9:30), so the idle timer never fires.
        repeat(19) {
            advanceTimeBy(30_000); runCurrent()
            assertTrue(r.voice.active)
            r.voice.speech()
        }
        advanceTimeBy(29_500); runCurrent() // 9:59.5 since voice started
        assertTrue("still on just before 10 min", r.voice.active)
        advanceTimeBy(600); runCurrent()
        assertFalse("voice stopped at 10 min despite activity", r.voice.active)
        assertEquals(1, r.voice.stops)
        advanceTimeBy(301); runCurrent()
        assertTrue(r.events.any { it.startsWith("IdleTimeout") && it.endsWith("IDLE_TIMEOUT") })
        assertTrue(r.core.state.value.heyBobArmed)
        assertTrue(r.engine.running)
    }

    @Test fun `the 10 minute maximum is a wake session constant next to the idle timeout`() {
        assertEquals(600_000L, WakeSessionCore.WAKE_SESSION_MAX_MS)
        assertEquals(45_000L, WakeSessionCore.IDLE_TIMEOUT_MS)
    }

    @Test fun `an idle stop that does nothing does not label the next session end as idle`() = runTest {
        val r = armed()
        wake(r)
        r.voice.ignoreStop = true
        advanceTimeBy(45_100); runCurrent()
        assertEquals("the idle stop was attempted", 1, r.voice.stops)
        assertTrue("but voice is still on", r.voice.active)
        // Later the technician (or Bob) ends the session normally.
        r.voice.ignoreStop = false
        advanceTimeBy(5_000)
        r.voice.stop()
        runCurrent()
        advanceTimeBy(301); runCurrent()
        assertTrue(r.events.none { it.startsWith("IdleTimeout") })
        assertTrue(r.events.any { it.startsWith("SessionEnded") })
        assertTrue(r.core.state.value.heyBobArmed)
    }
}
