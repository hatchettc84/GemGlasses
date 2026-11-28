package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.workmode.ActivationTrigger
import com.lpecom.gemglasses.bobai.workmode.EngineStart
import com.lpecom.gemglasses.bobai.workmode.MicRoute
import com.lpecom.gemglasses.bobai.workmode.RouteKind
import com.lpecom.gemglasses.bobai.workmode.WakeHandoff
import com.lpecom.gemglasses.bobai.workmode.WakeWordEngine
import com.lpecom.gemglasses.bobai.workmode.WorkEvent
import com.lpecom.gemglasses.bobai.workmode.WorkModeMachine
import com.lpecom.gemglasses.bobai.workmode.WorkModeState
import com.lpecom.gemglasses.bobai.workmode.WorkPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeHandoffTest {

    /** Records the order of calls so the test can prove stop happens before the wake is dispatched. */
    private class FakeEngine(private val log: MutableList<String>) : WakeWordEngine {
        override val displayName = "Fake"
        var starts = 0
        var stops = 0
        private var onWake: (() -> Unit)? = null

        override fun start(route: MicRoute, onWake: () -> Unit, onFailed: (String) -> Unit): EngineStart {
            starts++
            log += "start"
            this.onWake = onWake
            return EngineStart.Started
        }

        override fun stop() {
            stops++
            log += "stop"
            onWake = null
        }

        fun fire() = onWake?.invoke()
    }

    private val route = MicRoute(RouteKind.PHONE)

    @Test fun `firing the wake stops the engine before dispatching WakeDetected`() {
        val log = mutableListOf<String>()
        val engine = FakeEngine(log)
        val events = mutableListOf<WorkEvent>()
        val handoff = WakeHandoff(engine) { log += "dispatch:$it"; events += it }

        assertSame(EngineStart.Started, handoff.arm(route))
        engine.fire()

        assertEquals(1, engine.stops)
        assertEquals(listOf(WorkEvent.WakeDetected), events)
        assertEquals(listOf("start", "stop", "dispatch:${WorkEvent.WakeDetected}"), log)
    }

    @Test fun `wake word then acknowledgement makes assistance active`() {
        var s = WorkModeState()
        val engine = FakeEngine(mutableListOf())
        val handoff = WakeHandoff(engine) { s = WorkModeMachine.reduce(s, it) }

        s = WorkModeMachine.reduce(s, WorkEvent.Enable)
        handoff.arm(route)
        s = WorkModeMachine.reduce(s, WorkEvent.EngineReady(route))
        assertTrue(s.heyBobArmed)
        engine.fire()
        assertEquals(WorkPhase.WAKE_DETECTED, s.phase)
        s = WorkModeMachine.reduce(s, WorkEvent.Acknowledged)

        assertEquals(WorkPhase.ACTIVE, s.phase)
        assertEquals(ActivationTrigger.WAKE_WORD, s.trigger)
    }

    @Test fun `wake detected while active is ignored`() {
        val active = listOf(WorkEvent.Enable, WorkEvent.EngineReady(route), WorkEvent.WakeDetected, WorkEvent.Acknowledged)
            .fold(WorkModeState(), WorkModeMachine::reduce)
        assertEquals(WorkPhase.ACTIVE, active.phase)

        val after = WorkModeMachine.reduce(active, WorkEvent.WakeDetected)
        assertEquals(active, after)
    }
}
