package com.lpecom.gemglasses.bobai.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.bobai.data.BobAiController
import com.lpecom.gemglasses.bobai.data.StoreSnapshot
import com.lpecom.gemglasses.bobai.domain.ModeResolver
import com.lpecom.gemglasses.bobai.domain.ModeStatus
import com.lpecom.gemglasses.bobai.live.AssistanceFacts
import com.lpecom.gemglasses.bobai.live.BobVoiceAssistant
import com.lpecom.gemglasses.bobai.live.AssistanceModes
import com.lpecom.gemglasses.bobai.live.MediaStatus
import com.lpecom.gemglasses.bobai.live.ModeAvailability
import com.lpecom.gemglasses.bobai.workmode.WorkModeController
import com.lpecom.gemglasses.bobai.workmode.WorkModeState
import com.lpecom.gemglasses.glasses.GlassesCameraSource
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.glasses.RegistrationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/** Truthful glasses status. Mock builds never claim real hardware is connected. */
data class GlassesStatus(val label: String, val detail: String, val ok: Boolean)

/** Office console pairing as shown in Settings. */
data class PairingUi(
    val paired: Boolean = false,
    val userName: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class BobAiViewModel @Inject constructor(
    application: Application,
    val controller: BobAiController,
    private val glassesManager: GlassesManager,
    private val cameraSource: GlassesCameraSource,
    val workMode: WorkModeController,
    val voice: BobVoiceAssistant,
    val offline: com.lpecom.gemglasses.bobai.offline.OfflineAssistant,
    private val pairingClient: com.lpecom.gemglasses.bobai.sync.PairingClient,
    private val deviceKeys: com.lpecom.gemglasses.bobai.sync.DeviceKeyStore,
) : AndroidViewModel(application) {

    /** Loads the on-device model (pushed with adb for the spike) and runs its self-test. */
    fun loadOfflineModel() {
        viewModelScope.launch { offline.load() }
    }

    /** Asks the on-device path: manual search first, the model only rephrases the excerpt. */
    fun askOffline(question: String) {
        if (question.isBlank() || offline.state.value.busy) return
        viewModelScope.launch { offline.answer(question.trim()) }
    }

    /**
     * Hardware check: runs a 5-second camera burst, saves the first frame to
     * app storage (files/glasses_test.jpg) and reports how many frames arrived.
     */
    fun testGlassesCamera() {
        viewModelScope.launch {
            message = "Testing glasses camera…"
            var first: ByteArray? = null
            val count = cameraSource.runBurst(maxDurationMs = 10_000, maxFrames = 10) { jpeg ->
                if (first == null) first = jpeg
            }
            val saved = first?.let { bytes ->
                File(getApplication<Application>().filesDir, "glasses_test.jpg").writeBytes(bytes)
                bytes.size
            }
            message = if (saved != null) "Glasses camera: $count frame(s), first ${saved / 1024} KB saved."
            else "Glasses camera: no frames received."
        }
    }

    /** Opens the Meta AI app to connect BobAI to the glasses. */
    fun registerGlasses() = glassesManager.startRegistration()

    /** Opens the Meta AI app to remove BobAI's glasses registration. */
    fun unregisterGlasses() = glassesManager.startUnregistration()

    /** Asks Meta AI for glasses camera access and reports the outcome. */
    fun requestGlassesCamera() {
        viewModelScope.launch {
            val granted = glassesManager.ensureCameraPermission()
            message = if (granted) "Glasses camera allowed." else "Glasses camera not allowed."
        }
    }

    /** True only while the glasses camera is open for an explicit capture. */
    var capturing by mutableStateOf(false)
        private set

    /**
     * Explicit, user-initiated capture into the active job. Real-glasses
     * builds open the glasses camera just long enough for one settled frame
     * and save it to app storage; mock builds record a labelled placeholder.
     * Never falls back to a placeholder when real glasses are expected.
     */
    fun capture() {
        if (capturing) return
        if (!BuildConfig.USE_REAL_GLASSES) {
            controller.mockCapture()
            return
        }
        val session = controller.activeSession()
        if (session == null) {
            message = "Start a job session to capture a photo."
            return
        }
        if (!glasses.value.ok) {
            message = "Glasses not connected. Connect them in Settings → Hardware, then capture again."
            return
        }
        viewModelScope.launch {
            capturing = true
            try {
                val jpeg = grabGlassesFrame()
                if (jpeg == null) {
                    message = "No image from the glasses. Nothing was saved — check they're on and awake, then try again."
                    return@launch
                }
                val ref = "$CAPTURE_DIR/${session.id}-${System.currentTimeMillis()}.jpg"
                val file = captureFile(ref)
                withContext(Dispatchers.IO) {
                    file.parentFile?.mkdirs()
                    file.writeBytes(jpeg)
                }
                message = if (controller.addCapture(ref, "glasses") != null) {
                    "Photo saved to this job."
                } else {
                    file.delete()
                    "The session ended before the photo was saved."
                }
            } finally {
                capturing = false
            }
        }
    }

    /** Where a capture reference lives on disk (app-private storage). */
    fun captureFile(ref: String): File = File(getApplication<Application>().filesDir, ref)

    /**
     * Opens the glasses camera for one capture. The first frame arrives about
     * 5 s after the stream starts; keeping the second frame gives the camera a
     * moment to settle exposure. The stream closes as soon as it returns.
     *
     * The first stream after the glasses have been idle sometimes fails while
     * their link reconnects, so a capture that gets no frame is retried once.
     */
    private suspend fun grabGlassesFrame(): ByteArray? {
        repeat(CAPTURE_ATTEMPTS) { attempt ->
            var last: ByteArray? = null
            cameraSource.runBurst(maxDurationMs = 15_000, maxFrames = 2) { last = it }
            last?.let { return it }
            if (attempt < CAPTURE_ATTEMPTS - 1) {
                Log.i(TAG, "glasses capture got no frame; retrying once the glasses reconnect")
                message = "Glasses camera reconnecting — retrying…"
                // After a stream error the glasses drop off for a moment and
                // reconnect; retrying during that gap fails instantly.
                delay(RECONNECT_SETTLE_MS)
                val reconnected = withTimeoutOrNull(RECONNECT_TIMEOUT_MS) { glasses.first { it.ok } }
                if (reconnected == null) return null
            }
        }
        return null
    }

    val snapshot: StateFlow<StoreSnapshot> = controller.state

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online

    private val _pairing = MutableStateFlow(deviceKeys.get().let { PairingUi(paired = it != null, userName = it?.userName) })
    val pairing: StateFlow<PairingUi> = _pairing

    /** Pairs this phone with the office console using a 6-digit code from an admin. */
    fun pairPhone(code: String) {
        if (_pairing.value.busy) return
        _pairing.value = _pairing.value.copy(busy = true, message = null)
        viewModelScope.launch {
            _pairing.value = when (val r = pairingClient.pair(code, android.os.Build.MODEL)) {
                is com.lpecom.gemglasses.bobai.sync.PairResult.Paired ->
                    PairingUi(paired = true, userName = r.userName, message = "Paired to ${r.userName}.")
                is com.lpecom.gemglasses.bobai.sync.PairResult.Rejected -> PairingUi(message = r.message)
                is com.lpecom.gemglasses.bobai.sync.PairResult.Failed -> PairingUi(message = r.message)
            }
        }
    }

    /** Forgets the device key on this phone. An admin can also remove the phone in the console. */
    fun unpairPhone() {
        deviceKeys.clear()
        _pairing.value = PairingUi(message = "This phone is unpaired. Your admin can also remove it in the console.")
    }

    val mode: StateFlow<ModeStatus> = combine(_online, controller.state, offline.state) { net, s, o ->
        val now = System.currentTimeMillis()
        // Only a loaded model that passed its grounding self-test counts.
        ModeResolver.resolve(net, s.packs.count { it.isUsable(now) }, localModelValidated = o.validated)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ModeResolver.resolve(_online.value, 0, false))

    val glasses: StateFlow<GlassesStatus> =
        combine(glassesManager.registrationState, glassesManager.devices) { reg, devices ->
            if (!BuildConfig.USE_REAL_GLASSES) {
                GlassesStatus("Mock glasses", "Mock backend — no hardware in this build", ok = false)
            } else {
                val connected = devices.any { it.connected }
                when {
                    reg == RegistrationState.REGISTERED && connected -> GlassesStatus("Glasses connected", devices.first { it.connected }.name, true)
                    reg == RegistrationState.REGISTERED -> GlassesStatus("Glasses disconnected", "Registered — not currently connected", false)
                    reg == RegistrationState.REGISTERING -> GlassesStatus("Glasses registering", "Finish in the Meta AI app", false)
                    reg == RegistrationState.REVOKED -> GlassesStatus("Glasses access revoked", "Re-register in the Meta AI app", false)
                    else -> GlassesStatus("Glasses not set up", "Register in Settings → Hardware", false)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, GlassesStatus("Glasses status unknown", "Checking…", false))

    val workModeState: StateFlow<WorkModeState> = workMode.state

    /** Addendum §5 assistance modes, from facts only. */
    val assistanceModes: StateFlow<List<ModeAvailability>> =
        combine(_online, controller.state, glasses, offline.state) { net, s, g, o ->
            val now = System.currentTimeMillis()
            AssistanceModes.resolve(
                AssistanceFacts(
                    online = net,
                    glassesConnected = g.ok,
                    usableReferencePacks = s.packs.count { it.isUsable(now) },
                    bobAiVoiceProviderWired = voice.configured,
                    liveViewProviderWired = voice.configured,
                    localModelValidated = o.validated,
                ),
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Camera media status. Live view (Gemini) wins over a local capture; nothing is recorded. */
    val mediaStatus: MediaStatus
        get() = when {
            voice.state.value.media == MediaStatus.CLOUD_TRANSMISSION -> MediaStatus.CLOUD_TRANSMISSION
            capturing -> MediaStatus.LOCAL_CAPTURE
            else -> MediaStatus.CAMERA_OFF
        }

    // UI state that must survive fold/unfold (configuration change) — held here, not in composables.
    var draftInput by mutableStateOf("")
    var referenceDocId by mutableStateOf<String?>(null)
    var referencePage by mutableStateOf<Int?>(null)
    var referenceTab by mutableStateOf(0)
    var message by mutableStateOf<String?>(controller.loadWarning)

    fun openReference(docId: String, page: Int?) {
        referenceDocId = docId
        referencePage = page
        referenceTab = 0
    }

    private val connectivity = application.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    // Track only the system default network and trust the capabilities its
    // callback delivers. Listening to every network and re-reading
    // activeNetwork on each event could latch "offline" when a secondary
    // network (cellular, VPN) dropped while the default was switching.
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _online.value = caps.isOnline()
        }
        override fun onLost(network: Network) { _online.value = false }
    }

    init {
        runCatching { connectivity.registerDefaultNetworkCallback(callback) }
            .onFailure { Log.w("BobAiViewModel", "network callback failed", it) }
    }

    private fun currentlyOnline(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.isOnline() ?: false
    }

    private fun NetworkCapabilities.isOnline() =
        hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

    override fun onCleared() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val CAPTURE_DIR = "captures"
        const val CAPTURE_ATTEMPTS = 2
        const val RECONNECT_SETTLE_MS = 1_500L
        const val RECONNECT_TIMEOUT_MS = 8_000L
        const val TAG = "BobAiViewModel"
    }
}
