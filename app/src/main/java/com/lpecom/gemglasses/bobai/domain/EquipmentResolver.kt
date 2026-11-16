package com.lpecom.gemglasses.bobai.domain

/** A model/serial guess extracted from a label. Always requires human confirmation. */
data class EquipmentCandidate(
    val model: String,
    val serial: String?,
    val source: String,
)

/**
 * Proposes equipment identities from label text and turns a technician's
 * explicit confirmation into a confirmed [Equipment]. It never confirms on
 * its own: [candidatesFromLabel] only proposes; [confirm] requires a human.
 *
 * Label text currently comes from mock OCR. Real OCR plugs in upstream of
 * [candidatesFromLabel] without changing the confirmation contract.
 */
class EquipmentResolver(
    private val knownModels: Map<String, String>,
) {

    fun candidatesFromLabel(labelText: String): List<EquipmentCandidate> {
        val serial = SERIAL.find(labelText)?.groupValues?.get(1)
        return MODEL.findAll(labelText.uppercase())
            .map { it.value }
            .distinct()
            .map { EquipmentCandidate(model = it, serial = serial, source = "Label text (mock OCR)") }
            .toList()
    }

    fun isAmbiguous(candidates: List<EquipmentCandidate>): Boolean =
        candidates.map { it.model }.distinct().size != 1

    fun normalizeModel(input: String): String = input.trim().uppercase().replace(Regex("\\s+"), "-")

    fun isKnownModel(model: String): Boolean = normalizeModel(model) in knownModels

    sealed interface Confirmation {
        data class Confirmed(val equipment: Equipment) : Confirmation
        data class Rejected(val reason: String) : Confirmation
    }

    fun confirm(
        id: String,
        tenantId: String,
        model: String,
        serial: String,
        confirmedBy: String,
    ): Confirmation {
        val normalized = normalizeModel(model)
        if (normalized.isBlank()) return Confirmation.Rejected("Enter the model number from the label.")
        if (!MODEL_SHAPE.matches(normalized)) {
            return Confirmation.Rejected("\"$model\" doesn't look like a model number. Check the label.")
        }
        return Confirmation.Confirmed(
            Equipment(
                id = id,
                tenantId = tenantId,
                manufacturer = knownModels[normalized],
                model = normalized,
                serial = serial.trim().uppercase(),
                confirmationStatus = ConfirmationStatus.CONFIRMED,
                confirmedBy = confirmedBy,
            ),
        )
    }

    private companion object {
        val MODEL = Regex("\\b[A-Z]{2,4}-\\d{2,4}[A-Z]?\\b")
        val MODEL_SHAPE = Regex("^[A-Z0-9][A-Z0-9-]{2,24}$")
        val SERIAL = Regex("(?i)\\b(?:S/?N|SERIAL)[:#\\s]*([A-Z0-9-]{4,})")
    }
}
