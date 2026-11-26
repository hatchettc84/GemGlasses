package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.ui.setupSteps
import com.lpecom.gemglasses.bobai.workmode.WorkPhase
import com.lpecom.gemglasses.bobai.workmode.listenNotificationText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupChecklistTest {
    @Test fun `nothing done lists every step in order with its action`() {
        val s = setupSteps(false, false, false, false, false)
        assertEquals(listOf("glasses", "mic", "pair", "sync", "heybob"), s.map { it.id })
        assertEquals(listOf("Register with Meta AI", "Allow", "Enter code", "Sync now", "Turn on"), s.map { it.action })
        assertEquals(
            listOf("Connect your glasses", "Allow the microphone", "Pair with the office", "Download manuals and jobs", "Turn on \"Hey Bob\""),
            s.map { it.label },
        )
        assertTrue(s.none { it.done })
    }

    @Test fun `all done has no actions`() =
        assertTrue(setupSteps(true, true, true, true, true).all { it.done && it.action == null })

    @Test fun `only the finished step loses its action`() {
        val s = setupSteps(false, true, false, false, false)
        assertEquals(listOf(false, true, false, false, false), s.map { it.done })
        assertEquals(listOf(true, false, true, true, true), s.map { it.action != null })
    }

    @Test fun `notification text follows the phase`() {
        assertEquals("BobAI is listening for \"Hey Bob\". No audio is saved.", listenNotificationText(WorkPhase.ARMED))
        assertEquals("Bob is talking with you.", listenNotificationText(WorkPhase.ACTIVE))
        assertEquals("Bob is talking with you.", listenNotificationText(WorkPhase.WAKE_DETECTED))
    }
}
