package com.lpecom.gemglasses.bobai.domain

/** Brief §6 operating modes. */
enum class OperatingMode(val label: String) {
    CONNECTED("Online"),
    OFFLINE_ASSISTED("Offline assisted"),
    OFFLINE_REFERENCE("Offline reference"),
    OFFLINE_NO_REFERENCES("Offline — no references"),
}

data class ModeStatus(
    val mode: OperatingMode,
    /** Plain-language description of what works right now. */
    val available: List<String>,
    /** What doesn't work, with the reason. Never hidden. */
    val unavailable: List<String>,
)

/**
 * Derives the active mode from facts only. Downloaded manuals never imply
 * offline AI: OFFLINE_ASSISTED requires a validated local model, which no
 * configuration has yet.
 */
object ModeResolver {

    fun resolve(
        networkAvailable: Boolean,
        usableReferencePacks: Int,
        localModelValidated: Boolean,
    ): ModeStatus {
        val refs = usableReferencePacks > 0
        val referenceFeatures = listOf(
            "Downloaded manuals and search",
            "Deterministic checklists",
            "Notes and saved job progress",
        )
        return when {
            networkAvailable -> ModeStatus(
                mode = OperatingMode.CONNECTED,
                available = (if (refs) referenceFeatures else emptyList()) +
                    "Talk to Bob (Gemini Live) — on the Assistant screen",
                unavailable = if (!refs) listOf("Offline references — no pack downloaded") else emptyList(),
            )

            localModelValidated && refs -> ModeStatus(
                mode = OperatingMode.OFFLINE_ASSISTED,
                available = referenceFeatures + "Validated local model",
                unavailable = listOf("Cloud voice assistant — no internet"),
            )

            refs -> ModeStatus(
                mode = OperatingMode.OFFLINE_REFERENCE,
                available = referenceFeatures,
                unavailable = listOf(
                    "Cloud voice assistant — no internet",
                    "Offline AI — no local model is configured or validated",
                ),
            )

            else -> ModeStatus(
                mode = OperatingMode.OFFLINE_NO_REFERENCES,
                available = listOf("Notes and saved job progress"),
                unavailable = listOf(
                    "Cloud voice assistant — no internet",
                    "Manuals — no reference pack downloaded",
                    "Offline AI — no local model is configured or validated",
                ),
            )
        }
    }
}
