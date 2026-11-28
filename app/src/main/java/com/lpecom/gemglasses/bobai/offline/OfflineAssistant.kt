package com.lpecom.gemglasses.bobai.offline

import android.util.Log
import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.bobai.domain.EvidenceAnswer
import com.lpecom.gemglasses.bobai.domain.EvidenceCitation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class ModelStatus(val label: String) {
    NO_MODEL("Offline AI not configured — no model on this phone"),
    NOT_LOADED("Model found — not loaded"),
    LOADING("Loading on-device model…"),
    SELF_TEST_FAILED("Model loaded but failed the grounding self-test"),
    READY("On-device model ready (self-test passed)"),
    FAILED("On-device model failed to load"),
}

data class OfflineState(
    val status: ModelStatus = ModelStatus.NO_MODEL,
    val backend: String? = null,
    val detail: String? = null,
    val busy: Boolean = false,
    val lastQuestion: String? = null,
    val lastAnswer: OfflineAnswer? = null,
    val lastLatencyMs: Long? = null,
) {
    /** Only a model that passed the self-test may enable "Offline assisted" mode. */
    val validated: Boolean get() = status == ModelStatus.READY
}

/**
 * On-device answering (spike). Search picks the evidence through the same
 * [BobAiController.ask] the app already uses (which logs the verbatim excerpt
 * to the job); the model only rephrases it, and [GroundingValidator] decides
 * whether that wording may be shown. The model's wording is never saved.
 */
@Singleton
class OfflineAssistant internal constructor(
    private val ask: (String) -> EvidenceAnswer,
    private val llm: LocalLlm,
) {
    @Inject constructor(controller: BobAiController, llm: LocalLlm) : this({ q -> controller.ask(q) }, llm)

    private val _state = MutableStateFlow(OfflineState())
    val state: StateFlow<OfflineState> = _state.asStateFlow()

    init { refreshPresence() }

    /** Re-checks for the model file (e.g. after it was pushed with adb). */
    fun refreshPresence() {
        val present = llm.modelFile.isFile
        _state.update { s ->
            when {
                !present -> s.copy(status = ModelStatus.NO_MODEL, backend = null, detail = "Expected at ${llm.modelFile.path}")
                s.status == ModelStatus.NO_MODEL -> s.copy(status = ModelStatus.NOT_LOADED, detail = null)
                else -> s
            }
        }
    }

    /** Loads the model, then runs the grounding self-test before marking it ready. */
    suspend fun load() {
        refreshPresence()
        if (_state.value.status == ModelStatus.NO_MODEL || _state.value.status == ModelStatus.LOADING) return
        _state.update { it.copy(status = ModelStatus.LOADING, detail = null) }
        try {
            val backend = llm.load()
            val result = selfTest()
            _state.update {
                if (result == null) it.copy(status = ModelStatus.READY, backend = backend, detail = null)
                else it.copy(status = ModelStatus.SELF_TEST_FAILED, backend = backend, detail = result)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "load failed", t)
            _state.update { it.copy(status = ModelStatus.FAILED, detail = t.message?.take(200)) }
        }
    }

    suspend fun answer(question: String): OfflineAnswer {
        _state.update { it.copy(busy = true, lastQuestion = question) }
        val started = System.currentTimeMillis()
        val result = try {
            resolve(question)
        } finally {
            _state.update { it.copy(busy = false) }
        }
        _state.update { it.copy(lastAnswer = result, lastLatencyMs = System.currentTimeMillis() - started) }
        return result
    }

    private suspend fun resolve(question: String): OfflineAnswer {
        val evidence = ask(question)
        if (evidence !is EvidenceAnswer.Answer) return OfflineAnswer.NoAnswer(evidence, message(evidence))
        if (!_state.value.validated) {
            return OfflineAnswer.Verbatim(evidence.excerpt, evidence.citations, evidence.stale, "on-device model not ready")
        }
        val raw = try {
            llm.generate(GroundedPrompt.systemInstruction, GroundedPrompt.userMessage(question, evidence))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(TAG, "generation failed", t)
            return OfflineAnswer.Verbatim(evidence.excerpt, evidence.citations, evidence.stale, "model error")
        }
        return when (val v = GroundingValidator.check(raw, evidence)) {
            is GroundingValidator.Verdict.Ok -> OfflineAnswer.Phrased(v.text, evidence.citations, evidence.stale)
            GroundingValidator.Verdict.NotInSource -> OfflineAnswer.NotInSource(evidence.citations)
            is GroundingValidator.Verdict.Rejected -> {
                Log.i(TAG, "model wording rejected: ${v.reason}")
                OfflineAnswer.Verbatim(evidence.excerpt, evidence.citations, evidence.stale, "wording rejected: ${v.reason}")
            }
        }
    }

    /** Returns null when the model restates a known excerpt correctly, else the reason it failed. */
    private suspend fun selfTest(): String? {
        val raw = runCatching { llm.generate(GroundedPrompt.systemInstruction, GroundedPrompt.userMessage(SELF_TEST_Q, SELF_TEST)) }
            .getOrElse { return "self-test error: ${it.message}" }
        Log.i(TAG, "self-test output: ${raw.take(200)}")
        return when (val v = GroundingValidator.check(raw, SELF_TEST)) {
            is GroundingValidator.Verdict.Ok -> if ("5" in v.text) null else "self-test answer left out the 5-minute wait"
            GroundingValidator.Verdict.NotInSource -> "self-test: model claimed the source had no answer"
            is GroundingValidator.Verdict.Rejected -> "self-test: ${v.reason}"
        }
    }

    companion object {
        private const val TAG = "OfflineAssistant"

        // Synthetic fixture for the self-test only — not from any real manual.
        private val SELF_TEST = EvidenceAnswer.Answer(
            excerpt = "Turn off power at the service disconnect, then wait 5 minutes before removing the burner access panel.",
            citations = listOf(EvidenceCitation("self-test", "Self-test fixture", "A", 1, "Turn off power…")),
            stale = false,
        )
        private const val SELF_TEST_Q = "How long do I wait before removing the burner access panel?"

        fun message(evidence: EvidenceAnswer): String = when (evidence) {
            is EvidenceAnswer.Answer -> evidence.excerpt
            is EvidenceAnswer.Conflict ->
                "Conflicting approved sources. BobAI will not choose between them — escalate."
            is EvidenceAnswer.NoApplicableDocuments -> "No approved, downloaded document applies to ${evidence.model}."
            EvidenceAnswer.NoEvidence -> "No applicable evidence found in approved sources. BobAI won't guess — check the manual or escalate."
            EvidenceAnswer.EquipmentNotConfirmed -> "Confirm the exact model before asking equipment questions."
        }
    }
}
