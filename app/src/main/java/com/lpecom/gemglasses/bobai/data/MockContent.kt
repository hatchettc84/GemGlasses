package com.lpecom.gemglasses.bobai.data

import com.lpecom.gemglasses.bobai.domain.ApprovalStatus
import com.lpecom.gemglasses.bobai.domain.DocPage
import com.lpecom.gemglasses.bobai.domain.EvidenceCitation
import com.lpecom.gemglasses.bobai.domain.KnowledgePack
import com.lpecom.gemglasses.bobai.domain.NodeKind
import com.lpecom.gemglasses.bobai.domain.PackState
import com.lpecom.gemglasses.bobai.domain.Procedure
import com.lpecom.gemglasses.bobai.domain.ProcedureNode
import com.lpecom.gemglasses.bobai.domain.ResultOption
import com.lpecom.gemglasses.bobai.domain.SourceDocument

/**
 * MOCK CONTENT — fictional equipment, documents, packs and procedures used only
 * to exercise the Phase 1 UI. None of these values are real specifications.
 * Every title carries a [MOCK] prefix so it can never be mistaken for approved
 * field content.
 */
object MockContent {
    const val TENANT = "mock-tenant"
    const val TECHNICIAN = "Local technician (mock profile)"

    /** Model → manufacturer for fictional equipment. */
    val knownModels = mapOf(
        "ABC-123" to "Acme Mock Heating Co.",
        "XYZ-900" to "Zenith Mock Systems",
        "QRS-410" to "Quill Mock Appliances",
    )

    /** What the mock "label photo" says. Real OCR would replace this. */
    const val MOCK_LABEL_TEXT = "ACME MOCK HEATING CO.  MODEL ABC-123  S/N MK2026-00417  120V 60Hz"
    const val MOCK_AMBIGUOUS_LABEL_TEXT = "MODEL ABC-123 / ABC-128 (smudged)  S/N MK2026-00417"

    private fun manual(id: String, family: String, rev: String, models: Set<String>, status: ApprovalStatus, pack: String?, title: String, pages: List<DocPage>) =
        SourceDocument(id, TENANT, title, family, rev, models, status, pack, pages, isMock = true)

    val ABC_MANUAL_B = manual(
        "doc-abc-manual-b", "abc-service-manual", "B", setOf("ABC-123"), ApprovalStatus.APPROVED, "pack-abc",
        "[MOCK] ABC-123 Service Manual",
        listOf(
            DocPage(1, "Safety and isolation", "Follow your company lockout/tagout procedure before opening the cabinet. Verify absence of voltage with a rated meter. Images cannot confirm isolation. (Mock text.)"),
            DocPage(12, "Thermostat call for heat", "Raise the thermostat setpoint 5 degrees above room temperature. The call-for-heat indicator on the control board should light within 30 seconds. (Mock text.)"),
            DocPage(27, "Error codes", "E1: igniter circuit fault — continue to the igniter resistance check. E4: gas supply pressure fault — do not service; escalate to a supervisor. (Mock codes.)"),
            DocPage(42, "Igniter resistance check", "With power isolated, disconnect connector J3 and measure igniter resistance. Acceptable range: 40 to 90 ohms. This is a MOCK value, not a real specification."),
            DocPage(43, "Igniter replacement", "Replace the igniter with approved part MOCK-IGN-01. Handle by the base only. Reconnect J3 and restore power per company procedure. (Mock text.)"),
        ),
    )

    private val ABC_MANUAL_A_REVOKED = manual(
        "doc-abc-manual-a", "abc-service-manual", "A", setOf("ABC-123"), ApprovalStatus.REVOKED, "pack-abc",
        "[MOCK] ABC-123 Service Manual",
        listOf(DocPage(42, "Igniter resistance check", "Superseded revision. Acceptable range: 30 to 70 ohms. (Mock, revoked.)")),
    )

    private val ABC_INSTALL = manual(
        "doc-abc-install", "abc-install-guide", "C", setOf("ABC-123"), ApprovalStatus.APPROVED, "pack-abc",
        "[MOCK] ABC-123 Installation Guide",
        listOf(
            DocPage(4, "Clearances", "Maintain 6 inches clearance on the sides and 24 inches at the front service panel. (Mock values.)"),
            DocPage(9, "Venting", "Use the vent diameter listed on the rating plate. Do not reduce vent size. (Mock text.)"),
        ),
    )

    private val ABC_DRAFT_NOTE = manual(
        "doc-abc-field-note", "abc-field-notes", "draft", setOf("ABC-123"), ApprovalStatus.DRAFT, "pack-abc",
        "[MOCK] Unreviewed technician field note",
        listOf(DocPage(1, "Field note", "Unreviewed observation from a previous visit. Not approved guidance. (Mock.)")),
    )

    private val XYZ_REV1 = manual(
        "doc-xyz-manual-1", "xyz-service-manual", "1", setOf("XYZ-900"), ApprovalStatus.APPROVED, "pack-xyz",
        "[MOCK] XYZ-900 Service Manual",
        listOf(DocPage(18, "Igniter resistance check", "Measure igniter resistance at the plug. Acceptable range: 50 to 100 ohms. (Mock value.)")),
    )

    private val XYZ_REV2 = manual(
        "doc-xyz-manual-2", "xyz-service-manual", "2", setOf("XYZ-900"), ApprovalStatus.APPROVED, "pack-xyz",
        "[MOCK] XYZ-900 Service Manual",
        listOf(DocPage(18, "Igniter resistance check", "Measure igniter resistance at the plug. Acceptable range: 45 to 80 ohms. (Mock value.)")),
    )

