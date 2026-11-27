package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.domain.StickyKind
import com.lpecom.gemglasses.bobai.live.BobLiveTools
import com.lpecom.gemglasses.bobai.live.StickyArgs
import com.lpecom.gemglasses.bobai.live.StickyArgsResult
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StickyToolTest {
    private val decl get() = BobLiveTools.declarations.flatMap { it.functionDeclarations.orEmpty() }
        .single { it.name == "save_sticky" }

    @Test fun `declares save_sticky with required params`() {
        assertEquals("save_sticky", BobLiveTools.SAVE_STICKY)
        val p = decl.parameters!!.jsonObject
        assertEquals(setOf("kind", "title", "text"), p["required"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet())
        val props = p["properties"]!!.jsonObject
        assertEquals(setOf("kind", "title", "text", "job"), props.keys)
        assertEquals(listOf("note", "draft"), props["kind"]!!.jsonObject["enum"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertTrue(decl.description.contains("Drafts are never sent."))
    }

    @Test fun `system instruction carries rule 9`() {
        val s = BobLiveTools.systemInstruction(null)
        assertTrue(s.contains("9. When the technician asks you to note, remember, jot down, write down or draft something, call save_sticky"))
        assertTrue(s.contains("Drafts are never sent; never say you sent anything."))
    }

    @Test fun `valid args accepted and trimmed`() {
        val r = StickyArgs.parse("Draft", "  Email Sam ", " Hi Sam ", " Job 1 ") as StickyArgsResult.Ok
        assertEquals(StickyKind.DRAFT, r.kind)
        assertEquals("Email Sam", r.title)
        assertEquals("Hi Sam", r.text)
        assertEquals("Job 1", r.job)
    }

    @Test fun `blank job becomes null`() =
        assertEquals(null, (StickyArgs.parse("note", "t", "x", "  ") as StickyArgsResult.Ok).job)

    @Test fun `bad kind blank title blank text rejected`() {
        assertTrue(StickyArgs.parse("memo", "t", "x", null) is StickyArgsResult.Error)
        assertTrue(StickyArgs.parse(null, "t", "x", null) is StickyArgsResult.Error)
        assertTrue(StickyArgs.parse("note", "   ", "x", null) is StickyArgsResult.Error)
        assertTrue(StickyArgs.parse("note", "t", " ", null) is StickyArgsResult.Error)
    }

    @Test fun `result json shapes`() {
        assertEquals("saved", BobLiveTools.stickySavedJson("T")["result"]!!.jsonPrimitive.content)
        assertEquals("T", BobLiveTools.stickySavedJson("T")["title"]!!.jsonPrimitive.content)
        val e = BobLiveTools.stickyErrorJson("bad")
        assertEquals("error", e["result"]!!.jsonPrimitive.content)
        assertEquals("bad", e["reason"]!!.jsonPrimitive.content)
    }
}
