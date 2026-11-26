package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.bobai.data.JobSessionStore
import com.lpecom.gemglasses.bobai.domain.ApprovalStatus
import com.lpecom.gemglasses.bobai.domain.DocPage
import com.lpecom.gemglasses.bobai.domain.SourceDocument
import com.lpecom.gemglasses.bobai.office.OfficeApi
import com.lpecom.gemglasses.bobai.office.OfficeJobRow
import com.lpecom.gemglasses.bobai.office.OfficeKnowledge
import com.lpecom.gemglasses.bobai.office.OfficeSync
import com.lpecom.gemglasses.bobai.office.OfficeWork
import com.lpecom.gemglasses.bobai.office.SyncOutcome
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OfficeSyncTest {
    @get:Rule val tmp = TemporaryFolder()
    private val t0 = 1_800_000_000_000L

    private val knowledge = OfficeKnowledge(
        "tenant-1", t0, t0 + 86_400_000L,
        listOf(
            SourceDocument(
                "doc-1", "tenant-1", "ZX-9 Manual", "ZX-9 Manual", "A", setOf("ZX-9"), ApprovalStatus.APPROVED,
                "office-approved", listOf(DocPage(1, "Panel", "Turn the gas valve to OFF.")), isMock = false,
            ),
        ),
    )
    private val work = OfficeWork(listOf(OfficeJobRow("j1", "Fix boiler", "Site", status = "open", revision = 1)), emptyList())

    private class FakeApi(var onCall: () -> Unit = {}, val k: OfficeKnowledge, val w: OfficeWork) : OfficeApi {
        var calls = 0
        override suspend fun knowledge(): OfficeKnowledge { calls++; onCall(); return k }
        override suspend fun work(): OfficeWork { calls++; onCall(); return w }
    }

    private fun controller(name: String) =
        BobAiController(JobSessionStore(File(tmp.root, "$name.json")), now = { t0 })

    private val dir get() = File(tmp.root, "office")

    private fun hasDoc(c: BobAiController) = c.knowledge.allDocuments().any { it.id == "doc-1" }

    @Test fun `caches knowledge and reloads it without network`() = runBlocking {
        val api = FakeApi(k = knowledge, w = work)
        assertEquals(SyncOutcome.Synced, OfficeSync(api, dir, controller("a")) { true }.syncNow())
        val c2 = controller("b")
        val offline = FakeApi({ throw IOException("offline") }, knowledge, work)
        OfficeSync(offline, dir, c2) { true }.loadCached()
        assertTrue(hasDoc(c2))
        assertNotNull(c2.job("office-j1"))
        assertEquals(0, offline.calls)
    }

    @Test fun `not paired never calls the api`() = runBlocking {
        val api = FakeApi(k = knowledge, w = work)
        assertEquals(SyncOutcome.NotPaired, OfficeSync(api, dir, controller("a")) { false }.syncNow())
        assertEquals(0, api.calls)
    }

    @Test fun `removed phone stops syncing`() = runBlocking {
        val good = FakeApi(k = knowledge, w = work)
        OfficeSync(good, dir, controller("a")) { true }.syncNow()
        val before = File(dir, "knowledge.json").readText() to File(dir, "work.json").readText()
        val removed = FakeApi({ throw DeviceRemovedException() }, knowledge, work)
        assertEquals(SyncOutcome.Removed, OfficeSync(removed, dir, controller("b")) { true }.syncNow())
        assertEquals(before, File(dir, "knowledge.json").readText() to File(dir, "work.json").readText())
    }

    @Test fun `a failed sync keeps the last good data`() = runBlocking {
        val c = controller("a")
        val api = FakeApi(k = knowledge, w = work)
        val sync = OfficeSync(api, dir, c) { true }
        sync.syncNow()
        api.onCall = { throw IOException("boom") }
        val out = sync.syncNow()
        assertTrue(out is SyncOutcome.Failed && out.message == "boom")
        assertTrue(hasDoc(c))
        assertTrue(File(dir, "knowledge.json").exists())
    }

    @Test fun `a corrupt cache file is ignored and deleted`() {
        dir.mkdirs()
        File(dir, "knowledge.json").writeText("{nope")
        val c = controller("a")
        OfficeSync(FakeApi(k = knowledge, w = work), dir, c) { true }.loadCached()
        assertTrue(!File(dir, "knowledge.json").exists())
    }

    @Test fun `backend JSON shapes deserialize`() {
        val json = Json { ignoreUnknownKeys = true }
        val k = json.decodeFromString(
            OfficeKnowledge.serializer(),
            """{"tenantId":"t","generatedAt":1,"expiresAt":2,"documents":[{"id":"d","tenantId":"t","title":"T","family":"T","revision":"1","applicableModels":["ZX-9"],"approvalStatus":"APPROVED","packId":"office-approved","pages":[{"number":1,"heading":"h","body":"b"}],"isMock":false}]}""",
        )
        assertEquals(setOf("ZX-9"), k.documents.single().applicableModels)
        val w = json.decodeFromString(
            OfficeWork.serializer(),
            """{"jobs":[{"id":"j","tenant_id":"t","title":"T","site":"S","description":"","assigned_user_id":"u","status":"in_progress","due_at":"2026-01-01T00:00:00Z","revision":3,"created_by":"x","created_at":"c","updated_at":"u","assigned_name":"Bo"}],"tasks":[{"id":"t1","tenant_id":"t","job_id":"j","title":"x","completed":1,"revision":2,"created_at":"c","updated_at":"u"}]}""",
        )
        assertEquals("2026-01-01T00:00:00Z", w.jobs.single().dueAt)
        assertEquals("j", w.tasks.single().jobId)
    }
}
