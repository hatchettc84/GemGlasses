package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.data.StickyRepository
import com.lpecom.gemglasses.bobai.data.StickyStore
import com.lpecom.gemglasses.bobai.domain.StickyKind
import com.lpecom.gemglasses.bobai.domain.StickyLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StickyRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_000L
    private var n = 0
    private fun file() = File(tmp.root, "bobai/stickies.json")
    private fun repo(f: File = file()) = StickyRepository(StickyStore(f), clock = { now++ }, newId = { "id${n++}" })

    @Test fun `add returns the sticky and lists it`() {
        val r = repo()
        val s = r.add(StickyKind.NOTE, "Check valve", "Valve 3 leaks", "Replace igniter")
        assertEquals("Check valve", s.title)
        assertEquals("Replace igniter", s.jobTitle)
        assertFalse(s.done)
        assertEquals(listOf(s), r.stickies.value)
    }

    @Test fun `newest first`() {
        val r = repo()
        val a = r.add(StickyKind.NOTE, "a", "a", null)
        val b = r.add(StickyKind.DRAFT, "b", "b", null)
        assertEquals(listOf(b.id, a.id), r.stickies.value.map { it.id })
    }

    @Test fun `setDone and delete`() {
        val r = repo()
        val a = r.add(StickyKind.NOTE, "a", "a", null)
        r.setDone(a.id, true)
        assertTrue(r.stickies.value.single().done)
        r.setDone(a.id, false)
        assertFalse(r.stickies.value.single().done)
        r.delete(a.id)
        assertTrue(r.stickies.value.isEmpty())
    }

    @Test fun `title and text are trimmed and capped`() {
        val r = repo()
        val s = r.add(StickyKind.NOTE, "  " + "t".repeat(200), "x".repeat(5000), null)
        assertEquals(StickyLimits.MAX_TITLE, s.title.length)
        assertEquals(StickyLimits.MAX_TEXT, s.text.length)
        assertEquals(80, StickyLimits.MAX_TITLE)
        assertEquals(2000, StickyLimits.MAX_TEXT)
        assertEquals(500, StickyLimits.MAX_STICKIES)
    }

    @Test fun `over 500 drops the oldest done first`() {
        val r = repo()
        val first = r.add(StickyKind.NOTE, "first", "x", null)
        val second = r.add(StickyKind.NOTE, "second", "x", null)
        repeat(498) { r.add(StickyKind.NOTE, "n$it", "x", null) }
        assertEquals(500, r.stickies.value.size)
        r.setDone(second.id, true)
        r.add(StickyKind.NOTE, "extra", "x", null)
        val ids = r.stickies.value.map { it.id }
        assertEquals(500, ids.size)
        assertFalse(second.id in ids)
        assertTrue(first.id in ids)
    }

    @Test fun `over 500 with none done drops the oldest`() {
        val r = repo()
        val first = r.add(StickyKind.NOTE, "first", "x", null)
        repeat(499) { r.add(StickyKind.NOTE, "n$it", "x", null) }
        r.add(StickyKind.NOTE, "extra", "x", null)
        assertEquals(500, r.stickies.value.size)
        assertFalse(first.id in r.stickies.value.map { it.id })
    }

    @Test fun `data survives a reload`() {
        val r = repo()
        val a = r.add(StickyKind.DRAFT, "a", "text", "job")
        r.setDone(a.id, true)
        val again = repo()
        assertEquals(r.stickies.value, again.stickies.value)
        assertTrue(again.stickies.value.single().done)
    }

    @Test fun `corrupt file is moved aside and repository starts empty`() {
        val f = file()
        f.parentFile!!.mkdirs()
        f.writeText("{not json")
        val r = repo(f)
        assertTrue(r.stickies.value.isEmpty())
        assertTrue(f.parentFile!!.listFiles()!!.any { it.name.startsWith("stickies.json.corrupt-") })
        r.add(StickyKind.NOTE, "a", "a", null)
        assertEquals(1, repo(f).stickies.value.size)
    }
}
