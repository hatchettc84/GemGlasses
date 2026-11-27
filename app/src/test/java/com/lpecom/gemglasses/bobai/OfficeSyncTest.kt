package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.bobai.data.JobSessionStore
import com.lpecom.gemglasses.bobai.domain.ApprovalStatus
import com.lpecom.gemglasses.bobai.domain.DocPage
import com.lpecom.gemglasses.bobai.domain.SourceDocument
import com.lpecom.gemglasses.bobai.office.OfficeApi
import com.lpecom.gemglasses.bobai.office.OfficeJobRow
import com.lpecom.gemglasses.bobai.data.OFFICE_PACK_ID
import com.lpecom.gemglasses.bobai.live.BobLiveTools
import com.lpecom.gemglasses.bobai.office.OfficeDocMeta
import com.lpecom.gemglasses.bobai.office.OfficeDocumentBody
import com.lpecom.gemglasses.bobai.office.OfficeKnowledgeIndex
import com.lpecom.gemglasses.bobai.office.OfficeNotFoundException
import com.lpecom.gemglasses.bobai.office.OfficeSync
import com.lpecom.gemglasses.bobai.office.OfficeWork
import com.lpecom.gemglasses.bobai.office.SyncOutcome
import com.lpecom.gemglasses.gemini.DeviceRemovedException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun doc(id: String, model: String, body: String, heading: String = "Panel") = SourceDocument(
        id, "tenant-1", "$model Manual", "$model Manual", "A", setOf(model), ApprovalStatus.APPROVED,
        "office-approved", listOf(DocPage(1, heading, body)), isMock = false,
    )

    private val doc1 = doc("doc-1", "ZX-9", "Turn the gas valve to OFF before opening the panel.")
    private val doc2 = doc("doc-2", "ZX-9", "Reset the breaker after replacing the fuse.", heading = "Electrical")
    private fun meta(d: SourceDocument, version: Int = 2, sha: String = "sha-${d.id}") =
        OfficeDocMeta(d.id, d.tenantId, d.title, d.revision, d.applicableModels, version, sha)

    private val work = OfficeWork(listOf(OfficeJobRow("j1", "Fix boiler", "Site", status = "open", revision = 1)), emptyList())

    /** The office: a manifest of approved documents, and each one's pages on request. */
    private class FakeApi(
        var onCall: () -> Unit = {},
        var served: List<Pair<OfficeDocMeta, SourceDocument>>,
        val w: OfficeWork,
    ) : OfficeApi {
        var calls = 0
        val fetched = mutableListOf<String>()
        override suspend fun knowledge(): OfficeKnowledgeIndex {
            calls++; onCall()
            return OfficeKnowledgeIndex("tenant-1", 1_800_000_000_000L, 1_800_000_000_000L + 86_400_000L, served.map { it.first })
        }
        override suspend fun knowledgeDocument(id: String): OfficeDocumentBody {
            calls++; onCall(); fetched += id
            val (m, d) = served.firstOrNull { it.first.id == id } ?: throw OfficeNotFoundException("knowledge/$id")
            return OfficeDocumentBody(m.version, m.sha256, d)
        }
        override suspend fun work(): OfficeWork { calls++; onCall(); return w }
        override suspend fun putSticky(sticky: com.lpecom.gemglasses.bobai.domain.Sticky) {}
        override suspend fun deleteSticky(id: String) {}
    }

    private fun api(vararg docs: SourceDocument) = FakeApi(served = docs.map { meta(it) to it }, w = work)

    private fun controller(name: String) =
        BobAiController(JobSessionStore(File(tmp.root, "$name.json")), now = { t0 })

    private val dir get() = File(tmp.root, "office")

    private fun hasDoc(c: BobAiController, id: String = "doc-1") = c.knowledge.allDocuments().any { it.id == id }

    /** What Bob's search_approved_manuals tool returns for [question] on a confirmed ZX-9. */
    private fun search(c: BobAiController, question: String): String {
        c.startOrResume()
        c.confirmEquipment("ZX-9", "SN1")
        val result = BobLiveTools.answerJson(c.knowledge.answer(question, c.activeSession()!!.equipment))
        return (result["status"] as JsonPrimitive).content
    }

    @Test fun `caches knowledge and reloads it without network`() = runBlocking {
        val api = api(doc1)
        assertEquals(SyncOutcome.Synced, OfficeSync(api, dir, controller("a")) { true }.syncNow())
        val c2 = controller("b")
        val offline = FakeApi({ throw IOException("offline") }, emptyList(), work)
        OfficeSync(offline, dir, c2) { true }.loadCached()
        assertTrue(hasDoc(c2))
        assertNotNull(c2.job("office-j1"))
        assertEquals(0, offline.calls)
    }

    @Test fun `not paired never calls the api`() = runBlocking {
        val api = api(doc1)
        assertEquals(SyncOutcome.NotPaired, OfficeSync(api, dir, controller("a")) { false }.syncNow())
        assertEquals(0, api.calls)
    }

    @Test fun `only new or changed documents are downloaded`() = runBlocking {
        val api = api(doc1)
        val c = controller("a")
        val sync = OfficeSync(api, dir, c) { true }
        sync.syncNow()
        assertEquals(listOf("doc-1"), api.fetched)
        // Unchanged doc-1 comes from the cache; only the new doc-2 is fetched.
        api.served = listOf(meta(doc1) to doc1, meta(doc2) to doc2)
        sync.syncNow()
        assertEquals(listOf("doc-1", "doc-2"), api.fetched)
        assertTrue(hasDoc(c, "doc-1") && hasDoc(c, "doc-2"))
        // A new version (or file hash) of doc-1 is downloaded again.
        val doc1b = doc1.copy(pages = listOf(DocPage(1, "Panel", "Close the gas valve, then open the panel.")))
        api.served = listOf(meta(doc1, sha = "sha-new") to doc1b, meta(doc2) to doc2)
        sync.syncNow()
        assertEquals(listOf("doc-1", "doc-2", "doc-1"), api.fetched)
        assertTrue(c.knowledge.document("doc-1")!!.pages.single().body.startsWith("Close the gas valve"))
    }

    @Test fun `a document missing from the manifest is dropped, so search no longer finds it`() = runBlocking {
        val api = api(doc1, doc2)
        val c = controller("a")
        val sync = OfficeSync(api, dir, c) { true }
        assertEquals(SyncOutcome.Synced, sync.syncNow())
        assertEquals("ok", search(c, "how do I open the panel"))

        // doc-1 was revoked in the console: the next manifest no longer lists it.
        api.served = listOf(meta(doc2) to doc2)
        assertEquals(SyncOutcome.Synced, sync.syncNow())
        assertEquals("no_evidence", search(c, "how do I open the panel"))
        assertEquals("ok", search(c, "how do I reset the breaker"))
        assertNull(c.knowledge.document("doc-1"))
        assertEquals(listOf("doc-2"), c.state.value.packs.single { it.id == OFFICE_PACK_ID }.documentIds)

        // And it does not come back from the cache on the next launch.
        val relaunched = controller("b")
        OfficeSync(api, dir, relaunched) { true }.loadCached()
        assertFalse(hasDoc(relaunched, "doc-1"))
        assertTrue(hasDoc(relaunched, "doc-2"))
    }

    @Test fun `a document revoked between the manifest and its download is skipped`() = runBlocking {
        val api = FakeApi(served = listOf(meta(doc1) to doc1), w = work)
        val gone = object : OfficeApi by api {
            override suspend fun knowledge() = api.knowledge().let { it.copy(documents = it.documents + meta(doc2)) }
        }
        val c = controller("a")
        assertEquals(SyncOutcome.Synced, OfficeSync(gone, dir, c) { true }.syncNow())
        assertTrue(hasDoc(c, "doc-1"))
        assertFalse(hasDoc(c, "doc-2"))
    }

    /** Like [FakeApi], but downloading any document in [failing] throws [error]. */
    private fun failingDocs(api: FakeApi, failing: Set<String>, error: () -> Exception = { IOException("timeout") }) =
        object : OfficeApi by api {
            override suspend fun knowledgeDocument(id: String): OfficeDocumentBody {
                if (id in failing) { api.fetched += id; throw error() }
                return api.knowledgeDocument(id)
            }
        }

    @Test fun `one document failing to download keeps its cached copy and reports a partial sync`() = runBlocking {
        val api = api(doc1, doc2)
        val c = controller("a")
        assertEquals(SyncOutcome.Synced, OfficeSync(api, dir, c) { true }.syncNow())

        // doc-1 has a new version, but its download times out; doc-3 is new and downloads fine.
        val doc1b = doc1.copy(pages = listOf(DocPage(1, "Panel", "Close the gas valve, then open the panel.")))
        val doc3 = doc("doc-3", "ZX-9", "Bleed the radiator with the key.", heading = "Heating")
        api.served = listOf(meta(doc1, sha = "sha-new") to doc1b, meta(doc2) to doc2, meta(doc3) to doc3)
        val sync = OfficeSync(failingDocs(api, setOf("doc-1")), dir, c) { true }
        assertEquals(SyncOutcome.Partial(notUpdated = 1), sync.syncNow())

        // The old doc-1 is kept (and cached), doc-3 arrived.
        assertTrue(c.knowledge.document("doc-1")!!.pages.single().body.startsWith("Turn the gas valve"))
        assertTrue(hasDoc(c, "doc-2") && hasDoc(c, "doc-3"))
        val relaunched = controller("b")
        OfficeSync(api, dir, relaunched) { true }.loadCached()
        assertTrue(hasDoc(relaunched, "doc-1") && hasDoc(relaunched, "doc-3"))

        // The stale copy is retried on the next sync, and a working network completes it.
        api.fetched.clear()
        assertEquals(SyncOutcome.Synced, OfficeSync(api, dir, c) { true }.syncNow())
        assertEquals(listOf("doc-1"), api.fetched)
        assertTrue(c.knowledge.document("doc-1")!!.pages.single().body.startsWith("Close the gas valve"))
    }

    @Test fun `a failed download with no cached copy is skipped while drops still apply`() = runBlocking {
        val api = api(doc1, doc2)
        val c = controller("a")
        OfficeSync(api, dir, c) { true }.syncNow()

        // doc-1 was revoked (left out of the manifest); new doc-3 fails with a 500.
        val doc3 = doc("doc-3", "ZX-9", "Bleed the radiator with the key.", heading = "Heating")
        api.served = listOf(meta(doc2) to doc2, meta(doc3) to doc3)
        val sync = OfficeSync(failingDocs(api, setOf("doc-3")) { IOException("Office sync failed (HTTP 500)") }, dir, c) { true }
        assertEquals(SyncOutcome.Partial(notUpdated = 1), sync.syncNow())
        assertFalse(hasDoc(c, "doc-1")) // the revocation still reached the phone
        assertTrue(hasDoc(c, "doc-2"))
        assertFalse(hasDoc(c, "doc-3"))
        assertEquals(listOf("doc-2"), c.state.value.packs.single { it.id == OFFICE_PACK_ID }.documentIds)
        assertNotNull(c.job("office-j1")) // jobs still synced
    }

    @Test fun `a removed phone during a document download still forgets everything`() = runBlocking {
        val api = api(doc1)
        val c = controller("a")
        OfficeSync(api, dir, c) { true }.syncNow()
        api.served = listOf(meta(doc1, sha = "sha-new") to doc1)
        val sync = OfficeSync(failingDocs(api, setOf("doc-1")) { DeviceRemovedException() }, dir, c) { true }
        assertEquals(SyncOutcome.Removed, sync.syncNow())
        assertFalse(hasDoc(c))
    }

    @Test fun `a manifest failure is still a failed sync with the cache intact`() = runBlocking {
        val api = api(doc1)
        val c = controller("a")
        val sync = OfficeSync(api, dir, c) { true }
        sync.syncNow()
        val down = object : OfficeApi by api {
            override suspend fun knowledge(): OfficeKnowledgeIndex = throw IOException("Office sync failed (HTTP 503)")
        }
        val out = OfficeSync(down, dir, c) { true }.syncNow()
        assertTrue(out is SyncOutcome.Failed)
        assertTrue(hasDoc(c))
        assertTrue(File(dir, "knowledge.json").exists())
    }

    @Test fun `a removed phone deletes the cache and forgets office manuals and jobs`() = runBlocking {
        val c = controller("a")
        val api = api(doc1)
        val sync = OfficeSync(api, dir, c) { true }
        sync.syncNow()
        assertTrue(hasDoc(c))
        api.onCall = { throw DeviceRemovedException() }
        assertEquals(SyncOutcome.Removed, sync.syncNow())
        assertFalse(File(dir, "knowledge.json").exists())
        assertFalse(File(dir, "work.json").exists())
        assertFalse(hasDoc(c))
        assertNull(c.job("office-j1"))
        assertTrue(c.state.value.packs.none { it.id == OFFICE_PACK_ID })
        assertEquals("no_documents", search(c, "how do I open the panel"))
    }

    @Test fun `an unpaired phone never applies cached office data and deletes it`() = runBlocking {
        OfficeSync(api(doc1), dir, controller("a")) { true }.syncNow()
        val c = controller("b")
        OfficeSync(api(doc1), dir, c) { false }.loadCached()
        assertFalse(hasDoc(c))
        assertNull(c.job("office-j1"))
        assertFalse(File(dir, "knowledge.json").exists())
        assertFalse(File(dir, "work.json").exists())
    }

    @Test fun `forget clears office data after an explicit unpair`() = runBlocking {
        val c = controller("a")
        val sync = OfficeSync(api(doc1), dir, c) { true }
        sync.syncNow()
        sync.forget()
        assertFalse(hasDoc(c))
        assertNull(c.job("office-j1"))
        assertFalse(File(dir, "knowledge.json").exists())
    }

    /** Like [api], but fetching jobs throws [error] (e.g. Plane timing out behind the Worker). */
    private fun failingJobs(api: FakeApi, error: () -> Exception = { IOException("Office sync failed (HTTP 502)") }) =
        object : OfficeApi by api {
            override suspend fun work(): OfficeWork = throw error()
        }

    @Test fun `jobs failing still applies the manual update and keeps cached jobs`() = runBlocking {
        val c = controller("a")
        val api = api(doc1, doc2)
        OfficeSync(api, dir, c) { true }.syncNow()
        assertTrue(hasDoc(c, "doc-2"))
        // The office revokes doc-2 while the jobs source is down.
        api.served = api.served.filter { it.first.id != "doc-2" }
        val out = OfficeSync(failingJobs(api), dir, c) { true }.syncNow()
        assertEquals(SyncOutcome.Partial(notUpdated = 0, jobsNotUpdated = true), out)
        assertFalse(hasDoc(c, "doc-2"))
        assertNotNull(c.job("office-j1"))
        // The revocation is in the cache too, so it survives a restart.
        val c2 = controller("b")
        OfficeSync(api, dir, c2) { true }.loadCached()
        assertFalse(hasDoc(c2, "doc-2"))
        assertNotNull(c2.job("office-j1"))
    }

    @Test fun `a removed phone while fetching jobs still forgets everything`() = runBlocking {
        val c = controller("a")
        val api = api(doc1)
        OfficeSync(api, dir, c) { true }.syncNow()
        val out = OfficeSync(failingJobs(api) { DeviceRemovedException() }, dir, c) { true }.syncNow()
        assertEquals(SyncOutcome.Removed, out)
        assertFalse(hasDoc(c))
        assertNull(c.job("office-j1"))
    }

    @Test fun `a failed sync keeps the last good data`() = runBlocking {
        val c = controller("a")
        val api = api(doc1)
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
        OfficeSync(api(doc1), dir, c) { true }.loadCached()
        assertTrue(!File(dir, "knowledge.json").exists())
    }

    @Test fun `backend JSON shapes deserialize`() {
        val json = Json { ignoreUnknownKeys = true }
        val k = json.decodeFromString(
            OfficeKnowledgeIndex.serializer(),
            """{"tenantId":"t","generatedAt":1,"expiresAt":2,"documents":[{"id":"d","tenantId":"t","title":"T","family":"T","revision":"1","applicableModels":["ZX-9"],"approvalStatus":"APPROVED","packId":"office-approved","isMock":false,"version":2,"sha256":"abc"}]}""",
        )
        assertEquals("2:abc", k.documents.single().cacheKey)
        val one = json.decodeFromString(
            OfficeDocumentBody.serializer(),
            """{"version":2,"sha256":"abc","document":{"id":"d","tenantId":"t","title":"T","family":"T","revision":"1","applicableModels":["ZX-9"],"approvalStatus":"APPROVED","packId":"office-approved","isMock":false,"pages":[{"number":1,"heading":"h","body":"b"}]}}""",
        )
        assertEquals(setOf("ZX-9"), one.document.applicableModels)
        assertEquals("b", one.document.pages.single().body)
        val w = json.decodeFromString(
            OfficeWork.serializer(),
            """{"jobs":[{"id":"j","tenant_id":"t","title":"T","site":"S","description":"","assigned_user_id":"u","status":"in_progress","due_at":"2026-01-01T00:00:00Z","revision":3,"created_by":"x","created_at":"c","updated_at":"u","assigned_name":"Bo"}],"tasks":[{"id":"t1","tenant_id":"t","job_id":"j","title":"x","completed":1,"revision":2,"created_at":"c","updated_at":"u"}]}""",
        )
        assertEquals("2026-01-01T00:00:00Z", w.jobs.single().dueAt)
        assertEquals("j", w.tasks.single().jobId)
    }
}
