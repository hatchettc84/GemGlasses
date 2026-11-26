package com.lpecom.gemglasses.bobai.data

import com.lpecom.gemglasses.bobai.domain.EquipmentCandidate
import com.lpecom.gemglasses.bobai.domain.EquipmentResolver
import com.lpecom.gemglasses.bobai.domain.EvidenceAnswer
import com.lpecom.gemglasses.bobai.domain.Job
import com.lpecom.gemglasses.bobai.domain.JobTask
import com.lpecom.gemglasses.bobai.domain.KnowledgePack
import com.lpecom.gemglasses.bobai.domain.SourceDocument
import com.lpecom.gemglasses.bobai.domain.SyncState
import com.lpecom.gemglasses.bobai.office.OfficeKnowledge
import com.lpecom.gemglasses.bobai.office.OfficeWork
import com.lpecom.gemglasses.bobai.domain.JobStatus
import com.lpecom.gemglasses.bobai.domain.KnowledgeRepository
import com.lpecom.gemglasses.bobai.domain.NodeKind
import com.lpecom.gemglasses.bobai.domain.Observation
import com.lpecom.gemglasses.bobai.domain.ObservationKind
import com.lpecom.gemglasses.bobai.domain.PackState
import com.lpecom.gemglasses.bobai.domain.Procedure
import com.lpecom.gemglasses.bobai.domain.ProcedureEngine
import com.lpecom.gemglasses.bobai.domain.ProcedureState
import com.lpecom.gemglasses.bobai.domain.Session
import com.lpecom.gemglasses.bobai.domain.SessionStatus
import com.lpecom.gemglasses.bobai.domain.StepResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Android-free orchestration of the first vertical slice:
 * confirm equipment → retrieve approved source → answer with citation →
 * save local session. Every mutation is persisted through [persist].
 */