    val documents: List<SourceDocument> =
        listOf(ABC_MANUAL_B, ABC_MANUAL_A_REVOKED, ABC_INSTALL, ABC_DRAFT_NOTE, XYZ_REV1, XYZ_REV2)

    private const val DAY = 24L * 60 * 60 * 1000

    fun initialPacks(now: Long): List<KnowledgePack> = listOf(
        KnowledgePack(
            "pack-abc", TENANT, "[MOCK] Acme ABC-123 service pack", "2026.11.1", 48_000_000,
            listOf(ABC_MANUAL_B.id, ABC_MANUAL_A_REVOKED.id, ABC_INSTALL.id, ABC_DRAFT_NOTE.id),
            PackState.COMPLETE, downloadedAt = now, lastVerifiedAt = now, expiresAt = now + 30 * DAY,
        ),
        KnowledgePack(
            "pack-xyz", TENANT, "[MOCK] Zenith XYZ-900 service pack", "2026.08.2", 31_000_000,
            listOf(XYZ_REV1.id, XYZ_REV2.id),
            PackState.COMPLETE, downloadedAt = now - 40 * DAY, lastVerifiedAt = now - 40 * DAY, expiresAt = now - 2 * DAY,
        ),
        KnowledgePack(
            "pack-hvac-general", TENANT, "[MOCK] General HVAC reference", "2026.10.0", 120_000_000,
            emptyList(), PackState.NOT_DOWNLOADED,
        ),
        KnowledgePack(
            "pack-legacy", TENANT, "[MOCK] Legacy appliance pack", "2025.01.0", 15_000_000,
            emptyList(), PackState.CORRUPTED, downloadedAt = now - 90 * DAY,
        ),
    )

    private fun cite(page: Int): EvidenceCitation {
        val p = ABC_MANUAL_B.pages.first { it.number == page }
        return EvidenceCitation(ABC_MANUAL_B.id, ABC_MANUAL_B.title, ABC_MANUAL_B.revision, page, p.body.take(160))
    }

    val NO_HEAT_PROCEDURE = Procedure(
        id = "proc-abc-no-heat",
        version = "1.0-mock",
        title = "[MOCK] ABC-123 no-heat troubleshooting",
        applicableModels = setOf("ABC-123"),
        requiredAuthorization = "Trained technician or supervised apprentice",
        prerequisites = listOf(
            "I am trained and authorized to service this type of unit.",
            "I have isolated power and gas using my company's lockout/tagout procedure. BobAI cannot verify isolation from images.",
            "The model on the label matches ABC-123.",
        ),
        startNodeId = "thermostat",
        nodes = listOf(
            ProcedureNode(
                "thermostat", NodeKind.STEP, "Check the call for heat",
                "Restore control power per company procedure. Raise the thermostat setpoint 5° above room temperature and watch the call-for-heat indicator for 30 seconds.",
                cite(12),
                listOf(ResultOption("on", "Indicator on", "error_code"), ResultOption("off", "Indicator off", "stop_thermostat")),
            ),
            ProcedureNode(
                "error_code", NodeKind.STEP, "Read the error code",
                "Read the code shown on the control board display.",
                cite(27),
                listOf(
                    ResultOption("e1", "E1", "igniter"),
                    ResultOption("e4", "E4", "stop_gas"),
                    ResultOption("none", "No code", "stop_no_branch"),
                ),
            ),
            ProcedureNode(
                "igniter", NodeKind.STEP, "Measure igniter resistance",
                "Isolate power again. Disconnect J3 and measure igniter resistance. Compare with the range in the cited manual page.",
                cite(42),
                listOf(
                    ResultOption("in", "Within range", "stop_igniter_ok"),
                    ResultOption("out", "Outside range", "replace"),
                ),
            ),
            ProcedureNode(
                "replace", NodeKind.STEP, "Replace the igniter",
                "Replace the igniter with the approved part and restore power per company procedure. Run a heat cycle.",
                cite(43),
                listOf(
                    ResultOption("fixed", "Unit heats", "done"),
                    ResultOption("still", "Still no heat", "stop_after_replace"),
                ),
            ),
            ProcedureNode("stop_thermostat", NodeKind.STOP, "Outside this procedure", "No call for heat points to the thermostat or control wiring, which this procedure does not cover. Stop here and escalate or use an approved thermostat procedure."),
            ProcedureNode("stop_gas", NodeKind.STOP, "Stop — escalate", "E4 is a gas supply pressure fault. The approved source says not to service it. Stop and escalate to your supervisor.", cite(27)),
            ProcedureNode("stop_no_branch", NodeKind.STOP, "No approved branch", "There is no approved branch for a no-heat condition without an error code. Stop and escalate, or use the manual in reference mode."),
            ProcedureNode("stop_igniter_ok", NodeKind.STOP, "Igniter within range — escalate", "The igniter reading is within the cited range, so the approved procedure has no further branch. Stop and escalate."),
            ProcedureNode("stop_after_replace", NodeKind.STOP, "Still no heat — escalate", "The replacement did not resolve the fault. Stop and escalate. Record the readings in the job summary."),
            ProcedureNode("done", NodeKind.COMPLETE, "Procedure complete", "The unit is heating. Record readings, parts used and the outcome in the job summary."),
        ).associateBy { it.id },
    )

    val procedures: List<Procedure> = listOf(NO_HEAT_PROCEDURE)
}
