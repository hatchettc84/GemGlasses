package com.lpecom.gemglasses.bobai

import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.bobai.data.JobSessionStore
import com.lpecom.gemglasses.bobai.data.LoadResult
import com.lpecom.gemglasses.bobai.data.MockContent
import com.lpecom.gemglasses.bobai.domain.ConfirmationStatus
import com.lpecom.gemglasses.bobai.domain.Equipment
import com.lpecom.gemglasses.bobai.domain.EquipmentResolver
import com.lpecom.gemglasses.bobai.domain.EvidenceAnswer
import com.lpecom.gemglasses.bobai.domain.KnowledgeRepository
import com.lpecom.gemglasses.bobai.domain.ModeResolver
import com.lpecom.gemglasses.bobai.domain.NodeKind
import com.lpecom.gemglasses.bobai.domain.ObservationKind
import com.lpecom.gemglasses.bobai.domain.OperatingMode
import com.lpecom.gemglasses.bobai.domain.PackState
import com.lpecom.gemglasses.bobai.domain.ProcedureEngine
import com.lpecom.gemglasses.bobai.domain.SessionStatus
import com.lpecom.gemglasses.bobai.domain.StepResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

private const val NOW = 1_794_000_000_000L

private fun confirmed(model: String, tenant: String = MockContent.TENANT) = Equipment(
    "eq", tenant, null, model, "SN1", ConfirmationStatus.CONFIRMED, "tester",
)

class ProcedureEngineTest {
    private val engine = ProcedureEngine(MockContent.NO_HEAT_PROCEDURE)

    private fun ready() = (0 until MockContent.NO_HEAT_PROCEDURE.prerequisites.size)
        .fold(engine.start()) { s, i -> engine.acknowledge(s, i, true) }

    @Test fun `cannot advance before prerequisites are acknowledged`() {
        val r = engine.choose(engine.start(), "on")
        assertTrue(r is StepResult.NotUnderstood)
    }

    @Test fun `exact option advances along the chosen branch`() {
        val r = engine.choose(ready(), "on") as StepResult.Moved
        assertEquals("error_code", engine.current(r.state).id)
        assertEquals("Check 2 on this path", engine.stepLabel(r.state))
    }

    @Test fun `ambiguous utterances never advance`() {
        val s = ready()
        listOf("yeah I think so", "ok", "yes", "indicator", "on", "").forEach {
            assertTrue("'$it' should not advance", engine.interpret(s, it) is StepResult.NotUnderstood)
        }
    }

    @Test fun `exact label match ignoring case and punctuation advances`() {
        val r = engine.interpret(ready(), "  indicator ON. ")
        assertTrue(r is StepResult.Moved)
    }

    @Test fun `stop node is terminal and cannot be advanced`() {
        val s1 = (engine.choose(ready(), "on") as StepResult.Moved).state
        val s2 = (engine.choose(s1, "e4") as StepResult.Moved).state
        assertEquals(NodeKind.STOP, engine.current(s2).kind)
        assertTrue(engine.isTerminal(s2))
        assertTrue(engine.choose(s2, "e1") is StepResult.NotUnderstood)
    }

    @Test fun `paused procedure does not advance and back returns to prior step`() {
        val paused = engine.setPaused(ready(), true)
        assertTrue(engine.choose(paused, "on") is StepResult.NotUnderstood)
        val moved = (engine.choose(ready(), "on") as StepResult.Moved).state
        assertEquals("thermostat", engine.current(engine.back(moved)).id)
        assertEquals(engine.start().path, engine.back(engine.start()).path)
    }
}

class KnowledgeRepositoryTest {
    private val repo = KnowledgeRepository(MockContent.documents, { MockContent.initialPacks(NOW) }, { NOW })

    @Test fun `answer cites the approved revision with page number`() {
        val a = repo.answer("What is the igniter resistance range?", confirmed("ABC-123")) as EvidenceAnswer.Answer
        val c = a.citations.single()
        assertEquals("doc-abc-manual-b", c.documentId)
        assertEquals("B", c.revision)
        assertEquals(42, c.page)
        assertTrue(a.excerpt.contains("40 to 90 ohms"))
        assertFalse(a.stale)
    }

