package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.live.EndAfterReply
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EndAfterReplyTest {

    @Test fun `ends shortly after the goodbye turn completes`() = runTest {
        var ended = 0
        val e = EndAfterReply(backgroundScope, graceMs = 1_500, fallbackMs = 10_000) { ended++ }
        e.request()
        advanceTimeBy(3_000)
        e.onTurnComplete()
        advanceTimeBy(1_400); runCurrent()
        assertEquals(0, ended)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, ended)
        assertFalse(e.requested)
        advanceTimeBy(20_000); runCurrent()
        assertEquals(1, ended) // the fallback was replaced, not added
    }

    @Test fun `ends after the fallback when no turn completes`() = runTest {
        var ended = 0
        val e = EndAfterReply(backgroundScope, graceMs = 1_500, fallbackMs = 10_000) { ended++ }
        e.request()
        advanceTimeBy(9_900); runCurrent()
        assertEquals(0, ended)
        advanceTimeBy(200); runCurrent()
        assertEquals(1, ended)
    }

    @Test fun `a completed turn without a request does nothing, and reset cancels`() = runTest {
        var ended = 0
        val e = EndAfterReply(backgroundScope, graceMs = 1_500, fallbackMs = 10_000) { ended++ }
        e.onTurnComplete()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, ended)
        e.request()
        e.reset()
        advanceTimeBy(20_000); runCurrent()
        assertEquals(0, ended)
    }
}
