package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.live.EndAfterReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class EndAfterReplyTest {

    /** The speaker on the virtual clock: audio written plays back in real time. */
    private class FakePlayer(private val now: () -> Long) {
        private var playsUntil = 0L
        fun write(ms: Long) { playsUntil = maxOf(playsUntil, now()) + ms }
        fun pendingMs() = maxOf(0L, playsUntil - now())
    }

    private fun TestScope.rig(): Triple<EndAfterReply, FakePlayer, IntArray> {
        val player = FakePlayer { testScheduler.currentTime }
        val ended = IntArray(1)
        val e = EndAfterReply(backgroundScope, player::pendingMs, graceMs = 1_500, fallbackMs = 10_000) { ended[0]++ }
        return Triple(e, player, ended)
    }

    /** Bob's goodbye arrives as audio; the reply is written to the speaker faster than it plays. */
    private fun goodbye(e: EndAfterReply, p: FakePlayer, ms: Long) { e.onAudio(); p.write(ms) }

    @Test fun `ends 1,5 s after the goodbye has finished playing, not at turn complete`() = runTest {
        val (e, p, ended) = rig()
        e.request()
        advanceTimeBy(1_000)
        goodbye(e, p, 3_000) // plays until t=4 s
        e.onTurnComplete()   // at t=1 s, while the goodbye is still playing
        advanceTimeBy(3_000 + 1_400); runCurrent() // t=5.4 s: played out, grace not over
        assertEquals(0, ended[0])
        advanceTimeBy(200); runCurrent() // t=5.6 s
        assertEquals(1, ended[0])
        assertFalse(e.requested)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, ended[0]) // the fallback was replaced, not added
    }

    @Test fun `if the tool-call turn completes before any goodbye audio, waits for the next reply to play`() = runTest {
        val (e, p, ended) = rig()
        e.request()
        e.onTurnComplete() // the turn that carried the tool call: no goodbye yet
        advanceTimeBy(2_000); runCurrent()
        assertEquals(0, ended[0])
        goodbye(e, p, 2_000) // t=2 s, plays until t=4 s
        advanceTimeBy(500)
        e.onTurnComplete()
        advanceTimeBy(1_500 + 1_400); runCurrent() // t=5.4 s
        assertEquals(0, ended[0])
        advanceTimeBy(200); runCurrent()
        assertEquals(1, ended[0])
    }

    @Test fun `ends after the 10 s fallback from the tool call when nothing arrives`() = runTest {
        val (e, _, ended) = rig()
        e.request()
        e.onTurnComplete()
        advanceTimeBy(9_900); runCurrent()
        assertEquals(0, ended[0])
        advanceTimeBy(200); runCurrent()
        assertEquals(1, ended[0])
    }

    @Test fun `at the fallback, goodbye audio already playing is allowed to finish`() = runTest {
        val (e, p, ended) = rig()
        e.request()
        advanceTimeBy(9_000)
        goodbye(e, p, 2_000) // no turn complete ever comes; plays until t=11 s
        advanceTimeBy(1_100); runCurrent() // t=10.1 s
        assertEquals(0, ended[0])
        advanceTimeBy(900 + 1_400); runCurrent() // t=12.4 s
        assertEquals(0, ended[0])
        advanceTimeBy(200); runCurrent()
        assertEquals(1, ended[0])
    }

    @Test fun `a speaker that never drains holds the session for 15 s at most`() = runTest {
        var ended = 0
        val e = EndAfterReply(backgroundScope, { 5_000L }, graceMs = 1_500, fallbackMs = 10_000, maxDrainMs = 15_000) { ended++ }
        e.request()
        e.onAudio()
        e.onTurnComplete()
        advanceTimeBy(16_400); runCurrent()
        assertEquals(0, ended)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, ended)
    }

    @Test fun `a completed turn or audio without a request does nothing, and reset cancels`() = runTest {
        val (e, p, ended) = rig()
        goodbye(e, p, 1_000)
        e.onTurnComplete()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, ended[0])
        e.request()
        goodbye(e, p, 1_000)
        e.onTurnComplete()
        e.reset()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, ended[0])
        // Audio heard before a reset does not count for the next request.
        e.request()
        e.onTurnComplete()
        advanceTimeBy(5_000); runCurrent()
        assertEquals(0, ended[0])
    }

    @Test fun `calls from several threads at once end the session exactly once`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ended = AtomicInteger()
        val e = EndAfterReply(scope, { 0L }, graceMs = 20, fallbackMs = 50) { ended.incrementAndGet() }
        try {
            repeat(50) {
                val go = CountDownLatch(1)
                val threads = List(8) { i ->
                    Thread {
                        go.await()
                        when (i % 3) {
                            0 -> e.request()
                            1 -> { e.onAudio(); e.onTurnComplete() }
                            else -> e.onTurnComplete()
                        }
                    }.also { it.start() }
                }
                go.countDown()
                threads.forEach { it.join() }
                Thread.sleep(150)
                assertEquals(it + 1, ended.get())
                assertFalse(e.requested)
            }
        } finally {
            scope.cancel()
        }
    }
}