    @Test fun `revoked and draft documents are never applicable`() {
        val ids = repo.applicableDocuments(confirmed("ABC-123")).map { it.id }
        assertFalse("doc-abc-manual-a" in ids)
        assertFalse("doc-abc-field-note" in ids)
        assertTrue("doc-abc-manual-b" in ids)
    }

    @Test fun `unconfirmed equipment gets no answer`() {
        val eq = confirmed("ABC-123").copy(confirmationStatus = ConfirmationStatus.UNCONFIRMED)
        assertEquals(EvidenceAnswer.EquipmentNotConfirmed, repo.answer("igniter resistance", eq))
        assertEquals(EvidenceAnswer.EquipmentNotConfirmed, repo.answer("igniter resistance", null))
    }

    @Test fun `wrong model and other tenant get no documents`() {
        assertTrue(repo.answer("igniter resistance", confirmed("QRS-410")) is EvidenceAnswer.NoApplicableDocuments)
        assertTrue(repo.answer("igniter resistance", confirmed("ABC-123", tenant = "other")) is EvidenceAnswer.NoApplicableDocuments)
    }

    @Test fun `irrelevant question returns no evidence instead of guessing`() {
        assertEquals(EvidenceAnswer.NoEvidence, repo.answer("what's the warranty phone number", confirmed("ABC-123")))
    }

    @Test fun `two approved revisions that disagree are reported as a conflict`() {
        val a = repo.answer("igniter resistance range", confirmed("XYZ-900"))
        assertTrue(a is EvidenceAnswer.Conflict)
        assertEquals(setOf("1", "2"), (a as EvidenceAnswer.Conflict).citations.map { it.revision }.toSet())
    }

    @Test fun `documents in a missing pack are unavailable`() {
        val noPacks = KnowledgeRepository(MockContent.documents, { emptyList() }, { NOW })
        assertTrue(noPacks.answer("igniter resistance", confirmed("ABC-123")) is EvidenceAnswer.NoApplicableDocuments)
    }
}

class EquipmentResolverTest {
    private val resolver = EquipmentResolver(MockContent.knownModels)

    @Test fun `extracts single candidate and serial from label`() {
        val c = resolver.candidatesFromLabel(MockContent.MOCK_LABEL_TEXT)
        assertEquals(listOf("ABC-123"), c.map { it.model })
        assertEquals("MK2026-00417", c.single().serial)
        assertFalse(resolver.isAmbiguous(c))
    }

    @Test fun `smudged label is ambiguous`() {
        assertTrue(resolver.isAmbiguous(resolver.candidatesFromLabel(MockContent.MOCK_AMBIGUOUS_LABEL_TEXT)))
        assertTrue(resolver.isAmbiguous(resolver.candidatesFromLabel("illegible")))
    }

    @Test fun `confirmation normalizes and rejects junk`() {
        val ok = resolver.confirm("id", "t", " abc-123 ", "sn1", "me") as EquipmentResolver.Confirmation.Confirmed
        assertEquals("ABC-123", ok.equipment.model)
        assertEquals("Acme Mock Heating Co.", ok.equipment.manufacturer)
        assertTrue(ok.equipment.isConfirmed)
        assertTrue(resolver.confirm("id", "t", "", "", "me") is EquipmentResolver.Confirmation.Rejected)
        assertTrue(resolver.confirm("id", "t", "??", "", "me") is EquipmentResolver.Confirmation.Rejected)
    }
}

class ModeResolverTest {
    @Test fun `downloaded manuals never imply offline AI`() {
        val m = ModeResolver.resolve(networkAvailable = false, usableReferencePacks = 2, localModelValidated = false)
        assertEquals(OperatingMode.OFFLINE_REFERENCE, m.mode)
        assertTrue(m.unavailable.any { it.contains("Offline AI") })
    }

