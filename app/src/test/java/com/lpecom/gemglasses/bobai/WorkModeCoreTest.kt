package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.workmode.ActivationTrigger
import com.lpecom.gemglasses.bobai.workmode.EngineStart
import com.lpecom.gemglasses.bobai.workmode.MicRoute
import com.lpecom.gemglasses.bobai.workmode.RouteKind
import com.lpecom.gemglasses.bobai.workmode.VoiceLink
import com.lpecom.gemglasses.bobai.workmode.WakeWordEngine
import com.lpecom.gemglasses.bobai.workmode.WorkModeCore
import com.lpecom.gemglasses.bobai.workmode.WorkModePlatform
import com.lpecom.gemglasses.bobai.workmode.WorkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives the real Work Mode core (engine hand-off, state machine, service, voice) on the JVM. */
class WorkModeCoreTest {

    private class FakeEngine : WakeWordEngine {
        override val displayName = "Fake"
        var starts = 0
        var running = false
        var failNextStart: String? = null
        private var onWake: (() -> Unit)? = null
        private var onFailed: ((String) -> Unit)? = null

        override fun start(route: MicRoute, onWake: () -> Unit, onFailed: (String) -> Unit): EngineStart {
            failNextStart?.let { failNextStart = null; return EngineStart.Failed(it) }
            starts++
            running = true
            this.onWake = onWake
            this.onFailed = onFailed
            return EngineStart.Started
        }

        override fun stop() {
            running = false
            onWake = null
            onFailed = null
        }

        fun fire() = onWake?.invoke()
        fun breakDown(reason: String) = onFailed?.invoke(reason)
    }

    private class FakePlatform : WorkModePlatform {
        var mic = true
        var serviceUp = false
        var serviceStarts = 0
        /** Survives a "process death": tests build a new core on the same platform. */
        var remembered = false
        override fun rememberedOn() = remembered
        override fun rememberOn(on: Boolean) { remembered = on }
        override fun hasMicPermission() = mic
        override fun probeRoute() = MicRoute(RouteKind.PHONE)
        override fun startListenService(): Boolean { serviceStarts++; serviceUp = true; return true }
        override fun stopListenService() { serviceUp = false }
    }

    private class FakeVoice : VoiceLink {
        override var active = false
        var stops = 0
        override fun stop() { stops++; active = false }
    }

    private val engine = FakeEngine()
    private val platform = FakePlatform()
    private val voice = FakeVoice()
    private val core = WorkModeCore(engine, platform, voice)

    /** What the ViewModel does on WAKE_DETECTED: acknowledge, then voice starts. */
    private fun wakeAndTalk() {
        engine.fire()
        assertEquals(WorkPhase.WAKE_DETECTED, core.state.value.phase)
        core.acknowledge()
        voice.active = true
    }

    /** Android kills the process: its service goes too, and a new core starts DISABLED with the same storage. */
    private fun processDeath(newEngine: FakeEngine): WorkModeCore {
        platform.serviceUp = false
        return WorkModeCore(newEngine, platform, voice)
    }

    @Test fun `enable and disable remember the technician's choice`() {
        core.enable()
        assertTrue(platform.remembered)
        core.disable()
        assertFalse(platform.remembered)
    }

    @Test fun `after the app is closed, coming back re-arms Hey Bob if it was left on`() {
        core.enable()
        val restarted = processDeath(FakeEngine())
        assertEquals(WorkPhase.DISABLED, restarted.state.value.phase)
        restarted.recheck()
        assertTrue(restarted.state.value.heyBobArmed)
        assertTrue(platform.serviceUp)
    }

    @Test fun `coming back leaves Hey Bob off if the technician turned it off`() {
        core.enable()
        core.disable()
        val restarted = processDeath(FakeEngine())
        restarted.recheck()
        assertEquals(WorkPhase.DISABLED, restarted.state.value.phase)
        assertFalse(platform.serviceUp)
    }

    @Test fun `restore without microphone permission does not listen`() {
        core.enable()
        platform.mic = false
        val restartedEngine = FakeEngine()
        val restarted = processDeath(restartedEngine)
        restarted.recheck()
        assertFalse(restartedEngine.running)
        assertFalse(restarted.state.value.heyBobArmed)
        assertFalse(platform.serviceUp)
    }

