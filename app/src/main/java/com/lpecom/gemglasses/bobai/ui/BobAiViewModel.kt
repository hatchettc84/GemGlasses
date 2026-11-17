package com.lpecom.gemglasses.bobai.ui

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
import javax.inject.Inject

/** Truthful glasses status. Mock builds never claim real hardware is connected. */
data class GlassesStatus(val label: String, val detail: String, val ok: Boolean)

@HiltViewModel
class BobAiViewModel @Inject constructor(
    application: Application,
    val controller: BobAiController,
    private val glassesManager: GlassesManager,
    private val cameraSource: GlassesCameraSource,
) : AndroidViewModel(application) {

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
                    message = "No image from the glasses. Nothing was saved — try again."
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
     */
    private suspend fun grabGlassesFrame(): ByteArray? {
        var last: ByteArray? = null
        cameraSource.runBurst(maxDurationMs = 15_000, maxFrames = 2) { last = it }
        return last
    }

    val snapshot: StateFlow<StoreSnapshot> = controller.state

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online

    val mode: StateFlow<ModeStatus> = combine(_online, controller.state) { net, s ->
        val now = System.currentTimeMillis()
        ModeResolver.resolve(net, s.packs.count { it.isUsable(now) }, localModelValidated = false)
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
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { _online.value = currentlyOnline() }
        override fun onLost(network: Network) { _online.value = currentlyOnline() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { _online.value = currentlyOnline() }
    }

    init {
        runCatching {
            connectivity.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
        }
    }

    private fun currentlyOnline(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    override fun onCleared() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }

    private companion object {
        const val CAPTURE_DIR = "captures"
    }
}