    @Test fun `offline without packs says so`() {
        assertEquals(OperatingMode.OFFLINE_NO_REFERENCES, ModeResolver.resolve(false, 0, false).mode)
        assertEquals(OperatingMode.CONNECTED, ModeResolver.resolve(true, 0, false).mode)
        assertEquals(OperatingMode.OFFLINE_ASSISTED, ModeResolver.resolve(false, 1, true).mode)
    }
}

/** The brief's first vertical slice, end to end, including process-death restore. */
class VerticalSliceTest {
    @get:Rule val tmp = TemporaryFolder()

    private var clock = NOW
    private var ids = 0
    private fun controller(file: File) =
        BobAiController(JobSessionStore(file), now = { clock++ }, newId = { "id-${ids++}" })

    @Test fun `confirm equipment, retrieve source, answer with citation, save locally, restore`() {
        val file = File(tmp.root, "bobai/store.json")
        val c = controller(file)
        val session = c.startOrResume()
        assertNull(session.equipment)

        assertTrue(c.confirmEquipment("ABC-123", "MK2026-00417") is EquipmentResolver.Confirmation.Confirmed)
        val answer = c.ask("igniter resistance range")
        assertTrue(answer is EvidenceAnswer.Answer)

        assertTrue(c.startProcedure())
        repeat(3) { c.acknowledge(it, true) }
        assertTrue(c.interpret("yeah probably") is StepResult.NotUnderstood)
        assertTrue(c.chooseOption("on") is StepResult.Moved)

        // Simulated process death: a new controller reads the same file.
        val restored = controller(file)
        val s = restored.activeSession()!!
        assertEquals("ABC-123", s.equipment!!.model)
        assertEquals(listOf("thermostat", "error_code"), s.procedureState!!.path)
        val sourced = s.observations.single { it.kind == ObservationKind.SOURCE_STATEMENT }
        assertEquals(42, sourced.citation!!.page)
        assertEquals("B", sourced.citation!!.revision)

        val ended = restored.endSession()!!
        assertEquals(SessionStatus.ENDED, ended.status)
        assertTrue(ended.draftSummary.contains("ABC-123"))
        assertTrue(ended.draftSummary.contains("rev B, page 42"))
        assertNull(controller(file).activeSession())
    }

    @Test fun `real captures are logged, survive restore, and are counted apart from mocks`() {
        val file = File(tmp.root, "bobai/store.json")
        val c = controller(file)
        c.startOrResume()
        assertNotNull(c.addCapture("captures/id-0-1.jpg", "glasses"))
        assertNotNull(c.mockCapture())

        val s = controller(file).activeSession()!!
        assertEquals(2, s.mediaReferences.size)
        assertEquals("captures/id-0-1.jpg", s.mediaReferences.first())
        assertTrue(s.observations.any { it.kind == ObservationKind.VISUAL_CAPTURE && it.text == "Photo captured from glasses." })

        val summary = c.draftSummary(s)
        assertTrue(summary, summary.contains("Photos: 1 captured, 1 mock placeholder(s)"))
    }

    @Test fun `capture with no active session records nothing`() {
        val c = controller(File(tmp.root, "store.json"))
        assertNull(c.addCapture("captures/x.jpg", "glasses"))
    }

    @Test fun `corrupt store is set aside and reported, not silently lost`() {
        val file = File(tmp.root, "store.json").apply { writeText("{not json") }
        val c = controller(file)
        assertNotNull(c.loadWarning)
        assertTrue(tmp.root.listFiles()!!.any { it.name.startsWith("store.json.corrupt-") })
        assertTrue(JobSessionStore(file).load() is LoadResult.Loaded)
    }

    @Test fun `expired pack answers are flagged stale and refresh clears it`() {
        val c = controller(File(tmp.root, "s.json"))
        c.startOrResume()
        c.setPackState("pack-abc", PackState.COMPLETE)
        clock += 31L * 24 * 3600 * 1000
        c.confirmEquipment("ABC-123", "")
        assertTrue((c.ask("igniter resistance range") as EvidenceAnswer.Answer).stale)
        c.setPackState("pack-abc", PackState.COMPLETE)
        assertFalse((c.ask("igniter resistance range") as EvidenceAnswer.Answer).stale)
    }
}
