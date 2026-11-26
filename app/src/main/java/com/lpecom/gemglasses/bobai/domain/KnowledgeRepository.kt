package com.lpecom.gemglasses.bobai.domain

/** Outcome of an evidence lookup. Every operational answer carries citations. */
sealed interface EvidenceAnswer {
    data class Answer(
        val excerpt: String,
        val citations: List<EvidenceCitation>,
        val stale: Boolean,
    ) : EvidenceAnswer

    data object EquipmentNotConfirmed : EvidenceAnswer

    data class NoApplicableDocuments(val model: String) : EvidenceAnswer

    data object NoEvidence : EvidenceAnswer

    /** Two approved revisions of the same document both match; we do not guess. */
    data class Conflict(val citations: List<EvidenceCitation>) : EvidenceAnswer
}

/**
 * Local, simple keyword retrieval over approved, applicable documents
 * (brief §8 "start with a simple local search"). Semantic retrieval is a
 * later, evaluation-gated addition.
 *
 * Filtering happens before ranking: tenant, approval, exact-model
 * applicability, and pack availability. Document text is treated as data —
 * it is only ever quoted, never interpreted as instructions.
 */
class KnowledgeRepository(
    private val documents: () -> List<SourceDocument>,
    private val packs: () -> List<KnowledgePack>,
    private val now: () -> Long,
) {

    fun allDocuments(): List<SourceDocument> = documents()

    fun document(id: String): SourceDocument? = documents().firstOrNull { it.id == id }

    /** Whether the document's pack is downloaded, verified and in date. */
    fun availability(doc: SourceDocument): DocAvailability {
        val pack = doc.packId?.let { id -> packs().firstOrNull { it.id == id } }
            ?: return DocAvailability.NOT_IN_PACK
        return when {
            pack.state == PackState.COMPLETE && pack.isExpired(now()) -> DocAvailability.STALE
            pack.state == PackState.COMPLETE -> DocAvailability.LOCAL
            pack.state == PackState.CORRUPTED -> DocAvailability.CORRUPTED
            else -> DocAvailability.NOT_DOWNLOADED
        }
    }

    fun applicableDocuments(equipment: Equipment): List<SourceDocument> {
        if (!equipment.isConfirmed) return emptyList()
        val model = equipment.model.trim().uppercase()
        return documents().filter { doc ->
            doc.tenantId == equipment.tenantId &&
                doc.approvalStatus == ApprovalStatus.APPROVED &&
                model in doc.applicableModels.map { it.uppercase() } &&
                availability(doc).readable
        }
    }

    fun answer(question: String, equipment: Equipment?): EvidenceAnswer {
        if (equipment == null || !equipment.isConfirmed) return EvidenceAnswer.EquipmentNotConfirmed
        val docs = applicableDocuments(equipment)
        if (docs.isEmpty()) return EvidenceAnswer.NoApplicableDocuments(equipment.model)

        val terms = tokenize(question)
        if (terms.isEmpty()) return EvidenceAnswer.NoEvidence

        val hits = docs.flatMap { doc ->
            doc.pages.map { page -> Hit(doc, page, score(terms, page)) }
        }.filter { it.score >= MIN_SCORE }.sortedByDescending { it.score }

        if (hits.isEmpty()) return EvidenceAnswer.NoEvidence

        val best = hits.first()
        // Another revision of the same document family with a comparable hit is a conflict.
        val rivals = hits.filter {
            it.doc.family == best.doc.family &&
                it.doc.revision != best.doc.revision &&
                it.score >= best.score - 1
        }
        if (rivals.isNotEmpty()) {
            return EvidenceAnswer.Conflict(
                (listOf(best) + rivals).distinctBy { it.doc.id }.map { citation(it.doc, it.page) },
            )
        }

        return EvidenceAnswer.Answer(
            excerpt = best.page.body,
            citations = listOf(citation(best.doc, best.page)),
            stale = availability(best.doc) == DocAvailability.STALE,
        )
    }

    private data class Hit(val doc: SourceDocument, val page: DocPage, val score: Int)

    private fun citation(doc: SourceDocument, page: DocPage) = EvidenceCitation(
        documentId = doc.id,
        documentTitle = doc.title,
        revision = doc.revision,
        page = page.number,
        excerpt = page.body.take(160),
    )

    private fun score(terms: Set<String>, page: DocPage): Int {
        val headingTerms = tokenize(page.heading)
        val bodyTerms = tokenize(page.body)
        return terms.sumOf { t -> (if (t in headingTerms) 2 else 0) + (if (t in bodyTerms) 1 else 0) }
    }

    companion object {
        private const val MIN_SCORE = 2
        private val STOPWORDS = setOf(
            "the", "a", "an", "is", "are", "what", "how", "do", "i", "to", "of", "for", "on",
            "in", "and", "or", "it", "this", "that", "my", "me", "should", "can", "be", "with",
            "does", "which", "where", "when", "unit",
        )

        fun tokenize(text: String): Set<String> =
            text.lowercase().split(Regex("[^a-z0-9]+"))
                .filter { it.length > 1 && it !in STOPWORDS }
                .toSet()
    }
}

enum class DocAvailability(val readable: Boolean, val label: String) {
    LOCAL(true, "Downloaded"),
    STALE(true, "Downloaded — expired, refresh required"),
    NOT_DOWNLOADED(false, "Not downloaded"),
    CORRUPTED(false, "Download corrupted"),
    NOT_IN_PACK(false, "Not in any pack"),
}