class BobAiController(
    private val store: JobSessionStore,
    private val persist: (StoreSnapshot) -> Unit = store::save,
    private val now: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { java.util.UUID.randomUUID().toString() },
) {
    @Volatile private var officeDocs: List<SourceDocument> = emptyList()
    @Volatile private var officeModels: Map<String, String> = emptyMap()
    @Volatile private var officeTenant: String? = null
    @Volatile private var officeExpiresAt: Long = 0L

    val resolver = EquipmentResolver { MockContent.knownModels + officeModels }

    /** Set when the saved file could not be read on launch. Shown to the user. */
    var loadWarning: String? = null
        private set

    private val _state = MutableStateFlow(initial())
    val state: StateFlow<StoreSnapshot> = _state

    // Office manuals past their expiry are not readable (the phone must refresh before relying on them).
    val knowledge = KnowledgeRepository(
        { MockContent.documents + if (now() < officeExpiresAt) officeDocs else emptyList() },
        { _state.value.packs },
        now,
    )

    private fun initial(): StoreSnapshot = when (val r = store.load()) {
        is LoadResult.Loaded -> r.snapshot
        LoadResult.Missing -> seed()
        is LoadResult.Corrupted -> {
            loadWarning = "Saved jobs could not be read and were set aside (${r.backup.name}). Starting fresh."
            seed()
        }
    }

    private fun seed(): StoreSnapshot {
        val t = now()
        val sample = Job(
            id = "job-mock-sample", tenantId = MockContent.TENANT,
            site = "[MOCK] Riverside Clinic — mechanical room 2",
            status = JobStatus.OPEN, createdAt = t, updatedAt = t, isMock = true,
        )
        return StoreSnapshot(jobs = listOf(sample), packs = MockContent.initialPacks(t)).also(persist)
    }

    private fun mutate(block: (StoreSnapshot) -> StoreSnapshot) {
        _state.update(block)
        persist(_state.value)
    }

    // ---- sessions ---------------------------------------------------------

    fun activeSession(): Session? = _state.value.let { s ->
        s.sessions.firstOrNull { it.id == s.activeSessionId && it.status != SessionStatus.ENDED }
    }

    fun session(id: String): Session? = _state.value.sessions.firstOrNull { it.id == id }

    fun job(id: String): Job? = _state.value.jobs.firstOrNull { it.id == id }

    /** Resumes the active session or starts one, optionally attached to an existing job. */
    fun startOrResume(jobId: String? = null): Session {
        activeSession()?.let { if (jobId == null || it.jobId == jobId) return it }
        val t = now()
        val job = jobId?.let(::job) ?: Job(
            id = newId(), tenantId = MockContent.TENANT, site = "Unassigned session",
            createdAt = t, updatedAt = t,
        )
        val session = Session(
            id = newId(), jobId = job.id, createdAt = t, updatedAt = t,
            observations = listOf(system("Session started. Camera and microphone are off.")),
        )
        mutate { s ->
            s.copy(
                jobs = if (s.jobs.any { it.id == job.id }) {
                    s.jobs.map { if (it.id == job.id) it.copy(status = JobStatus.IN_PROGRESS, updatedAt = t) else it }
                } else {
                    s.jobs + job.copy(status = JobStatus.IN_PROGRESS)
                },
                sessions = s.sessions + session,
                activeSessionId = session.id,
            )
        }
        return session
    }

    private fun updateActive(block: (Session) -> Session): Session? {
        val active = activeSession() ?: return null
        val updated = block(active).copy(updatedAt = now())
        mutate { s -> s.copy(sessions = s.sessions.map { if (it.id == active.id) updated else it }) }
        return updated
    }

    private fun system(text: String) = Observation(ObservationKind.SYSTEM, text, now())

    private fun Session.log(o: Observation) = copy(observations = observations + o)

    // ---- equipment ---------------------------------------------------------

    fun candidates(labelText: String): List<EquipmentCandidate> = resolver.candidatesFromLabel(labelText)

    fun confirmEquipment(model: String, serial: String): EquipmentResolver.Confirmation {
        val isOfficeModel = resolver.normalizeModel(model).let { it in officeModels && it !in MockContent.knownModels }
        val tenant = if (isOfficeModel) officeTenant ?: MockContent.TENANT else MockContent.TENANT
        val result = resolver.confirm(newId(), tenant, model, serial, MockContent.TECHNICIAN)
        if (result is EquipmentResolver.Confirmation.Confirmed) {
            val eq = result.equipment
            val session = updateActive { session ->
                val docs = knowledge.applicableDocuments(eq)
                val note = if (docs.isEmpty()) {
                    "Equipment confirmed: ${eq.model}. No approved, downloaded manual applies to this model — reference-only or escalation."
                } else {
                    "Equipment confirmed: ${eq.model} (S/N ${eq.serial.ifBlank { "not entered" }}). ${docs.size} approved source(s) apply."
                }
                session.copy(equipment = eq, procedureState = null).log(system(note))
            }
            session?.let { s ->
                mutate { st -> st.copy(jobs = st.jobs.map { if (it.id == s.jobId) it.copy(equipmentId = eq.id, updatedAt = now()) else it }) }
            }
        }
        return result
    }

    // ---- procedures --------------------------------------------------------

    fun procedureFor(session: Session?): Procedure? {
        val eq = session?.equipment?.takeIf { it.isConfirmed } ?: return null
        return MockContent.procedures.firstOrNull { eq.model in it.applicableModels }
    }

    fun engine(session: Session?): ProcedureEngine? = procedureFor(session)?.let(::ProcedureEngine)

    fun startProcedure(): Boolean {
        val session = activeSession() ?: return false
        val engine = engine(session) ?: return false
        updateActive { it.copy(procedureState = engine.start()).log(system("Started ${procedureFor(it)!!.title}.")) }
        return true
    }

    fun acknowledge(index: Int, value: Boolean) {
        val session = activeSession() ?: return
        val engine = engine(session) ?: return
        val ps = session.procedureState ?: return
        updateActive { it.copy(procedureState = engine.acknowledge(ps, index, value)) }
    }

    fun chooseOption(optionId: String): StepResult = advance { engine, ps -> engine.choose(ps, optionId) }

    /** Typed or transcribed result. Only exact matches advance. */
    fun interpret(text: String): StepResult {
        updateActive { it.log(Observation(ObservationKind.TECHNICIAN_REPORT, text, now())) }
        return advance { engine, ps -> engine.interpret(ps, text) }
    }

    private fun advance(op: (ProcedureEngine, ProcedureState) -> StepResult): StepResult {
        val session = activeSession() ?: return StepResult.NotUnderstood("No active session.")
        val engine = engine(session) ?: return StepResult.NotUnderstood("No approved procedure for this equipment.")
        val ps = session.procedureState ?: return StepResult.NotUnderstood("Start the procedure first.")
        val before = engine.current(ps)
        val result = op(engine, ps)
        when (result) {
            is StepResult.Moved -> updateActive {
                val chosen = before.options.first { o -> o.nextNodeId == result.state.path.last() }
                val next = engine.current(result.state)
                var s = it.copy(procedureState = result.state)
                    .log(Observation(ObservationKind.TECHNICIAN_REPORT, "${before.title}: ${chosen.label}", now()))
                if (next.kind != NodeKind.STEP) s = s.log(system("${next.title}. ${next.instruction}"))
                s
            }
            is StepResult.NotUnderstood -> updateActive { it.log(system(result.reason)) }
        }
        return result
    }

    fun back() {
        val session = activeSession() ?: return
        val engine = engine(session) ?: return
        val ps = session.procedureState ?: return
        updateActive { it.copy(procedureState = engine.back(ps)).log(system("Went back one step.")) }
    }

    fun setPaused(paused: Boolean) {
        updateActive { s ->
            s.copy(
                procedureState = s.procedureState?.copy(paused = paused),
                status = if (paused) SessionStatus.PAUSED else SessionStatus.ACTIVE,
            ).log(system(if (paused) "Guidance paused." else "Guidance resumed."))
        }
    }

    fun notSure() {
        updateActive {
            it.log(system("BobAI will not advance on an uncertain result. Re-check the reading, open the source, or stop and escalate."))
        }
    }

    // ---- evidence ----------------------------------------------------------

    fun ask(question: String): EvidenceAnswer {
        val answer = knowledge.answer(question, activeSession()?.equipment)
        updateActive { s ->
            val asked = s.log(Observation(ObservationKind.TECHNICIAN_REPORT, question, now()))
            when (answer) {
                is EvidenceAnswer.Answer -> asked.log(
                    Observation(
                        ObservationKind.SOURCE_STATEMENT,
                        (if (answer.stale) "Source pack has expired — refresh before relying on this. " else "") + answer.excerpt,
                        now(), answer.citations.first(),
                    ),
                )
                is EvidenceAnswer.Conflict -> asked.log(system("Conflicting approved sources: " + answer.citations.joinToString { it.label() } + ". BobAI will not choose between them — escalate."))
                is EvidenceAnswer.NoApplicableDocuments -> asked.log(system("No approved, downloaded document applies to ${answer.model}."))
                EvidenceAnswer.NoEvidence -> asked.log(system("No applicable evidence found in approved sources. BobAI won't guess — check the manual or escalate."))
                EvidenceAnswer.EquipmentNotConfirmed -> asked.log(system("Confirm the exact model before asking equipment questions."))
            }
        }
        return answer
    }

    fun addNote(text: String) {
        if (text.isBlank()) return
        updateActive { it.log(Observation(ObservationKind.TECHNICIAN_REPORT, text.trim(), now())) }
    }

    /** Mock capture: records an explicit, user-initiated photo reference. No camera is used. */
    fun mockCapture(): String? {
        val ref = "$MOCK_PHOTO_PREFIX${now()}"
        return updateActive {
            it.copy(mediaReferences = it.mediaReferences + ref)
                .log(Observation(ObservationKind.VISUAL_CAPTURE, "Photo captured (mock placeholder — no camera used).", now()))
        }?.let { ref }
    }

    /**
     * Records a real photo the technician explicitly captured. [ref] is the
     * image's path relative to app storage. Returns the updated session, or
     * null when no session is active (the caller should discard the file).
     */
    fun addCapture(ref: String, source: String): Session? = updateActive {
        it.copy(mediaReferences = it.mediaReferences + ref)
            .log(Observation(ObservationKind.VISUAL_CAPTURE, "Photo captured from $source.", now()))
    }

    fun endSession(): Session? {
        val ended = updateActive { s -> s.copy(status = SessionStatus.ENDED, draftSummary = s.draftSummary.ifBlank { draftSummary(s) }) }
        mutate { it.copy(activeSessionId = null) }
        return ended
    }

    fun saveSummary(sessionId: String, text: String, reviewed: Boolean) {
        val jobId = session(sessionId)?.jobId
        mutate { s ->
            s.copy(
                sessions = s.sessions.map {
                    if (it.id == sessionId) it.copy(draftSummary = text, summaryReviewed = reviewed, updatedAt = now()) else it
                },
                jobs = if (reviewed) {
                    s.jobs.map { if (it.id == jobId) it.copy(status = JobStatus.COMPLETED, updatedAt = now()) else it }
                } else s.jobs,
            )
        }
    }

    fun draftSummary(s: Session): String = buildString {
        appendLine("DRAFT — review before sharing. Saved on this device only.")
        appendLine("Equipment: ${s.equipment?.let { "${it.model} (S/N ${it.serial.ifBlank { "n/a" }})" } ?: "not confirmed"}")
        val engine = engine(s)
        s.procedureState?.let { ps ->
            engine?.let {
                val node = it.current(ps)
                appendLine(if (it.isTerminal(ps)) "Procedure outcome: ${node.title}" else "Procedure not finished — ended at step: ${node.title}")
            }
        }
        appendLine()
        appendLine("Technician reports:")
        s.observations.filter { it.kind == ObservationKind.TECHNICIAN_REPORT }.forEach { appendLine("• ${it.text}") }
        val sources = s.observations.mapNotNull { it.citation }.distinct()
        if (sources.isNotEmpty()) {
            appendLine()
            appendLine("Sources cited:")
            sources.forEach { appendLine("• ${it.label()}") }
        }
        val (mockPhotos, realPhotos) = s.mediaReferences.partition { isMockPhoto(it) }
        if (realPhotos.isNotEmpty() || mockPhotos.isNotEmpty()) {
            appendLine()
            append("Photos: ${realPhotos.size} captured")
            if (mockPhotos.isNotEmpty()) append(", ${mockPhotos.size} mock placeholder(s)")
            appendLine()
        }
    }.trim()

    // ---- office sync ---------------------------------------------------------

    /** Installs the approved manuals downloaded from the office as the "office-approved" pack. */
    fun applyOfficeKnowledge(k: OfficeKnowledge) {
        val docs = k.documents.map { it.copy(packId = OFFICE_PACK_ID, isMock = false) }
        officeModels = docs.flatMap { d -> d.applicableModels.map { resolver.normalizeModel(it) to d.title } }.toMap()
        officeTenant = k.tenantId
        officeExpiresAt = k.expiresAt
        officeDocs = docs
        val t = now()
        val pack = KnowledgePack(
            id = OFFICE_PACK_ID, tenantId = k.tenantId, name = "Office manuals",
            version = k.generatedAt.toString(), sizeBytes = 0, documentIds = docs.map { it.id },
            state = PackState.COMPLETE, downloadedAt = t, lastVerifiedAt = t, expiresAt = k.expiresAt,
        )
        mutate { s ->
            s.copy(packs = if (s.packs.any { it.id == OFFICE_PACK_ID }) s.packs.map { if (it.id == OFFICE_PACK_ID) pack else it } else s.packs + pack)
        }
    }

    /** Replaces the office-assigned jobs with [w]; local jobs and jobs with a live session stay. */
    fun applyOfficeWork(w: OfficeWork) {
        val t = now()
        val tenant = officeTenant ?: MockContent.TENANT
        mutate { s ->
            val liveJobIds = s.sessions.filter { it.status != SessionStatus.ENDED }.map { it.jobId }.toSet()
            val incoming = w.jobs.map { "office-" + it.id }.toSet()
            val existing = s.jobs.associateBy { it.id }
            val kept = s.jobs.filter { it.officeId == null || it.id in incoming || it.id in liveJobIds }
                .filter { it.id !in incoming }
            val upserted = w.jobs.map { row ->
                val id = "office-" + row.id
                Job(
                    id = id, officeId = row.id, tenantId = tenant, site = row.site, title = row.title,
                    description = row.description, dueAt = row.dueAt,
                    status = when (row.status) {
                        "in_progress" -> JobStatus.IN_PROGRESS
                        "completed" -> JobStatus.COMPLETED
                        else -> JobStatus.OPEN
                    },
                    isMock = false, syncState = SyncState.SYNCED,
                    equipmentId = existing[id]?.equipmentId,
                    createdAt = existing[id]?.createdAt ?: t, updatedAt = t,
                    tasks = w.tasks.filter { it.jobId == row.id }.map { JobTask(it.id, it.title, it.completed == 1) },
                )
            }
            s.copy(jobs = kept + upserted)
        }
    }

    // ---- packs (simulated; no network transfer happens) --------------------

    fun setPackState(packId: String, state: PackState) {
        val t = now()
        mutate { s ->
            s.copy(packs = s.packs.map { p ->
                if (p.id != packId) p else when (state) {
                    PackState.COMPLETE -> p.copy(state = state, downloadedAt = t, lastVerifiedAt = t, expiresAt = t + 30L * 24 * 3600 * 1000)
                    PackState.NOT_DOWNLOADED -> p.copy(state = state, downloadedAt = null, lastVerifiedAt = null, expiresAt = null)
                    else -> p.copy(state = state)
                }
            })
        }
    }
}

const val OFFICE_PACK_ID = "office-approved"

const val MOCK_PHOTO_PREFIX = "mock-photo-"

/** True for placeholder references made by [BobAiController.mockCapture]. */
fun isMockPhoto(ref: String): Boolean = ref.startsWith(MOCK_PHOTO_PREFIX)
