package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.workmode.WakePhrase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {
    @Test fun `matches common ways of saying hey bob`() {
        listOf("hey bob", "Hey Bob.", "hey bob what's my next job", "hi bob", "okay bob", "ok bob", "hey bob,", "Hey, Bob").forEach {
            assertTrue(it, WakePhrase.matches(it))
        }
    }

    @Test fun `ignores other speech`() {
        listOf("", "bobcat", "hey robert", "the bob", "kebab", "hey", "bobby brown", "they bought").forEach {
            assertFalse(it, WakePhrase.matches(it))
        }
    }
}
