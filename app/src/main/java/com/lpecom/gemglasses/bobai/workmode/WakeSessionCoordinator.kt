package com.lpecom.gemglasses.bobai.workmode

import android.annotation.SuppressLint
import android.os.SystemClock
import android.util.Log
import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.bobai.live.BobVoiceAssistant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped owner of the "Hey Bob" hand-off ([WakeSessionCore]). It is
 * injected by the screen and by [WakeListenService], so it exists whenever
 * Work Mode can be on, and it keeps working after the Activity is destroyed.
 */
@Singleton
class WakeSessionCoordinator @Inject constructor(
    workMode: WorkModeController,
    voice: BobVoiceAssistant,
    controller: BobAiController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val core = WakeSessionCore(
        scope = scope,
        workMode = object : WakeWorkMode {
            override val state: StateFlow<WorkModeState> get() = workMode.state
            override fun hasMicPermission() = workMode.hasMicPermission()
            override fun acknowledge() = workMode.acknowledge()
            override fun endAssistance() = workMode.endAssistance()
            override fun idleTimeout() = workMode.idleTimeout()
        },
        voice = object : WakeVoice {
            override val active: Boolean get() = voice.state.value.active
            override val activeFlow: Flow<Boolean> = voice.state.map { it.active }.distinctUntilChanged()
            override fun lastActivityAt() = voice.lastActivityAt
            override fun prepare() { controller.startOrResume() }

            // Work Mode is only enabled after RECORD_AUDIO was granted, and the
            // core checks hasMicPermission() again right before this call.
            @SuppressLint("MissingPermission")
            override fun start() = voice.start()
            override fun stop() = voice.stop()
            override fun greet() =
                voice.sendAppNotice("The technician just said \"Hey Bob\". Greet them in a few words and ask how you can help.")
        },
        clock = SystemClock::elapsedRealtime,
        log = { Log.i(TAG, it) },
    )

    init {
        core.start()
    }

    private companion object {
        const val TAG = "WakeSession"
    }
}
