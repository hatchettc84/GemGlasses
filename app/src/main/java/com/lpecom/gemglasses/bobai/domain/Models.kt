package com.lpecom.gemglasses.bobai.domain

import kotlinx.serialization.Serializable

/*
 * BobAI conceptual data model (brief §11). These are deliberately small and
 * Android-free so the procedure, retrieval and confirmation logic can be unit
 * tested on the JVM. They are not a mandated storage schema.
 */

@Serializable
enum class ConfirmationStatus { UNCONFIRMED, CONFIRMED }

@Serializable
data class Equipment(
    val id: String,
    val tenantId: String,
    val manufacturer: String?,
    val model: String,
    val serial: String,
    val confirmationStatus: ConfirmationStatus,
    val confirmedBy: String? = null,
) {
    val isConfirmed: Boolean get() = confirmationStatus == ConfirmationStatus.CONFIRMED
}

@Serializable
enum class ApprovalStatus { APPROVED, DRAFT, REVOKED }

@Serializable
data class DocPage(
    val number: Int,
    val heading: String,
    val body: String,
)

@Serializable
data class SourceDocument(
    val id: String,
    val tenantId: String,
    val title: String,
    /** Documents in the same family are revisions of one another. */
    val family: String,
    val revision: String,
    val applicableModels: Set<String>,
    val approvalStatus: ApprovalStatus,
    val packId: String?,
    val pages: List<DocPage>,
    val isMock: Boolean = true,
)

@Serializable
data class EvidenceCitation(
    val documentId: String,
    val documentTitle: String,
    val revision: String,
    val page: Int,
    val excerpt: String,
) {
    fun label(): String = "$documentTitle, rev $revision, page $page"
}

@Serializable
enum class PackState { NOT_DOWNLOADED, PENDING, COMPLETE, CORRUPTED, INSUFFICIENT_STORAGE }

@Serializable
data class KnowledgePack(
    val id: String,
    val tenantId: String,
    val name: String,
    val version: String,
    val sizeBytes: Long,
    val documentIds: List<String>,
    val state: PackState,
    val downloadedAt: Long? = null,
    val lastVerifiedAt: Long? = null,
    val expiresAt: Long? = null,
) {
    fun isUsable(now: Long): Boolean = state == PackState.COMPLETE && !isExpired(now)
    fun isExpired(now: Long): Boolean = expiresAt != null && now >= expiresAt
}

/** Who/what an entry in the session timeline came from (brief §8: keep these distinct). */
@Serializable
enum class ObservationKind {
    /** Something the technician said or typed. */
    TECHNICIAN_REPORT,

    /** A photo or visual note the technician captured. */
    VISUAL_CAPTURE,

    /** A statement quoted from an approved source, always with a citation. */
    SOURCE_STATEMENT,

    /** Deterministic system/procedure messages (not generative). */
    SYSTEM,
}

@Serializable
data class Observation(
    val kind: ObservationKind,
    val text: String,
    val timestamp: Long,
    val citation: EvidenceCitation? = null,
)

@Serializable
enum class SyncState { SAVED_LOCALLY, SYNC_PENDING, SYNCED, CONFLICT, SYNC_FAILED }

@Serializable
enum class JobStatus { OPEN, IN_PROGRESS, COMPLETED }

@Serializable
data class Job(
    val id: String,
    val tenantId: String,
    val site: String,
    val equipmentId: String? = null,
    val status: JobStatus = JobStatus.OPEN,
    val assignedUser: String = "local-technician",
    val syncState: SyncState = SyncState.SAVED_LOCALLY,
    val createdAt: Long,
    val updatedAt: Long,
    val isMock: Boolean = false,
    val title: String = "",
    val description: String = "",
    val dueAt: String? = null,
    /** Backend job id for jobs assigned from the office; null for local jobs. */
    val officeId: String? = null,
    val tasks: List<JobTask> = emptyList(),
)

@Serializable
data class JobTask(val id: String, val title: String, val done: Boolean)

@Serializable
enum class SessionStatus { ACTIVE, PAUSED, ENDED }

@Serializable
data class Session(
    val id: String,
    val jobId: String,
    val equipment: Equipment? = null,
    val procedureState: ProcedureState? = null,
    val observations: List<Observation> = emptyList(),
    val mediaReferences: List<String> = emptyList(),
    val draftSummary: String = "",
    val summaryReviewed: Boolean = false,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val createdAt: Long,
    val updatedAt: Long,
)
