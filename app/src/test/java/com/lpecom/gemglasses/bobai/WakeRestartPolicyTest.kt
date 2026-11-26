package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.workmode.WakeRestartPolicy
import com.lpecom.gemglasses.bobai.workmode.WakeRestartPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeRestartPolicyTest {

    @Test fun `slow silence restarts quickly forever`() {
        val p = WakeRestartPolicy()
        var t = 0L
        repeat(100) {
            p.onListen(t)
            t += 5_000
            assertEquals(Decision.Restart(WakeRestartPolicy.RESTART_MS), p.onSilence(t))
        }
    }

    @Test fun `persistent errors back off three times then give up`() {
        val p = WakeRestartPolicy()
        var t = 0L
        val decisions = mutableListOf<Decision>()
        while (decisions.lastOrNull() != Decision.GiveUp && decisions.size < 100) {
            p.onListen(t)
            t += 100
            decisions += p.onError(t)
        }
        assertEquals(Decision.GiveUp, decisions.last())
        assertEquals(3, decisions.count { it == Decision.Restart(WakeRestartPolicy.BACKOFF_MS) })
    }

    @Test fun `instant silence counts as an error so a muted mic gives up`() {
        val p = WakeRestartPolicy()
        var t = 0L
        var last: Decision? = null
        var n = 0
        while (last != Decision.GiveUp && n++ < 100) {
            p.onListen(t)
            t += 50
            last = p.onSilence(t)
        }
        assertEquals(Decision.GiveUp, last)
    }

    @Test fun `a healthy window resets the backoff count`() {
        val p = WakeRestartPolicy()
        var t = 0L
        repeat(3) {
            repeat(WakeRestartPolicy.MAX_ERRORS) { p.onListen(t); t += 100; p.onError(t) }
            p.onListen(t); t += 5_000
            p.onResult()
        }
        repeat(WakeRestartPolicy.MAX_ERRORS - 1) { p.onListen(t); t += 100; p.onError(t) }
        p.onListen(t); t += 100
        assertEquals(Decision.Restart(WakeRestartPolicy.BACKOFF_MS), p.onError(t))
    }
}
