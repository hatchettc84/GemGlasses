package com.lpecom.gemglasses.gemini

import com.lpecom.gemglasses.BuildConfig
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
 * Fetches an ephemeral Gemini Live token from the backend Worker, authenticating through [BackendAuth].
 */
@Singleton
class TokenProvider internal constructor(
    private val client: OkHttpClient,
    private val json: Json,
    private val auth: BackendAuth,
    private val baseUrl: String,
) {
    @Inject constructor(client: OkHttpClient, json: Json, auth: BackendAuth) :
        this(client, json, auth, BuildConfig.BACKEND_URL)

    @Serializable
    private data class TokenResponse(val token: String, val expiresAt: String? = null)

    /** Returns a fresh ephemeral token, or throws on transport/auth failure. */
    suspend fun fetchEphemeralToken(): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/token")
            .post(ByteArray(0).toRequestBody(JSON_MEDIA))
        val sent = auth.authorize(builder)

        client.newCall(builder.build()).execute().use { response ->
            val body = response.body?.string().orEmpty()
            auth.checkResponse(sent, response.code, body)
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
