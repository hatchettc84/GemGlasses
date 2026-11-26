package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.bobai.data.JobSessionStore
import com.lpecom.gemglasses.bobai.domain.ApprovalStatus
import com.lpecom.gemglasses.bobai.domain.DocPage
import com.lpecom.gemglasses.bobai.domain.EquipmentResolver
import com.lpecom.gemglasses.bobai.domain.EvidenceAnswer
import com.lpecom.gemglasses.bobai.domain.JobStatus
import com.lpecom.gemglasses.bobai.domain.SourceDocument
import com.lpecom.gemglasses.bobai.office.OfficeJobRow
import com.lpecom.gemglasses.bobai.office.OfficeKnowledge
import com.lpecom.gemglasses.bobai.office.OfficeTaskRow
import com.lpecom.gemglasses.bobai.office.OfficeWork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class BobAiControllerOfficeTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = 1_800_000_000_000L
    private var ids = 0
    private fun controller(clock: () -> Long = { t0 }) =
        BobAiController(JobSessionStore(File(tmp.root, "store.json")), now = clock, newId = { "id-${ids++}" })

    private fun knowledge(expiresAt: Long = t0 + 86_400_000L) = OfficeKnowledge(
        tenantId = "tenant-1", generatedAt = t0, expiresAt = expiresAt,
        documents = listOf(
            SourceDocument(
                id = "doc-zx9", tenantId = "tenant-1", title = "ZX-9 Service Manual", family = "ZX-9 Service Manual",
                revision = "C", applicableModels = setOf("ZX-9"), approvalStatus = ApprovalStatus.APPROVED,
                packId = "office-approved",
                pages = listOf(DocPage(1, "Opening the panel", "Turn the gas valve to OFF before opening the panel.")),
                isMock = false,
            ),
        ),
    )

    @Test fun `office documents become searchable for a confirmed model`() {
        val c = controller()
        c.applyOfficeKnowledge(knowledge())
        c.startOrResume()
        assertTrue(c.confirmEquipment("ZX-9", "SN1") is EquipmentResolver.Confirmation.Confirmed)
        val answer = c.knowledge.answer("how do I open the panel", c.activeSession()!!.equipment)
        answer as EvidenceAnswer.Answer
        val cite = answer.citations.single()
        assertEquals("doc-zx9", cite.documentId)
        assertEquals("C", cite.revision)
        assertEquals(1, cite.page)
        assertFalse(answer.stale)
    }

    @Test fun `office models become known`() {
        val c = controller()
        assertFalse(c.resolver.isKnownModel("ZX-9"))
        c.applyOfficeKnowledge(knowledge())
        assertTrue(c.resolver.isKnownModel("zx-9"))
        c.startOrResume()
        val r = c.confirmEquipment("ZX-9", "SN1") as EquipmentResolver.Confirmation.Confirmed
        assertEquals("tenant-1", r.equipment.tenantId)
        assertEquals("ZX-9 Service Manual", r.equipment.manufacturer)
    }

    private fun work(vararg ids: String) = OfficeWork(
        jobs = ids.map { OfficeJobRow(id = it, title = "Job $it", site = "Site $it", status = "open", revision = 1) },
        tasks = ids.map { OfficeTaskRow(id = "t-$it", jobId = it, title = "Task $it", completed = 1, revision = 1) },
    )

    @Test fun `office jobs replace previous office jobs and keep local sessions`() {
        val c = controller()
        c.applyOfficeKnowledge(knowledge())
        c.applyOfficeWork(work("a", "b"))
        c.startOrResume("office-b")
        c.applyOfficeWork(work("c"))
        val ids = c.state.value.jobs.map { it.id }
        assertFalse("office-a" in ids)
        assertTrue("office-b" in ids)   // has a live session
        assertTrue("office-c" in ids)
        assertTrue("job-mock-sample" in ids)
        val cJob = c.job("office-c")!!
        assertEquals("Site c", cJob.site)
        assertEquals("c", cJob.officeId)
        assertEquals(JobStatus.OPEN, cJob.status)
        assertEquals(listOf(true), cJob.tasks.map { it.done })
        c.endSession()
        c.applyOfficeWork(work("c"))
        assertNull(c.job("office-b"))
    }

    @Test fun `expired office knowledge is not readable`() {
        var clock = t0
        val c = controller { clock }
        c.applyOfficeKnowledge(knowledge(expiresAt = t0 + 1000))
        c.startOrResume()
        c.confirmEquipment("ZX-9", "SN1")
        clock = t0 + 2000
        val answer = c.knowledge.answer("how do I open the panel", c.activeSession()!!.equipment)
        assertTrue(answer !is EvidenceAnswer.Answer)
    }
}
