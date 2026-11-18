package com.lpecom.gemglasses.bobai.live

/*
 * Live assistance building blocks (addendum §5). Pure Kotlin; the provider
 * connection is not built yet. These keep the three data paths separate:
 * glasses frames reaching the phone, selected frames reaching an AI
 * provider, and recording (off by default).
 */

/** What is happening to camera media right now — shown to the technician verbatim. */
enum class MediaStatus(val label: String) {
    CAMERA_OFF("Camera off"),
    LOCAL_CAPTURE("Glasses camera on — stays on this phone"),
    CLOUD_TRANSMISSION("Sending frames to the AI service"),
    RECORDING("Recording to this phone"),
}

class TimedFrame(val jpeg: ByteArray, val capturedAt: Long)

/**
 * Holds at most one frame: the newest. Older frames are dropped rather than
 * queued, so a slow uplink can never build an upload backlog.
 */
class LatestFrameBuffer {
    private var latest: TimedFrame? = null

    var dropped: Int = 0
        private set

    @Synchronized
    fun offer(frame: TimedFrame) {
        if (latest != null) dropped++
        latest = frame
    }

    @Synchronized
    fun take(): TimedFrame? = latest.also { latest = null }

    @Synchronized
    fun clear() {
        latest = null
    }
}

/**
 * Decides which captured frames may be sent to the AI. The capture rate
 * (glasses camera) and the AI sampling rate are independent: frames arrive as
 * fast as the camera delivers them, and at most one is released per
 * [minIntervalMs]. Frames older than [maxAgeMs] are never released, and
 * [invalidate] discards everything seen before an interruption or a big
 * camera movement.
 */
class FrameSampler(
    private val minIntervalMs: Long,
    private val maxAgeMs: Long,
) {
    private val buffer = LatestFrameBuffer()
    private var lastReleasedAt: Long? = null
    private var invalidatedAt: Long = Long.MIN_VALUE

    val droppedFrames: Int get() = buffer.dropped

    fun offer(frame: TimedFrame) = buffer.offer(frame)

    /** Returns a fresh frame to send now, or null if none is due or fresh. */
    fun nextForUpload(now: Long): TimedFrame? {
        lastReleasedAt?.let { if (now - it < minIntervalMs) return null }
        val frame = buffer.take() ?: return null
        if (frame.capturedAt <= invalidatedAt || now - frame.capturedAt > maxAgeMs) return null
        lastReleasedAt = now
        return frame
    }

    fun invalidate(now: Long) {
        invalidatedAt = now
        buffer.clear()
    }
}

/** A visual observation the AI may use, which expires. */
data class VisualContext(val capturedAt: Long, val maxAgeMs: Long) {
    fun isStale(now: Long): Boolean = now - capturedAt > maxAgeMs
}

enum class AssistanceMode(val label: String) {
    VOICE("Voice assistance"),
    LOOK_NOW("Look now"),
    GUIDED_LIVE_VIEW("Guided live view"),
    OFFLINE_ASSISTED("Offline assisted"),
    OFFLINE_REFERENCE("Offline reference"),
}

data class ModeAvailability(val mode: AssistanceMode, val available: Boolean, val detail: String)

/** Facts only. Nothing here is assumed to work until it has been built and tested. */
data class AssistanceFacts(
    val online: Boolean,
    val glassesConnected: Boolean,
    val usableReferencePacks: Int,
    val bobAiVoiceProviderWired: Boolean = false,
    val liveViewProviderWired: Boolean = false,
    val localModelValidated: Boolean = false,
)

object AssistanceModes {

    fun resolve(f: AssistanceFacts): List<ModeAvailability> = listOf(
        when {
            !f.bobAiVoiceProviderWired -> ModeAvailability(
                AssistanceMode.VOICE, false,
                "Not wired into BobAI yet. The separate Gemini Live screen is under Settings → Live voice.",
            )
            !f.online -> ModeAvailability(AssistanceMode.VOICE, false, "Needs internet")
            else -> ModeAvailability(AssistanceMode.VOICE, true, "Camera stays off")
        },
        if (f.glassesConnected) {
            ModeAvailability(
                AssistanceMode.LOOK_NOW, true,
                "One glasses photo per Capture tap, saved to the job. Not sent to any AI yet.",
            )
        } else {
            ModeAvailability(AssistanceMode.LOOK_NOW, false, "Glasses not connected")
        },
        when {
            !f.liveViewProviderWired -> ModeAvailability(
                AssistanceMode.GUIDED_LIVE_VIEW, false,
                "Not built yet — needs a tested AI provider connection",
            )
            !f.online -> ModeAvailability(AssistanceMode.GUIDED_LIVE_VIEW, false, "Needs internet")
            !f.glassesConnected -> ModeAvailability(AssistanceMode.GUIDED_LIVE_VIEW, false, "Glasses not connected")
            else -> ModeAvailability(AssistanceMode.GUIDED_LIVE_VIEW, true, "Started and stopped by you")
        },
        if (f.localModelValidated && f.usableReferencePacks > 0) {
            ModeAvailability(AssistanceMode.OFFLINE_ASSISTED, true, "Validated local model")
        } else {
            ModeAvailability(
                AssistanceMode.OFFLINE_ASSISTED, false,
                "No local speech or AI model has been validated on this phone",
            )
        },
        if (f.usableReferencePacks > 0) {
            ModeAvailability(AssistanceMode.OFFLINE_REFERENCE, true, "Downloaded manuals, search and checklists")
        } else {
            ModeAvailability(AssistanceMode.OFFLINE_REFERENCE, false, "No usable reference pack downloaded")
        },
    )
}
