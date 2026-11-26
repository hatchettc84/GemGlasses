package com.lpecom.gemglasses.gemini

import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.bobai.sync.DeviceKeyStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** This phone was removed by an admin; it must be paired again before voice works. */
class DeviceRemovedException : Exception("This phone was removed. Ask your admin to pair it again.")

/**
 * The one place that decides how this phone authenticates to the backend:
 * paired -> its device key; removed -> never the shared secret (throws);
 * never paired -> the legacy shared app secret.
 */
@Singleton
class BackendAuth internal constructor(
    private val keyStore: DeviceKeyStore,
    private val json: Json,
    private val appSecret: String,
) {
    @Inject constructor(keyStore: DeviceKeyStore, json: Json) : this(keyStore, json, BuildConfig.APP_SECRET)

    /** What was sent: the device id whose key was used, or null for the shared secret. */
    class Sent internal constructor(internal val deviceId: String?)

    @Serializable
    private data class ErrorBody(val error: String = "")

    /** Adds the auth header to [builder]. Throws [DeviceRemovedException] for a removed phone. */
    fun authorize(builder: Request.Builder): Sent {
        if (keyStore.wasRemoved()) throw DeviceRemovedException()
        val pairing = keyStore.get()
        return if (pairing != null) {
            builder.header("Authorization", "Bearer ${pairing.deviceKey}")
            Sent(pairing.deviceId)
        } else {
            builder.header("X-App-Secret", appSecret)
            Sent(null)
        }
    }

    /**
     * Call with every response. A 401 device_revoked/device_unknown for a device key marks this
     * phone removed (only if that same pairing is still stored) and throws [DeviceRemovedException].
     */
    fun checkResponse(sent: Sent, code: Int, body: String) {
        val deviceId = sent.deviceId ?: return
        if (code != 401) return
        val error = runCatching { json.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()
        if (error == "device_revoked" || error == "device_unknown") {
            keyStore.markRemoved(deviceId)
            throw DeviceRemovedException()
        }
    }
}
