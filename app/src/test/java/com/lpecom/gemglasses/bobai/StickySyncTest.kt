package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.bobai.data.JobSessionStore
import com.lpecom.gemglasses.bobai.data.StickyOp
import com.lpecom.gemglasses.bobai.data.StickyOutbox
import com.lpecom.gemglasses.bobai.data.StickyRepository
import com.lpecom.gemglasses.bobai.data.StickyStore
import com.lpecom.gemglasses.bobai.domain.Sticky
import com.lpecom.gemglasses.bobai.domain.StickyKind
import com.lpecom.gemglasses.bobai.office.OfficeApi
import com.lpecom.gemglasses.bobai.office.OfficeDocumentBody
import com.lpecom.gemglasses.bobai.office.OfficeKnowledgeIndex
import com.lpecom.gemglasses.bobai.office.OfficeRejectedException
import com.lpecom.gemglasses.bobai.office.OfficeSync
import com.lpecom.gemglasses.bobai.office.OfficeWork
import com.lpecom.gemglasses.bobai.office.StickyUploader
import com.lpecom.gemglasses.bobai.office.SyncOutcome
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class StickySyncTest {
    @get:Rule val tmp = TemporaryFolder()

    private val outboxFile get() = File(tmp.root, "bobai/sticky_outbox.json")
    private val stickyFile get() = File(tmp.root, "bobai/stickies.json")

    private class Api(var failure: (String) -> Exception? = { null }) : OfficeApi {
        val calls = mutableListOf<String>()
        override suspend fun knowledge(): OfficeKnowledgeIndex = OfficeKnowledgeIndex("t", 1, 2, emptyList())
        override suspend fun knowledgeDocument(id: String): OfficeDocumentBody = error("unused")
        override suspend fun work(): OfficeWork = OfficeWork(emptyList(), emptyList())
        override suspend fun putSticky(sticky: Sticky) {
            calls += "PUT ${sticky.id}"
            failure(sticky.id)?.let { throw it }
        }
        override suspend fun deleteSticky(id: String) {
            calls += "DELETE $id"
            failure(id)?.let { throw it }
        }
        override suspend fun me() = com.lpecom.gemglasses.bobai.office.OfficeMe()
        override suspend fun postTip(title: String, text: String) = error("unused")
    }

    private var n = 0
    private fun repo(outbox: StickyOutbox, onChanged: () -> Unit = {}) =
        StickyRepository(StickyStore(stickyFile), newId = { "s${n++}" }, outbox = outbox, onChanged = onChanged)

    private fun uploader(outbox: StickyOutbox, r: StickyRepository, api: Api, paired: Boolean = true) =
        @Suppress("OPT_IN_USAGE") StickyUploader(outbox, api, { r.stickies.value }, { paired }, GlobalScope)

    @Test fun `outbox persists and the latest op wins`() {
        val o = StickyOutbox(outboxFile)
        o.enqueue("a", StickyOp.UPSERT)
        o.enqueue("b", StickyOp.UPSERT)
        o.enqueue("a", StickyOp.DELETE)
        val reloaded = StickyOutbox(outboxFile)
        assertEquals(listOf("b" to StickyOp.UPSERT, "a" to StickyOp.DELETE), reloaded.pending().map { it.id to it.op })
    }

    @Test fun `first run queues every existing sticky, later runs do not`() {
        val seed = StickyRepository(StickyStore(stickyFile), newId = { "s${n++}" })
        seed.add(StickyKind.NOTE, "t1", "x", null)
        seed.add(StickyKind.DRAFT, "t2", "y", null)
        val o = StickyOutbox(outboxFile)
        repo(o)
        assertEquals(setOf("s0", "s1"), o.pending().map { it.id }.toSet())
        o.clear()
        repo(StickyOutbox(outboxFile)) // file exists now: no second backfill
        assertTrue(StickyOutbox(outboxFile).pending().isEmpty())
    }

    @Test fun `repository changes enqueue ops and trigger a flush`() {
        var triggers = 0
        val o = StickyOutbox(outboxFile)
        val r = repo(o) { triggers++ }
        val s = r.add(StickyKind.NOTE, "t", "x", null)
        r.setDone(s.id, true)
        r.delete(s.id)
        assertEquals(listOf(s.id to StickyOp.DELETE), o.pending().map { it.id to it.op })
        assertEquals(3, triggers)
    }

    @Test fun `flush uploads and clears the outbox`() = runBlocking {
        val o = StickyOutbox(outboxFile)
        val r = repo(o)
        val a = r.add(StickyKind.NOTE, "t", "x", null)
        val b = r.add(StickyKind.NOTE, "t", "x", null)
        r.delete(b.id)
        val api = Api()
        uploader(o, r, api).flush()
        assertEquals(listOf("PUT ${a.id}", "DELETE ${b.id}"), api.calls)
        assertTrue(o.pending().isEmpty())
    }

    @Test fun `an upsert for a sticky that no longer exists is dropped without a request`() = runBlocking {
        val o = StickyOutbox(outboxFile)
        val r = repo(o)
        o.enqueue("ghost", StickyOp.UPSERT)
        val api = Api()
        uploader(o, r, api).flush()
        assertTrue(api.calls.isEmpty())
        assertTrue(o.pending().isEmpty())
    }

    @Test fun `a network error keeps the ops and stops the flush`() = runBlocking {
        val o = StickyOutbox(outboxFile)
        val r = repo(o)
        r.add(StickyKind.NOTE, "t", "x", null)
        r.add(StickyKind.NOTE, "t", "x", null)
        val api = Api { IOException("Office sync failed (HTTP 503)") }
        uploader(o, r, api).flush()
        assertEquals(1, api.calls.size)
        assertEquals(2, o.pending().size)
        api.failure = { null }
        uploader(o, r, api).flush()
        assertTrue(o.pending().isEmpty())
    }

    @Test fun `a 400 or 409 drops that op and continues`() = runBlocking {
        val o = StickyOutbox(outboxFile)
        val r = repo(o)
        val a = r.add(StickyKind.NOTE, "t", "x", null)
        val b = r.add(StickyKind.NOTE, "t", "x", null)
        val api = Api { if (it == a.id) OfficeRejectedException(400) else null }
        uploader(o, r, api).flush()
        assertEquals(2, api.calls.size)
        assertTrue(o.pending().isEmpty())
        assertEquals(listOf(b.id, a.id), r.stickies.value.map { it.id }) // local stickies stay
    }

    @Test fun `unpaired phone does not flush`() = runBlocking {
        val o = StickyOutbox(outboxFile)
        val r = repo(o)
        r.add(StickyKind.NOTE, "t", "x", null)
        val api = Api()
        uploader(o, r, api, paired = false).flush()
        assertTrue(api.calls.isEmpty())
        assertEquals(1, o.pending().size)
    }

    private fun controller() = BobAiController(JobSessionStore(File(tmp.root, "c.json")), now = { 1L })

    @Test fun `a flush failure does not change the sync outcome, and forget clears the outbox`() = runBlocking {
        val o = StickyOutbox(outboxFile)
        val r = repo(o)
        r.add(StickyKind.NOTE, "t", "x", null)
        val api = Api { IOException("down") }
        val sync = OfficeSync(api, File(tmp.root, "office"), controller(), uploader(o, r, api), o) { true }
        assertEquals(SyncOutcome.Synced, sync.syncNow())
        assertEquals(1, api.calls.size)
        assertEquals(1, o.pending().size)
        sync.forget()
        assertTrue(StickyOutbox(outboxFile).pending().isEmpty())
        assertEquals(1, r.stickies.value.size)
    }
}