    @Test fun `enable arms the engine and then starts the listening service`() {
        core.enable()
        assertTrue(core.state.value.heyBobArmed)
        assertTrue(engine.running)
        assertTrue(platform.serviceUp)
    }

    @Test fun `session end re-arms the engine through the real path`() {
        core.enable()
        wakeAndTalk()
        assertFalse(engine.running)
        assertEquals(ActivationTrigger.WAKE_WORD, core.state.value.trigger)

        voice.active = false
        core.endAssistance()

        assertEquals(2, engine.starts)
        assertTrue(engine.running)
        assertTrue(core.state.value.heyBobArmed)
        assertNull(core.state.value.trigger)
    }

    // Fix 1: the recognizer never runs while voice holds the mic.

    @Test fun `enable while voice is on goes straight to active without starting the recognizer`() {
        voice.active = true
        core.enable()

        assertEquals(WorkPhase.ACTIVE, core.state.value.phase)
        assertEquals(0, engine.starts)
        assertFalse(engine.running)
        // Started from the tap, so it can keep the mic for the re-arm later.
        assertTrue(platform.serviceUp)

        // The voice-off watcher then re-arms.
        voice.active = false
        core.endAssistance()
        assertTrue(core.state.value.heyBobArmed)
        assertEquals(1, engine.starts)
    }

    @Test fun `ending assistance while voice is still on does not start the recognizer`() {
        core.enable()
        wakeAndTalk()
        core.endAssistance() // voice still active
        assertEquals(WorkPhase.ACTIVE, core.state.value.phase)
        assertEquals(1, engine.starts)
        assertFalse(engine.running)
    }

    @Test fun `recheck re-enable while voice is on does not start the recognizer`() {
        platform.mic = false
        core.enable()
        assertEquals(WorkPhase.UNAVAILABLE, core.state.value.phase)
        platform.mic = true
        voice.active = true
        core.recheck()
        assertEquals(WorkPhase.ACTIVE, core.state.value.phase)
        assertEquals(0, engine.starts)
    }

    // Fix 2: turning Work Mode off ends a session the wake word started.

    @Test fun `disable during a wake-started session stops voice and the service`() {
        core.enable()
        wakeAndTalk()
        core.disable()

        assertEquals(WorkPhase.DISABLED, core.state.value.phase)
        assertEquals(1, voice.stops)
        assertFalse(voice.active)
        assertFalse(platform.serviceUp)
        assertFalse(engine.running)
    }

    @Test fun `disable leaves a manually started voice session alone`() {
        core.enable()
        assertTrue(core.startAssistance())
        voice.active = true
        core.disable()
        assertEquals(0, voice.stops)
        assertTrue(voice.active)
        assertFalse(platform.serviceUp)
    }

    // Fix 3: the service never claims to listen when nothing is.

    @Test fun `failed arming never starts the listening service`() {
        engine.failNextStart = "no recognizer"
        core.enable()
        assertEquals(WorkPhase.UNAVAILABLE, core.state.value.phase)
        assertEquals(0, platform.serviceStarts)
        assertFalse(platform.serviceUp)
    }

    @Test fun `engine failing after start reports unavailable and stops the service`() {
        core.enable()
        assertTrue(platform.serviceUp)
        engine.breakDown("Hey Bob stopped working")

        assertEquals(WorkPhase.UNAVAILABLE, core.state.value.phase)
        assertEquals("Hey Bob stopped working", core.state.value.unavailableReason)
        assertFalse(core.state.value.heyBobArmed)
        assertFalse(engine.running)
        assertFalse(platform.serviceUp)
    }

    @Test fun `re-arm failure after a session stops the service`() {
        core.enable()
        wakeAndTalk()
        voice.active = false
        engine.failNextStart = "no recognizer"
        core.endAssistance()
        assertEquals(WorkPhase.UNAVAILABLE, core.state.value.phase)
        assertFalse(platform.serviceUp)
    }

    @Test fun `permission lost on recheck stops engine and service`() {
        core.enable()
        platform.mic = false
        core.recheck()
        assertEquals(WorkPhase.UNAVAILABLE, core.state.value.phase)
        assertFalse(engine.running)
        assertFalse(platform.serviceUp)
    }
}
