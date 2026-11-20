package com.lpecom.gemglasses.gemini

import com.lpecom.gemglasses.BuildConfig
import com.lpecom.gemglasses.bobai.sync.DeviceKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches an ephemeral Gemini Live token from the backend Worker, authenticating with this phone's device key when paired, else the shared app secret.
 */
/** This phone was removed by an admin; it must be paired again before voice works. */
class DeviceRemovedException : Exception("This phone was removed. Ask your admin to pair it again.")

@Singleton
class TokenProvider internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val keyStore: DeviceKeyStore,
    private val baseUrl: String,
    private val appSecret: String,
) {
    @Inject constructor(client: OkHttpClient, json: Json, keyStore: DeviceKeyStore) :
        this(client, json, keyStore, BuildConfig.BACKEND_URL, BuildConfig.APP_SECRET)

    @Serializable
    private data class TokenResponse(val token: String, val expiresAt: String? = null)

    @Serializable
    private data class ErrorBody(val error: String = "")

    /** Returns a fresh ephemeral token, or throws on transport/auth failure. */
    suspend fun fetchEphemeralToken(): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/token")
            .post(ByteArray(0).toRequestBody(JSON_MEDIA))
        // A paired phone uses its own key; the shared secret is the fallback
        // until every phone is paired.
        if (keyStore.wasRemoved()) throw DeviceRemovedException()
        val pairing = keyStore.get()
        if (pairing != null) builder.header("Authorization", "Bearer ${pairing.deviceKey}")
        else builder.header("X-App-Secret", appSecret)

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 401 && pairing != null) {
                val error = runCatching { json.decodeFromString(ErrorBody.serializer(), body).error }.getOrNull()
                if (error == "device_revoked" || error == "device_unknown") {
                    keyStore.markRemoved()
                    throw DeviceRemovedException()
                }
            }
            check(response.isSuccessful) {
                "Token endpoint returned ${response.code}: ${body.take(200)}"
            }
            json.decodeFromString(TokenResponse.serializer(), body).token
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
