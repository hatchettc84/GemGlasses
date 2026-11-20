package com.lpecom.gemglasses.bobai.sync

import com.lpecom.gemglasses.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

sealed interface PairResult {
    data class Paired(val userName: String) : PairResult
    /** The console refused (wrong/expired code, too many attempts); message is for the technician. */
    data class Rejected(val code: String, val message: String) : PairResult
    data class Failed(val message: String) : PairResult
}

/** Exchanges a pairing code from the office console for this phone's device key. */
@Singleton
class PairingClient internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val keyStore: DeviceKeyStore,
    private val baseUrl: String,
) {
    @Inject constructor(client: OkHttpClient, json: Json, keyStore: DeviceKeyStore) :
        this(client, json, keyStore, BuildConfig.BACKEND_URL)

    @Serializable private data class PairRequest(val code: String, val label: String)
    @Serializable private data class PairUser(val name: String, val email: String)
    @Serializable private data class PairResponse(val deviceKey: String, val deviceId: String, val user: PairUser)
    @Serializable private data class ErrorBody(val error: String = "", val message: String = "")

    suspend fun pair(code: String, label: String): PairResult = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(PairRequest.serializer(), PairRequest(code.trim(), label))
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/device/pair")
            .post(payload.toRequestBody(JSON_MEDIA))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val paired = json.decodeFromString(PairResponse.serializer(), text)
                    keyStore.save(DevicePairing(paired.deviceId, paired.deviceKey, paired.user.name))
                    PairResult.Paired(paired.user.name)
                } else {
                    val err = runCatching { json.decodeFromString(ErrorBody.serializer(), text) }.getOrNull()
                    if (response.code in 400..499 && err != null && err.message.isNotBlank()) {
                        PairResult.Rejected(err.error, err.message)
                    } else {
                        PairResult.Failed("The office console returned an error (${response.code}). Try again.")
                    }
                }
            }
        } catch (e: IOException) {
            PairResult.Failed("Couldn't reach the office console. Check the connection and try again.")
        }
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
