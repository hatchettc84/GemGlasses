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
import com.lpecom.gemglasses.glasses.GlassesManager
import com.lpecom.gemglasses.glasses.RegistrationState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Truthful glasses status. Mock builds never claim real hardware is connected. */
data class GlassesStatus(val label: String, val detail: String, val ok: Boolean)

@HiltViewModel
class BobAiViewModel @Inject constructor(
    application: Application,
    val controller: BobAiController,
    glassesManager: GlassesManager,
) : AndroidViewModel(application) {

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
}
