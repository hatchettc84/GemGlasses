package com.lpecom.gemglasses.bobai.domain

import kotlinx.serialization.Serializable

@Serializable
enum class NodeKind { STEP, STOP, COMPLETE }

/** A result the technician can report for a step. Selecting it moves to [nextNodeId]. */
@Serializable
data class ResultOption(
    val id: String,
    val label: String,
    val nextNodeId: String,
)

@Serializable
data class ProcedureNode(
    val id: String,
    val kind: NodeKind,
    val title: String,
    val instruction: String,
    val citation: EvidenceCitation? = null,
    val options: List<ResultOption> = emptyList(),
)

@Serializable
data class Procedure(
    val id: String,
    val version: String,
    val title: String,
    val applicableModels: Set<String>,
    val requiredAuthorization: String,
    val prerequisites: List<String>,
    val startNodeId: String,
    val nodes: Map<String, ProcedureNode>,
    val isMock: Boolean = true,
)

/** Persisted position in a procedure. [path] is the visited node ids, most recent last. */
@Serializable
data class ProcedureState(
    val procedureId: String,
    val procedureVersion: String,
    val acknowledgedPrerequisites: Set<Int> = emptySet(),
    val path: List<String>,
    val paused: Boolean = false,
)

sealed interface StepResult {
    data class Moved(val state: ProcedureState) : StepResult

    /** The input did not unambiguously match an allowed result; state is unchanged. */
    data class NotUnderstood(val reason: String) : StepResult
}

/**
 * Deterministic procedure controller (brief §7 ProcedureEngine).
 *
 * Rules enforced here rather than in the UI:
 *  - Steps cannot advance until every prerequisite is acknowledged.
 *  - The engine only advances on an exact, allowed result. Free-form or
 *    ambiguous input never advances ("never advance because an ambiguous
 *    utterance resembles confirmation").
 *  - STOP and COMPLETE nodes are terminal; nothing advances past them.
 *  - Paused procedures do not advance.
 */
class ProcedureEngine(private val procedure: Procedure) {

    init {
        require(procedure.startNodeId in procedure.nodes) { "start node missing" }
        procedure.nodes.values.flatMap { it.options }.forEach {
            require(it.nextNodeId in procedure.nodes) { "dangling branch to ${it.nextNodeId}" }
        }
    }

    fun start(): ProcedureState = ProcedureState(
        procedureId = procedure.id,
        procedureVersion = procedure.version,
        path = listOf(procedure.startNodeId),
    )

    fun prerequisitesMet(state: ProcedureState): Boolean =
        procedure.prerequisites.indices.all { it in state.acknowledgedPrerequisites }

    fun acknowledge(state: ProcedureState, index: Int, acknowledged: Boolean): ProcedureState {
        require(index in procedure.prerequisites.indices)
        val set = if (acknowledged) state.acknowledgedPrerequisites + index
        else state.acknowledgedPrerequisites - index
        return state.copy(acknowledgedPrerequisites = set)
    }

    fun current(state: ProcedureState): ProcedureNode =
        procedure.nodes.getValue(state.path.last())

    fun isTerminal(state: ProcedureState): Boolean = current(state).kind != NodeKind.STEP

    /**
     * Branching procedures have no honest fixed total, so the label reports the
     * position on the path actually taken rather than "step N of M".
     */
    fun stepLabel(state: ProcedureState): String = when (current(state).kind) {
        NodeKind.STEP -> "Check ${state.path.size} on this path"
        NodeKind.STOP -> "Stopped — escalation required"
        NodeKind.COMPLETE -> "Procedure complete"
    }

    fun choose(state: ProcedureState, optionId: String): StepResult {
        if (!prerequisitesMet(state)) return StepResult.NotUnderstood("Confirm the prerequisites first.")
        if (state.paused) return StepResult.NotUnderstood("Guidance is paused. Resume to continue.")
        val node = current(state)
        if (node.kind != NodeKind.STEP) return StepResult.NotUnderstood("This procedure has ended.")
        val option = node.options.firstOrNull { it.id == optionId }
            ?: return StepResult.NotUnderstood("That result is not an option for this step.")
        return StepResult.Moved(state.copy(path = state.path + option.nextNodeId))
    }

    /**
     * Maps free text (typed or transcribed) to a result only when it exactly
     * matches one option label, ignoring case and surrounding punctuation.
     * Anything else — "yeah I think so", "ok", partial matches — is rejected.
     */
    fun interpret(state: ProcedureState, utterance: String): StepResult {
        val normalized = normalize(utterance)
        if (normalized.isEmpty()) return StepResult.NotUnderstood("Nothing was entered.")
        val node = current(state)
        val matches = node.options.filter { normalize(it.label) == normalized }
        return when (matches.size) {
            1 -> choose(state, matches.single().id)
            else -> StepResult.NotUnderstood(
                "Not sure which result you mean. Choose one of: " +
                    node.options.joinToString { "\"${it.label}\"" } + ".",
            )
        }
    }

    fun back(state: ProcedureState): ProcedureState =
        if (state.path.size > 1) state.copy(path = state.path.dropLast(1)) else state

    fun setPaused(state: ProcedureState, paused: Boolean): ProcedureState = state.copy(paused = paused)

    private fun normalize(s: String): String =
        s.lowercase().trim().trim('.', '!', '?', ',', '"', '\'').replace(Regex("\\s+"), " ")
}
